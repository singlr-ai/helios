/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.memory.MemoryTools;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.TraceBuilder;
import ai.singlr.core.trace.TraceDetail;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The main agent that orchestrates LLM calls and tool execution. Runs to completion by default, or
 * can be stepped manually.
 *
 * <p>Supports structured output via {@link OutputSchema}. When an output schema is provided, the
 * agent passes it through to the model on every call. The model may still invoke tools before
 * producing the final structured response.
 *
 * <p>All runs are session-scoped. When memory is configured, the agent loads prior history from
 * memory before the run and persists new messages as the conversation progresses.
 */
public class Agent {

  private final AgentConfig config;
  private final Map<String, Tool> toolMap;
  private final ContextCompactor compactor;
  private UUID cachedSessionId;
  private Map<String, Tool> cachedTools;

  public Agent(AgentConfig config) {
    this.config = config;
    this.toolMap = new HashMap<>();
    for (var tool : config.tools()) {
      toolMap.put(tool.name(), tool);
    }
    this.compactor = new ContextCompactor(config.model());
  }

  /**
   * Run the agent to completion with a simple user input. Creates a session internally.
   *
   * @param userInput the user's message
   * @return the agent's final response
   */
  public Result<Response> run(String userInput) {
    return run(SessionContext.of(userInput));
  }

  /**
   * Run the agent to completion within a session.
   *
   * @param session the session context carrying user input and prompt variables
   * @return the agent's final response
   */
  public Result<Response> run(SessionContext session) {
    return toResponse(runLoop(session, null));
  }

  /**
   * Run the agent to completion within a session, with structured output.
   *
   * @param <T> the type of the structured output
   * @param session the session context carrying user input and prompt variables
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  public <T> Result<Response<T>> run(SessionContext session, OutputSchema<T> outputSchema) {
    return toTypedResponse(runLoop(session, outputSchema));
  }

  // --- Streaming API ---

  /**
   * Stream the agent's response for a simple user input. Creates a session internally.
   *
   * <p>Returns a closeable iterator of stream events. Use with try-with-resources to ensure cleanup
   * of the background thread and resources.
   *
   * @param userInput the user's message
   * @return closeable iterator of stream events
   */
  public CloseableIterator<StreamEvent> runStream(String userInput) {
    return runStream(SessionContext.of(userInput));
  }

