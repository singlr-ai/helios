/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.memory.MemoryTools;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.TraceBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
  private UUID cachedSessionId;
  private Map<String, Tool> cachedTools;

  public Agent(AgentConfig config) {
    this.config = config;
    this.toolMap = new HashMap<>();
    for (var tool : config.tools()) {
      toolMap.put(tool.name(), tool);
    }
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

    var runTools = resolveTools(state.sessionId());

    SpanBuilder modelSpan = null;
    SpanBuilder toolSpan = null;

    try {
      if (traceBuilder != null) {
        modelSpan = traceBuilder.span("model.chat", SpanKind.MODEL_CALL);
        modelSpan.attribute("model", config.model().id());
      }

      var response = callModel(state.messages(), outputSchema, runTools);

      if (modelSpan != null) {
        if (response.usage() != null) {
          modelSpan.attribute("inputTokens", String.valueOf(response.usage().inputTokens()));
          modelSpan.attribute("outputTokens", String.valueOf(response.usage().outputTokens()));
        }
        modelSpan.end();
        modelSpan = null;
      }

      var newMessages = new ArrayList<>(state.messages());
      newMessages.add(response.toMessage());

      if (config.memory() != null && state.sessionId() != null) {
        config.memory().addMessage(state.sessionId(), response.toMessage());
      }

      if (!response.hasToolCalls()) {
        return Result.success(
            AgentState.newBuilder()
                .withMessages(newMessages)
                .withLastResponse(response)
                .withIterations(state.iterations() + 1)
                .withComplete(true)
                .withSessionId(state.sessionId())
                .build());
      }

      for (var toolCall : response.toolCalls()) {
        var tool = runTools.get(toolCall.name());
        ToolResult toolResult;

        toolSpan = null;
        if (traceBuilder != null) {
          toolSpan = traceBuilder.span("tool." + toolCall.name(), SpanKind.TOOL_EXECUTION);
          toolSpan.attribute("toolName", toolCall.name());
          toolSpan.attribute("toolCallId", toolCall.id());
        }

        if (tool == null) {
          toolResult = ToolResult.failure("Unknown tool: " + toolCall.name());
          if (toolSpan != null) {
            toolSpan.fail("Unknown tool: " + toolCall.name());
            toolSpan = null;
          }
        } else {
          toolResult = config.faultTolerance().execute(() -> tool.execute(toolCall.arguments()));
          if (toolSpan != null) {
            if (toolResult.success()) {
              toolSpan.end();
            } else {
              toolSpan.fail(toolResult.output());
            }
            toolSpan = null;
          }
        }

        var toolMessage = Message.tool(toolCall.id(), toolCall.name(), toolResult.output());
        newMessages.add(toolMessage);

        if (config.memory() != null && state.sessionId() != null) {
          config.memory().addMessage(state.sessionId(), toolMessage);
        }
      }

      return Result.success(
          AgentState.newBuilder()
              .withMessages(newMessages)
              .withLastResponse(response)
              .withIterations(state.iterations() + 1)
              .withComplete(false)
              .withSessionId(state.sessionId())
              .build());

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      if (toolSpan != null) {
        toolSpan.fail(e.getMessage());
      }
      return Result.failure("Agent step failed: " + e.getMessage(), e);
    }
  }

  public AgentState initialState(String userMessage, Map<String, String> promptVars) {
    return initialState(userMessage, promptVars, null);
  }

  AgentState initialState(String userMessage, Map<String, String> promptVars, UUID sessionId) {
    var messages = new ArrayList<Message>();
    var systemPrompt = buildSystemPrompt(promptVars);
    messages.add(Message.system(systemPrompt));

    if (config.memory() != null && sessionId != null) {
      messages.addAll(config.memory().history(sessionId));
    }

    var userMsg = Message.user(userMessage);
    messages.add(userMsg);

    if (config.memory() != null && sessionId != null) {
      config.memory().addMessage(sessionId, userMsg);
    }

    return AgentState.newBuilder().withMessages(messages).withSessionId(sessionId).build();
  }

  private Result<AgentState> runLoop(SessionContext session, OutputSchema<?> outputSchema) {
    if (session == null) {
      return Result.failure("session must not be null");
    }
    var userMessage = session.userInput();
    if (userMessage == null || userMessage.isBlank()) {
      return Result.failure("userInput must not be null or blank");
    }
    var state = initialState(userMessage, session.promptVars(), session.sessionId());

    if (config.memory() != null && session.userId() != null && state.sessionId() != null) {
      config.memory().registerSession(session.userId(), state.sessionId());
    }

    var traceBuilder =
        config.tracingEnabled() ? TraceBuilder.start(config.name(), config.traceListeners()) : null;

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
      traceBuilder.end();
    }
    return Result.success(state);
  }

  private Map<String, Tool> resolveTools(UUID sessionId) {
    if (!config.includeMemoryTools() || config.memory() == null || sessionId == null) {
      return toolMap;
    }
    if (sessionId.equals(cachedSessionId)) {
      return cachedTools;
    }
    var merged = new HashMap<>(toolMap);
    for (var tool : MemoryTools.boundTo(config.memory(), sessionId)) {
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