  /**
   * Stream the agent's response within a session.
   *
   * <p>Starts a background virtual thread running the agent loop. Stream events (text deltas, tool
   * calls) flow through a queue to the returned iterator. The iterator yields a terminal {@link
   * StreamEvent.Done} or {@link StreamEvent.Error} event when the agent completes.
   *
   * <p>Use with try-with-resources:
   *
   * <pre>{@code
   * try (var events = agent.runStream(session)) {
   *   while (events.hasNext()) {
   *     switch (events.next()) {
   *       case StreamEvent.TextDelta td -> System.out.print(td.text());
   *       case StreamEvent.Done done -> System.out.println("\nDone!");
   *       case StreamEvent.Error err -> System.err.println(err.message());
   *       default -> {}
   *     }
   *   }
   * }
   * }</pre>
   *
   * @param session the session context carrying user input and prompt variables
   * @return closeable iterator of stream events
   */
  public CloseableIterator<StreamEvent> runStream(SessionContext session) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var thread = Thread.ofVirtual().name("agent-stream").start(() -> streamLoop(session, queue));
    return new AgentStreamIterator(queue, thread);
  }

  // --- Step-based API ---

  /**
   * Execute a single step of the agent loop. Call model, execute any tool calls, return new state.
   *
   * @param state the current state of the agent
   */
  public Result<AgentState> step(AgentState state) {
    return step(state, null, null);
  }

  /**
   * Execute a single step with structured output.
   *
   * @param <T> the type of the structured output
   * @param state the current state of the agent
   * @param outputSchema the schema for structured output
   */
  public <T> Result<AgentState> step(AgentState state, OutputSchema<T> outputSchema) {
    return step(state, outputSchema, null);
  }

  Result<AgentState> step(
      AgentState state, OutputSchema<?> outputSchema, TraceBuilder traceBuilder) {
    if (state.isComplete()) {
      return Result.success(state);
    }

    if (state.iterations() >= config.maxIterations()) {
      return Result.success(
          AgentState.newBuilder(state)
              .withError("Max iterations (%d) reached".formatted(config.maxIterations()))
              .build());
    }

    var runTools = resolveTools(state.userId(), state.sessionId());
    var messages = compactor.compactIfNeeded(state.messages());

    SpanBuilder modelSpan = null;

    try {
      if (traceBuilder != null) {
        modelSpan = traceBuilder.span("model.chat", SpanKind.MODEL_CALL);
        modelSpan.attribute("model", config.model().id());
      }

      var response = callModel(messages, outputSchema, runTools);

      if (modelSpan != null) {
        if (response.usage() != null) {
          modelSpan.attribute("inputTokens", String.valueOf(response.usage().inputTokens()));
          modelSpan.attribute("outputTokens", String.valueOf(response.usage().outputTokens()));
        }
        if (response.finishReason() != null) {
          modelSpan.attribute("finishReason", response.finishReason().name());
        }
        if (config.traceDetail() == TraceDetail.VERBOSE && response.hasThinking()) {
          modelSpan.attribute("thinking", response.thinking());
        }
        modelSpan.end();
        modelSpan = null;
      }

      var newMessages = new ArrayList<>(messages);
      newMessages.add(response.toMessage());

      if (config.memory() != null && state.sessionId() != null) {
        config.memory().addMessage(state.userId(), state.sessionId(), response.toMessage());
      }

      if (!response.hasToolCalls()) {
        return Result.success(
            AgentState.newBuilder()
                .withMessages(newMessages)
                .withLastResponse(response)
                .withIterations(state.iterations() + 1)
                .withComplete(true)
                .withUserId(state.userId())
                .withSessionId(state.sessionId())
                .build());
      }

      var toolMessages = executeToolCalls(response.toolCalls(), runTools, traceBuilder);
      newMessages.addAll(toolMessages);
      if (config.memory() != null && state.sessionId() != null) {
        for (var msg : toolMessages) {
          config.memory().addMessage(state.userId(), state.sessionId(), msg);
        }
      }

      return Result.success(
          AgentState.newBuilder()
              .withMessages(newMessages)
              .withLastResponse(response)
              .withIterations(state.iterations() + 1)
              .withComplete(false)
              .withUserId(state.userId())
              .withSessionId(state.sessionId())
              .build());

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      return Result.failure("Agent step failed: " + e.getMessage(), e);
    }
  }

  public AgentState initialState(String userMessage, Map<String, String> promptVars) {
    return initialState(userMessage, promptVars, null, null, List.of());
  }

  AgentState initialState(
      String userMessage,
      Map<String, String> promptVars,
      String userId,
      UUID sessionId,
      List<InlineFile> inlineFiles) {
    var messages = new ArrayList<Message>();
    var systemPrompt = buildSystemPrompt(promptVars);
    messages.add(Message.system(systemPrompt));

    if (config.memory() != null && sessionId != null) {
      messages.addAll(config.memory().history(userId, sessionId));
    }

    var userMsg = Message.user(userMessage, inlineFiles);
    messages.add(userMsg);

    if (config.memory() != null && sessionId != null) {
      config.memory().addMessage(userId, sessionId, Message.user(userMessage));
    }

    return AgentState.newBuilder()
        .withMessages(messages)
        .withUserId(userId)
        .withSessionId(sessionId)
        .build();
  }

  private Result<AgentState> runLoop(SessionContext session, OutputSchema<?> outputSchema) {
    if (session == null) {
      return Result.failure("session must not be null");
    }
    var userMessage = session.userInput();
    if (userMessage == null || userMessage.isBlank()) {
      return Result.failure("userInput must not be null or blank");
    }
    var state =
        initialState(
            userMessage,
            session.promptVars(),
            session.userId(),
            session.sessionId(),
            session.inlineFiles());

    if (config.memory() != null && session.userId() != null && state.sessionId() != null) {
      config.memory().registerSession(session.userId(), state.sessionId());
    }

    var traceBuilder =
        config.tracingEnabled() ? TraceBuilder.start(config.name(), config.traceListeners()) : null;

    if (traceBuilder != null) {
      traceBuilder
          .inputText(userMessage)
          .userId(session.userId())
          .sessionId(session.sessionId())
          .modelId(config.model().id());
      if (config.promptName() != null) {
        traceBuilder.promptName(config.promptName());
      }
      if (config.promptVersion() != null) {
        traceBuilder.promptVersion(config.promptVersion());
      }
      var groupId = session.metadata().get("groupId");
      if (groupId != null) {
        traceBuilder.groupId(groupId);
      }
    }

    while (!state.isComplete()) {
      var result = step(state, outputSchema, traceBuilder);
      if (result.isFailure()) {
        var failure = (Result.Failure<AgentState>) result;
        if (traceBuilder != null) {
          traceBuilder.fail(failure.error());
        }
        return Result.failure(failure.error(), failure.cause());
      }
      state = ((Result.Success<AgentState>) result).value();
    }

    if (state.isError()) {
      if (traceBuilder != null) {
        traceBuilder.fail(state.error());
      }
      return Result.failure(state.error());
    }

    if (traceBuilder != null) {
      var finalResponse = state.finalResponse();
      if (finalResponse != null && finalResponse.content() != null) {
        traceBuilder.outputText(finalResponse.content());
      }
      traceBuilder.end();
    }
    return Result.success(state);
  }

  private void streamLoop(SessionContext session, LinkedBlockingQueue<StreamEvent> queue) {
    TraceBuilder traceBuilder = null;
    try {
      if (session == null) {
        queue.put(new StreamEvent.Error("session must not be null"));
        return;
      }
      var userMessage = session.userInput();
      if (userMessage == null || userMessage.isBlank()) {
        queue.put(new StreamEvent.Error("userInput must not be null or blank"));
        return;
      }

      var state =
          initialState(
              userMessage,
              session.promptVars(),
              session.userId(),
              session.sessionId(),
              session.inlineFiles());

      if (config.memory() != null && session.userId() != null && state.sessionId() != null) {
        config.memory().registerSession(session.userId(), state.sessionId());
      }

      traceBuilder =
          config.tracingEnabled()
              ? TraceBuilder.start(config.name(), config.traceListeners())
              : null;
      if (traceBuilder != null) {
        traceBuilder
            .inputText(userMessage)
            .userId(session.userId())
            .sessionId(session.sessionId())
            .modelId(config.model().id());
        if (config.promptName() != null) {
          traceBuilder.promptName(config.promptName());
        }
        if (config.promptVersion() != null) {
          traceBuilder.promptVersion(config.promptVersion());
        }
        var groupId = session.metadata().get("groupId");
        if (groupId != null) {
          traceBuilder.groupId(groupId);
        }
      }

      while (!state.isComplete()) {
        state = streamStep(state, queue, traceBuilder);
      }

      if (state.isError()) {
        if (traceBuilder != null) {
          traceBuilder.fail(state.error());
        }
        queue.put(new StreamEvent.Error(state.error()));
        return;
      }

      if (traceBuilder != null) {
        var finalResponse = state.finalResponse();
        if (finalResponse != null && finalResponse.content() != null) {
          traceBuilder.outputText(finalResponse.content());
        }
        traceBuilder.end();
      }
      queue.put(new StreamEvent.Done(state.finalResponse()));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (traceBuilder != null) {
        traceBuilder.fail(e.getMessage());
      }
      try {
        queue.put(new StreamEvent.Error(e.getMessage(), e));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private AgentState streamStep(
      AgentState state, LinkedBlockingQueue<StreamEvent> queue, TraceBuilder traceBuilder)
      throws Exception {

    if (state.iterations() >= config.maxIterations()) {
      return AgentState.newBuilder(state)
          .withError("Max iterations (%d) reached".formatted(config.maxIterations()))
          .build();
    }

    var runTools = resolveTools(state.userId(), state.sessionId());
    var messages = compactor.compactIfNeeded(state.messages());

    SpanBuilder modelSpan = null;

    try {
      if (traceBuilder != null) {
        modelSpan = traceBuilder.span("model.chat", SpanKind.MODEL_CALL);
        modelSpan.attribute("model", config.model().id());
      }

      var tools = List.copyOf(runTools.values());
      Response<?> response;
      try (var stream =
          config.faultTolerance().execute(() -> config.model().chatStream(messages, tools))) {
        response = drainStreamToQueue(stream, queue);
      }

      if (modelSpan != null) {
        if (response.usage() != null) {
          modelSpan.attribute("inputTokens", String.valueOf(response.usage().inputTokens()));
          modelSpan.attribute("outputTokens", String.valueOf(response.usage().outputTokens()));
        }
        if (response.finishReason() != null) {
          modelSpan.attribute("finishReason", response.finishReason().name());
        }
        if (config.traceDetail() == TraceDetail.VERBOSE && response.hasThinking()) {
          modelSpan.attribute("thinking", response.thinking());
        }
        modelSpan.end();
        modelSpan = null;
      }

      var newMessages = new ArrayList<>(messages);
      newMessages.add(response.toMessage());

      if (config.memory() != null && state.sessionId() != null) {
        config.memory().addMessage(state.userId(), state.sessionId(), response.toMessage());
      }

      if (!response.hasToolCalls()) {
        return AgentState.newBuilder()
            .withMessages(newMessages)
            .withLastResponse(response)
            .withIterations(state.iterations() + 1)
            .withComplete(true)
            .withUserId(state.userId())
            .withSessionId(state.sessionId())
            .build();
      }

      var toolMessages = executeToolCalls(response.toolCalls(), runTools, traceBuilder);
      newMessages.addAll(toolMessages);
      if (config.memory() != null && state.sessionId() != null) {
        for (var msg : toolMessages) {
          config.memory().addMessage(state.userId(), state.sessionId(), msg);
        }
      }

      return AgentState.newBuilder()
          .withMessages(newMessages)
          .withLastResponse(response)
          .withIterations(state.iterations() + 1)
          .withComplete(false)
          .withUserId(state.userId())
          .withSessionId(state.sessionId())
          .build();

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      throw e;
    }
  }

  private Response<?> drainStreamToQueue(
      CloseableIterator<StreamEvent> stream, LinkedBlockingQueue<StreamEvent> queue)
      throws InterruptedException {
    while (stream.hasNext()) {
      var event = stream.next();
      switch (event) {
        case StreamEvent.TextDelta td -> queue.put(td);
        case StreamEvent.ToolCallStart tcs -> queue.put(tcs);
        case StreamEvent.ToolCallDelta tcd -> queue.put(tcd);
        case StreamEvent.ToolCallComplete tcc -> queue.put(tcc);
        case StreamEvent.Done done -> {
          return done.response();
        }
        case StreamEvent.Error err -> throw new RuntimeException(err.message(), err.cause());
      }
    }
    throw new IllegalStateException("Stream ended without Done or Error event");
  }

  /**
   * Creates a {@link Tool} that delegates to a fresh agent on each invocation. The tool accepts a
   * single "task" string parameter and returns the agent's response content.
   *
   * <p>Each invocation creates a new {@code Agent} from the provided config, so concurrent calls
   * are safe.
   *
   * @param name the tool name
   * @param description the tool description (shown to the calling model)
   * @param agentConfig the config for the sub-agent
   * @return a delegation tool
   */
  public static Tool asTool(String name, String description, AgentConfig agentConfig) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name required");
    }
    if (agentConfig == null) {
      throw new IllegalArgumentException("agentConfig required");
    }
    return Tool.newBuilder()
        .withName(name)
        .withDescription(description != null ? description : name)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("task")
                .withType(ParameterType.STRING)
                .withDescription("The task to delegate to this agent")
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              var task = (String) args.get("task");
              var result = new Agent(agentConfig).run(task);
              return switch (result) {
                case Result.Success<Response> s -> ToolResult.success(s.value().content());
                case Result.Failure<Response> f -> ToolResult.failure(f.error());
              };
            })
        .build();
  }

  // --- Tool execution helpers ---

  private List<Message> executeToolCalls(
      List<ToolCall> toolCalls, Map<String, Tool> runTools, TraceBuilder traceBuilder)
      throws Exception {
    if (config.parallelToolExecution() && toolCalls.size() > 1) {
      return executeToolCallsParallel(toolCalls, runTools, traceBuilder);
    }
    return executeToolCallsSequential(toolCalls, runTools, traceBuilder);
  }

  private List<Message> executeToolCallsSequential(
      List<ToolCall> toolCalls, Map<String, Tool> runTools, TraceBuilder traceBuilder)
      throws Exception {
    var toolMessages = new ArrayList<Message>(toolCalls.size());
    for (var toolCall : toolCalls) {
      var toolSpan = createToolSpan(toolCall, traceBuilder);
      var toolResult = executeSingleTool(toolCall, runTools, toolSpan);
      toolMessages.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));
    }
    return toolMessages;
  }

  private List<Message> executeToolCallsParallel(
      List<ToolCall> toolCalls, Map<String, Tool> runTools, TraceBuilder traceBuilder) {
    var spans = new SpanBuilder[toolCalls.size()];
    if (traceBuilder != null) {
      for (int i = 0; i < toolCalls.size(); i++) {
        spans[i] = createToolSpan(toolCalls.get(i), traceBuilder);
      }
    }

    var results = new ToolResult[toolCalls.size()];
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < toolCalls.size(); i++) {
        final int idx = i;
        final var toolCall = toolCalls.get(i);
        final var span = spans[idx];
        executor.submit(
            () -> {
              try {
                results[idx] = executeSingleTool(toolCall, runTools, span);
              } catch (Exception e) {
                results[idx] = ToolResult.failure("Tool execution failed: " + e.getMessage());
              }
            });
      }
    }

    var toolMessages = new ArrayList<Message>(toolCalls.size());
    for (int i = 0; i < toolCalls.size(); i++) {
      var tc = toolCalls.get(i);
      var result =
          results[i] != null
              ? results[i]
              : ToolResult.failure("Tool execution failed unexpectedly");
      toolMessages.add(Message.tool(tc.id(), tc.name(), result.output()));
    }
    return toolMessages;
  }

  private SpanBuilder createToolSpan(ToolCall toolCall, TraceBuilder traceBuilder) {
    if (traceBuilder == null) {
      return null;
    }
    var span = traceBuilder.span("tool." + toolCall.name(), SpanKind.TOOL_EXECUTION);
    span.attribute("toolName", toolCall.name());
    span.attribute("toolCallId", toolCall.id());
    if (config.traceDetail() == TraceDetail.VERBOSE && toolCall.arguments() != null) {
      span.attribute("arguments", toolCall.arguments().toString());
    }
    return span;
  }

  private ToolResult executeSingleTool(
      ToolCall toolCall, Map<String, Tool> runTools, SpanBuilder toolSpan) throws Exception {
    var tool = runTools.get(toolCall.name());
    if (tool == null) {
      var result = ToolResult.failure("Unknown tool: " + toolCall.name());
      if (toolSpan != null) {
        toolSpan.fail("Unknown tool: " + toolCall.name());
      }
      return result;
    }
    try {
      var toolResult = config.faultTolerance().execute(() -> tool.execute(toolCall.arguments()));
      if (toolSpan != null) {
        if (config.traceDetail() == TraceDetail.VERBOSE) {
          toolSpan.attribute("result", toolResult.output());
        }
        if (toolResult.success()) {
          toolSpan.end();
        } else {
          toolSpan.fail(toolResult.output());
        }
      }
      return toolResult;
    } catch (Exception e) {
      if (toolSpan != null) {
        toolSpan.fail(e.getMessage());
      }
      throw e;
    }
  }

  private Map<String, Tool> resolveTools(String userId, UUID sessionId) {
    if (!config.includeMemoryTools() || config.memory() == null || sessionId == null) {
      return toolMap;
    }
    if (sessionId.equals(cachedSessionId)) {
      return cachedTools;
    }
    var merged = new HashMap<>(toolMap);
    for (var tool : MemoryTools.boundTo(config.memory(), userId, sessionId)) {
      merged.put(tool.name(), tool);
    }
    cachedTools = Map.copyOf(merged);
    cachedSessionId = sessionId;
    return cachedTools;
  }

  private Response<?> callModel(
      List<Message> messages, OutputSchema<?> outputSchema, Map<String, Tool> runTools)
      throws Exception {
    var tools = List.copyOf(runTools.values());
    if (outputSchema != null) {
      return config
          .faultTolerance()
          .execute(() -> config.model().chat(messages, tools, outputSchema));
    }
    return config.faultTolerance().execute(() -> config.model().chat(messages, tools));
  }

  private String buildSystemPrompt(Map<String, String> extraVars) {
    var vars = new HashMap<String, String>();
    vars.put("name", config.name());

    if (config.memory() != null) {
      vars.put("core_memory", config.memory().renderCoreMemory());
    } else {
      vars.put("core_memory", "(no memory configured)");
    }

    vars.putAll(extraVars);
    return Strings.render(config.systemPrompt(), vars);
  }

  private Result<Response> toResponse(Result<AgentState> result) {
    if (result.isFailure()) {
      var failure = (Result.Failure<AgentState>) result;
      return Result.failure(failure.error(), failure.cause());
    }
    return Result.success(((Result.Success<AgentState>) result).value().finalResponse());
  }

  @SuppressWarnings("unchecked")
  private <T> Result<Response<T>> toTypedResponse(Result<AgentState> result) {
    if (result.isFailure()) {
      var failure = (Result.Failure<AgentState>) result;
      return Result.failure(failure.error(), failure.cause());
    }
    var state = ((Result.Success<AgentState>) result).value();
    return Result.success((Response<T>) state.finalResponse());
  }

  public AgentConfig config() {
    return config;
  }

  public List<Tool> tools() {
    return config.tools();
  }
}
