/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.memory.MemoryRefreshPolicy;
import ai.singlr.core.memory.MemoryTools;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.DurabilityCoordinator;
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.UnsafeResumeException;
import ai.singlr.core.runtime.UnsafeResumePolicy;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanContainer;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import ai.singlr.core.trace.TraceBuilder;
import ai.singlr.core.trace.TraceDetail;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

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

  private static final Logger LOG = Logger.getLogger(Agent.class.getName());

  /**
   * Propagates the calling tool's span down into a sub-agent run so the sub-agent's spans become
   * children of the parent tool span instead of starting a brand-new trace. Bound by {@link
   * #executeSingleTool} around every {@code tool.execute} call; {@link #runLoop} reads it to decide
   * whether to start a fresh {@link TraceBuilder} or append to the caller's subtree.
   *
   * <p>This is how {@code Team} multi-agent runs produce a single unified trace covering the
   * leader's model calls, each worker's delegation span, and every model/tool call inside each
   * worker — all reachable from one {@link ai.singlr.core.trace.Trace} via {@link
   * ai.singlr.core.trace.Span#children()}.
   *
   * <p>Uses JEP 506 {@link ScopedValue} (final in Java 25) rather than a {@link ThreadLocal} so the
   * binding is lexically scoped, unset automatically when the callable returns, and safely
   * inherited across virtual-thread boundaries inside the sequential tool path. The parallel tool
   * path rebinds per-task — each submitted virtual thread binds its own tool span independently.
   */
  public static final ScopedValue<SpanBuilder> PARENT_SPAN = ScopedValue.newInstance();

  private final AgentConfig config;
  private final Map<String, Tool> toolMap;
  private final ContextCompactor compactor;
  private final FaultTolerance faultToleranceNoRetry;
  private final DurabilityCoordinator durabilityCoordinator;

  public Agent(AgentConfig config) {
    this.config = config;
    this.toolMap = new HashMap<>();
    for (var tool : config.tools()) {
      toolMap.put(tool.name(), tool);
    }
    this.compactor = config.contextCompactor();
    this.faultToleranceNoRetry = config.faultTolerance().withoutRetry();
    this.durabilityCoordinator =
        config.durabilityEnabled()
            ? new DurabilityCoordinator(config.durability(), config.name())
            : null;
    // Note: AgentConfig listeners receive AGENT-LOOP events only (BeforeApiCall, AfterTurn,
    // BeforeCompaction, SessionEnd). Memory write events (MemoryWrite) are NOT auto-routed here
    // — callers wanting them must register on the Memory directly via Memory.addListener.
    //
    // The prior auto-subscription cleanly subscribed listeners at construction time but never
    // unsubscribed, so a long-lived Memory shared across short-lived Agents would slowly leak
    // observers and a listener registered on one Agent would observe writes triggered by sibling
    // Agents. Explicit registration on either side keeps the lifetime asymmetry visible.
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
    return toResponse(runLoop(session, null, null));
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
    return toTypedResponse(runLoop(session, outputSchema, null));
  }

  /**
   * Run the agent to completion under a durable {@code runId}. Requires {@link
   * AgentConfig#durabilityEnabled()}; throws otherwise. The first iteration writes a {@link
   * AgentRunStatus#RUNNING} checkpoint via the configured {@link
   * ai.singlr.core.runtime.Durability#runStore()}; subsequent iterations update {@code
   * lastCheckpointAt} and {@code iteration}; the run reaches {@link AgentRunStatus#COMPLETED} or
   * {@link AgentRunStatus#FAILED} when the loop exits.
   *
   * <p>Tool invocations are journaled via {@link
   * ai.singlr.core.runtime.Durability#toolCallJournal()} so {@code resume(...)} can detect
   * in-flight calls after a JVM crash.
   *
   * @param session the session context
   * @param runId stable durable identifier for this run
   * @return the agent's final response
   */
  public Result<Response> run(SessionContext session, UUID runId) {
    requireDurability("run(SessionContext, runId)");
    return toResponse(runLoop(session, null, runId));
  }

  /**
   * Run the agent under a durable {@code runId} with structured output.
   *
   * @param <T> the type of the structured output
   * @param session the session context
   * @param runId stable durable identifier for this run
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  public <T> Result<Response<T>> run(
      SessionContext session, UUID runId, OutputSchema<T> outputSchema) {
    requireDurability("run(SessionContext, runId, outputSchema)");
    return toTypedResponse(runLoop(session, outputSchema, runId));
  }

  private void requireDurability(String method) {
    if (!config.durabilityEnabled()) {
      throw new IllegalStateException(
          method + " requires AgentConfig.withDurability(...) to be configured");
    }
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
    return step(state, null, null, EventEmitter.NOOP);
  }

  /**
   * Execute a single step with structured output.
   *
   * @param <T> the type of the structured output
   * @param state the current state of the agent
   * @param outputSchema the schema for structured output
   */
  public <T> Result<AgentState> step(AgentState state, OutputSchema<T> outputSchema) {
    return step(state, outputSchema, null, EventEmitter.NOOP);
  }

  Result<AgentState> step(
      AgentState state,
      OutputSchema<?> outputSchema,
      SpanContainer container,
      EventEmitter emitter) {
    if (state.isComplete()) {
      return Result.success(state);
    }

    if (state.iterations() >= config.maxIterations()) {
      return Result.success(
          AgentState.newBuilder(state)
              .withError("Max iterations (%d) reached".formatted(config.maxIterations()))
              .build());
    }

    durableCheckpoint(state);

    var runTools = resolveTools(state.userId(), state.sessionId());
    var messages =
        refreshSystemMessage(
            compactor.compactIfNeeded(
                state.messages(),
                emitter.runId(),
                state.userId(),
                state.sessionId(),
                config.eventSinks()),
            state);

    fireBeforeApiCall(emitter, state, messages);
    emitter.emitIterationStarted(state.iterations(), config.maxIterations());

    SpanBuilder modelSpan = null;

    try {
      if (container != null) {
        modelSpan = container.span("model.chat", SpanKind.MODEL_CALL);
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
        if (response.hasCitations()) {
          modelSpan.attribute(
              "groundingCitationCount", String.valueOf(response.citations().size()));
          modelSpan.attribute("groundingSources", extractDomains(response.citations()));
        }
        if (config.traceDetail() == TraceDetail.VERBOSE && response.hasThinking()) {
          modelSpan.attribute("thinking", response.thinking());
        }
        modelSpan.end();
        modelSpan = null;
      }

      // Surface terminal-aggregation events for the response we just got back. For sync chat()
      // these are the only assistant-content events emitted; the streaming path emits per-chunk
      // deltas in streamLoop in addition to this terminal complete-event.
      if (response.hasThinking()) {
        emitter.emitAssistantThinkingComplete(response.thinking(), null);
      }
      if (!Strings.isBlank(response.content())) {
        emitter.emitAssistantText(response.content());
      }

      var newMessages = new ArrayList<>(messages);
      newMessages.add(response.toMessage());

      if (config.memory() != null && state.sessionId() != null) {
        config.memory().addMessage(state.userId(), state.sessionId(), response.toMessage());
      }

      if (!response.hasToolCalls()) {
        fireAfterTurn(emitter, state, messages, response.toMessage(), List.of());
        emitter.emitIterationCompleted(state.iterations());
        return Result.success(applyCompletionGuardrails(state, response, newMessages));
      }

      var toolMessages =
          executeToolCalls(response.toolCalls(), runTools, container, state, emitter);
      newMessages.addAll(toolMessages);
      if (config.memory() != null && state.sessionId() != null) {
        for (var msg : toolMessages) {
          config.memory().addMessage(state.userId(), state.sessionId(), msg);
        }
      }

      fireAfterTurn(emitter, state, messages, response.toMessage(), toolMessages);
      emitter.emitIterationCompleted(state.iterations());

      return Result.success(
          AgentState.newBuilder()
              .withMessages(newMessages)
              .withLastResponse(response)
              .withIterations(state.iterations() + 1)
              .withComplete(false)
              .withUserId(state.userId())
              .withSessionId(state.sessionId())
              .withRunId(state.runId())
              .build());

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      var parseFailure = unwrapStructuredOutputParse(e);
      if (parseFailure != null && outputSchema != null && config.structuredOutputRetry()) {
        return Result.success(injectParseCorrectionAndContinue(state, messages, parseFailure));
      }
      return Result.failure("Agent step failed: " + e.getMessage(), e);
    }
  }

  /**
   * Find the latest USER-role message in {@code messages}, walking backward. Used to populate
   * {@link HeliosEvent.AfterTurn#userMessage}. Returns {@code null} when no USER message exists —
   * defensive against synthetic message lists that begin with a system prompt and no user turn.
   */
  private static Message latestUserMessage(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      var msg = messages.get(i);
      if (msg.role() == Role.USER) {
        return msg;
      }
    }
    return null;
  }

  private static void fireBeforeApiCall(
      EventEmitter emitter, AgentState state, List<Message> messages) {
    emitter.emitBeforeApiCall(state.userId(), state.sessionId(), messages, state.iterations());
  }

  private static void fireAfterTurn(
      EventEmitter emitter,
      AgentState state,
      List<Message> messagesBeforeResponse,
      Message assistantMessage,
      List<Message> toolMessages) {
    emitter.emitAfterTurn(
        state.userId(),
        state.sessionId(),
        latestUserMessage(messagesBeforeResponse),
        assistantMessage,
        toolMessages,
        state.iterations());
  }

  private static void fireSessionEnd(
      EventEmitter emitter, AgentState state, HeliosEvent.SessionEnd.Termination termination) {
    emitter.emitSessionEnd(state.userId(), state.sessionId(), state.messages(), termination);
  }

  /**
   * Walk the {@link Throwable#getCause()} chain looking for a {@link
   * StructuredOutputParseException}. Providers throw it directly from {@code
   * parseStructuredContent} but {@link ai.singlr.core.fault.FaultTolerance} (and any retry envelope
   * above it) may wrap. The loop is bounded by visited identity to defend against pathological
   * cycles. Returns {@code null} when no parse-failure marker is present in the chain.
   */
  private static StructuredOutputParseException unwrapStructuredOutputParse(Throwable t) {
    var seen = new java.util.IdentityHashMap<Throwable, Boolean>();
    Throwable current = t;
    while (current != null && !seen.containsKey(current)) {
      if (current instanceof StructuredOutputParseException sope) {
        return sope;
      }
      seen.put(current, Boolean.TRUE);
      current = current.getCause();
    }
    return null;
  }

  /**
   * Convert a structured-output schema mismatch into a corrective USER turn and continue the loop.
   * The injected message carries {@link StructuredOutputParseException#correctionMessage()} so the
   * model sees the field-level diff inline; the raw bad output is intentionally not re-attached
   * (the model already produced it in this turn's API context). Marker {@code helios.injected =
   * "structuredOutputParse"} mirrors the existing minIterations/requiredTools guidance pattern so
   * tracing can distinguish injection sources.
   */
  private AgentState injectParseCorrectionAndContinue(
      AgentState state, List<Message> messages, StructuredOutputParseException parseFailure) {
    var injected =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent(parseFailure.correctionMessage())
            .withMetadata(Map.of("helios.injected", "structuredOutputParse"))
            .build();
    var newMessages = new ArrayList<>(messages);
    newMessages.add(injected);
    if (config.memory() != null && state.sessionId() != null) {
      config.memory().addMessage(state.userId(), state.sessionId(), injected);
    }
    return AgentState.newBuilder()
        .withMessages(newMessages)
        .withLastResponse(state.lastResponse())
        .withIterations(state.iterations() + 1)
        .withComplete(false)
        .withUserId(state.userId())
        .withSessionId(state.sessionId())
        .withRunId(state.runId())
        .build();
  }

  public AgentState initialState(String userMessage, Map<String, String> promptVars) {
    return initialState(userMessage, promptVars, null, null, List.of(), null);
  }

  AgentState initialState(
      String userMessage,
      Map<String, String> promptVars,
      String userId,
      UUID sessionId,
      List<InlineFile> inlineFiles,
      UUID runId) {
    var messages = new ArrayList<Message>();
    var systemPrompt = buildSystemPrompt(promptVars);
    messages.add(Message.system(systemPrompt));

    if (config.memory() != null && sessionId != null) {
      messages.addAll(config.memory().history(userId, sessionId));
    }

    var userMsg = Message.user(userMessage, inlineFiles);
    messages.add(userMsg);

    if (config.memory() != null && sessionId != null) {
      // Persist the user message verbatim — including any inline files attached. Persisting a
      // freshly-cloned Message.user(userMessage) would drop the multimodal payload, so durable
      // resume would replay a text-only conversation and the model would lose access to images,
      // documents, etc. that the user originally supplied.
      config.memory().addMessage(userId, sessionId, userMsg);
    }

    return AgentState.newBuilder()
        .withMessages(messages)
        .withUserId(userId)
        .withSessionId(sessionId)
        .withPromptVars(promptVars)
        .withRunId(runId)
        .build();
  }

  /**
   * Refresh the system message at the head of the message list when memory is configured. The
   * system prompt is rebuilt from the captured {@code promptVars} so any {@code memory_update} the
   * model performed in a prior iteration is reflected in {@code ${core_memory}} on the next model
   * call. Returns the original list when no memory is configured (and so nothing can change), when
   * {@code messages} is empty / the head is not a system message, or when {@link
   * MemoryRefreshPolicy#PER_SESSION} is in effect (the system prompt was built once at session
   * start and is frozen for cache stability).
   */
  private List<Message> refreshSystemMessage(List<Message> messages, AgentState state) {
    if (config.memory() == null || messages.isEmpty()) {
      return messages;
    }
    if (config.memoryRefreshPolicy() == MemoryRefreshPolicy.PER_SESSION) {
      return messages;
    }
    var head = messages.get(0);
    if (head.role() != Role.SYSTEM) {
      return messages;
    }
    var refreshed = buildSystemPrompt(state.promptVars());
    if (refreshed.equals(head.content())) {
      return messages;
    }
    var updated = new ArrayList<Message>(messages.size());
    updated.add(Message.system(refreshed));
    for (int i = 1; i < messages.size(); i++) {
      updated.add(messages.get(i));
    }
    return updated;
  }

  private Result<AgentState> runLoop(
      SessionContext session, OutputSchema<?> outputSchema, UUID runId) {
    if (session == null) {
      return Result.failure("session must not be null");
    }
    var userMessage = session.userInput();
    if (userMessage == null || userMessage.isBlank()) {
      return Result.failure("userInput must not be null or blank");
    }
    var effectiveRunId = config.durabilityEnabled() ? (runId != null ? runId : Ids.newId()) : null;
    var state =
        initialState(
            userMessage,
            session.promptVars(),
            session.userId(),
            session.sessionId(),
            session.inlineFiles(),
            effectiveRunId);

    if (config.memory() != null && session.userId() != null && state.sessionId() != null) {
      config.memory().registerSession(session.userId(), state.sessionId());
    }
    durableInitialize(state);

    var parentSpan = PARENT_SPAN.isBound() ? PARENT_SPAN.get() : null;
    var nested = parentSpan != null;
    var emitter = newEventEmitter(nested);
    TraceBuilder traceBuilder = null;
    SpanContainer container = null;

    if (nested) {
      container = parentSpan;
    } else if (config.tracingEnabled()) {
      traceBuilder = TraceBuilder.start(config.name(), emitter.runId(), config.eventSinks());
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
      container = traceBuilder;
    }

    emitter.emitRunStarted(userMessage, session);

    while (!state.isComplete()) {
      var result = step(state, outputSchema, container, emitter);
      if (result.isFailure()) {
        var failure = (Result.Failure<AgentState>) result;
        var failedTrace = traceBuilder != null ? traceBuilder.fail(failure.error()) : null;
        emitter.emitRunFailed(failure.error(), failedTrace);
        durableFail(state, failure.error());
        fireSessionEnd(emitter, state, HeliosEvent.SessionEnd.Termination.FAILED);
        return Result.failure(failure.error(), failure.cause());
      }
      state = ((Result.Success<AgentState>) result).value();
    }

    if (state.isError()) {
      var failedTrace = traceBuilder != null ? traceBuilder.fail(state.error()) : null;
      emitter.emitRunFailed(state.error(), failedTrace);
      durableFail(state, state.error());
      fireSessionEnd(emitter, state, HeliosEvent.SessionEnd.Termination.MAX_ITERATIONS);
      return Result.failure(state.error());
    }

    Trace finalTrace = null;
    if (traceBuilder != null) {
      var finalResponse = state.finalResponse();
      if (finalResponse != null && finalResponse.content() != null) {
        traceBuilder.outputText(finalResponse.content());
      }
      finalTrace = finalizeTrace(traceBuilder);
    }
    emitter.emitRunCompleted(finalTrace);
    if (nested) {
      try {
        recordNestedSpanCount(parentSpan);
      } catch (RuntimeException parentClosed) {
        LOG.log(
            Level.FINE, "Skipping subAgent.spanCount: parent span already closed", parentClosed);
      }
    }
    durableComplete(state);
    fireSessionEnd(emitter, state, HeliosEvent.SessionEnd.Termination.COMPLETED);
    return Result.success(state);
  }

  /**
   * Per-run helper that fans out {@link HeliosEvent}s to every configured {@link EventSink}. Built
   * fresh per {@code runLoop} invocation so each run has its own stable {@code runId}.
   *
   * <p>Returns a no-op emitter when this run is nested inside a Team leader's tool span — the
   * leader's emitter owns the unified stream for the whole team in that case.
   */
  private EventEmitter newEventEmitter(boolean nested) {
    if (nested) {
      return EventEmitter.NOOP;
    }
    var sinks = config.eventSinks();
    if (sinks == null || sinks.isEmpty()) {
      return EventEmitter.NOOP;
    }
    return new EventEmitter(sinks, Ids.newId(), "agent");
  }

  /**
   * Close the trace, falling back from {@code end()} to {@code fail()} if a child span was leaked.
   * Returns the built {@link Trace} so callers can attach it to {@link HeliosEvent.RunCompleted};
   * returns {@code null} only when both {@code end()} and the force-close {@code fail()} threw
   * (already-ended trace from another path).
   *
   * <p>{@link TraceBuilder#end()} throws {@link IllegalStateException} when any child span is still
   * open — by design, to surface span-tracking bugs during development. In production we must not
   * let that diagnostic abort {@code agent.run()} / {@code team.run()}: the user's request has
   * completed successfully (or with a real error) and the leaked span is a tracing implementation
   * detail. We log a warning, force the trace closed via {@code fail()} so listeners still see it,
   * and return normally.
   */
  private static Trace finalizeTrace(TraceBuilder traceBuilder) {
    try {
      return traceBuilder.end();
    } catch (IllegalStateException leakedSpan) {
      LOG.log(
          Level.WARNING,
          "Trace finalization detected leaked spans; force-closing via fail()",
          leakedSpan);
      try {
        return traceBuilder.fail(
            "trace finalization detected open spans: " + leakedSpan.getMessage());
      } catch (RuntimeException alreadyEnded) {
        LOG.log(Level.FINE, "Trace already ended during force-close", alreadyEnded);
        return null;
      }
    }
  }

  /**
   * When this run executed nested under a parent tool span, record how many spans the run
   * contributed to that parent. Surfaces whether the nesting machinery engaged end-to-end — a
   * {@code subAgent.spanCount} of zero with {@code subAgent.nested=true} means the sub-agent ran
   * but produced no internal spans (e.g., its model returned content without tool calls AND the
   * framework somehow skipped span creation). Any positive count confirms nesting worked.
   */
  private static void recordNestedSpanCount(SpanBuilder parentSpan) {
    if (parentSpan == null) {
      return;
    }
    parentSpan.attribute("subAgent.spanCount", String.valueOf(parentSpan.openChildCount()));
  }

  private void streamLoop(SessionContext session, LinkedBlockingQueue<StreamEvent> queue) {
    TraceBuilder traceBuilder = null;
    EventEmitter emitter = EventEmitter.NOOP;
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

      var effectiveRunId = config.durabilityEnabled() ? Ids.newId() : null;
      var state =
          initialState(
              userMessage,
              session.promptVars(),
              session.userId(),
              session.sessionId(),
              session.inlineFiles(),
              effectiveRunId);

      if (config.memory() != null && session.userId() != null && state.sessionId() != null) {
        config.memory().registerSession(session.userId(), state.sessionId());
      }
      durableInitialize(state);

      var parentSpan = PARENT_SPAN.isBound() ? PARENT_SPAN.get() : null;
      var nested = parentSpan != null;
      emitter = newEventEmitter(nested);
      SpanContainer container = null;

      if (nested) {
        container = parentSpan;
      } else if (config.tracingEnabled()) {
        traceBuilder = TraceBuilder.start(config.name(), emitter.runId(), config.eventSinks());
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
        container = traceBuilder;
      }

      emitter.emitRunStarted(userMessage, session);

      while (!state.isComplete()) {
        state = streamStep(state, queue, container, emitter);
      }

      if (state.isError()) {
        var failedTrace = traceBuilder != null ? traceBuilder.fail(state.error()) : null;
        emitter.emitRunFailed(state.error(), failedTrace);
        durableFail(state, state.error());
        fireSessionEnd(emitter, state, HeliosEvent.SessionEnd.Termination.MAX_ITERATIONS);
        queue.put(new StreamEvent.Error(state.error()));
        return;
      }

      Trace finalTrace = null;
      if (traceBuilder != null) {
        var finalResponse = state.finalResponse();
        if (finalResponse != null && finalResponse.content() != null) {
          traceBuilder.outputText(finalResponse.content());
        }
        finalTrace = finalizeTrace(traceBuilder);
      }
      emitter.emitRunCompleted(finalTrace);
      if (nested) {
        try {
          recordNestedSpanCount(parentSpan);
        } catch (RuntimeException parentClosed) {
          LOG.log(
              Level.FINE, "Skipping subAgent.spanCount: parent span already closed", parentClosed);
        }
      }
      durableComplete(state);
      fireSessionEnd(emitter, state, HeliosEvent.SessionEnd.Termination.COMPLETED);
      queue.put(new StreamEvent.Done(state.finalResponse()));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      var failedTrace = traceBuilder != null ? traceBuilder.fail(e.getMessage()) : null;
      emitter.emitRunFailed(e.getMessage(), failedTrace);
      try {
        queue.put(new StreamEvent.Error(e.getMessage(), e));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private AgentState streamStep(
      AgentState state,
      LinkedBlockingQueue<StreamEvent> queue,
      SpanContainer container,
      EventEmitter emitter)
      throws Exception {

    if (state.iterations() >= config.maxIterations()) {
      return AgentState.newBuilder(state)
          .withError("Max iterations (%d) reached".formatted(config.maxIterations()))
          .build();
    }

    durableCheckpoint(state);

    var runTools = resolveTools(state.userId(), state.sessionId());
    var messages =
        refreshSystemMessage(
            compactor.compactIfNeeded(
                state.messages(),
                emitter.runId(),
                state.userId(),
                state.sessionId(),
                config.eventSinks()),
            state);

    fireBeforeApiCall(emitter, state, messages);
    emitter.emitIterationStarted(state.iterations(), config.maxIterations());

    SpanBuilder modelSpan = null;

    try {
      if (container != null) {
        modelSpan = container.span("model.chat", SpanKind.MODEL_CALL);
        modelSpan.attribute("model", config.model().id());
      }

      var tools = List.copyOf(runTools.values());
      Response<?> response;
      try (var stream =
          config.faultTolerance().execute(() -> config.model().chatStream(messages, tools))) {
        response = drainStreamToQueue(stream, queue, emitter);
      }

      if (modelSpan != null) {
        if (response.usage() != null) {
          modelSpan.attribute("inputTokens", String.valueOf(response.usage().inputTokens()));
          modelSpan.attribute("outputTokens", String.valueOf(response.usage().outputTokens()));
        }
        if (response.finishReason() != null) {
          modelSpan.attribute("finishReason", response.finishReason().name());
        }
        if (response.hasCitations()) {
          modelSpan.attribute(
              "groundingCitationCount", String.valueOf(response.citations().size()));
          modelSpan.attribute("groundingSources", extractDomains(response.citations()));
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

      // Surface terminal assistant content as unified events. Streaming consumers also saw the
      // per-delta events via drainStreamToQueue; this terminal aggregation closes the message.
      if (response.hasThinking()) {
        emitter.emitAssistantThinkingComplete(response.thinking(), null);
      }
      if (!Strings.isBlank(response.content())) {
        emitter.emitAssistantText(response.content());
      }

      if (!response.hasToolCalls()) {
        fireAfterTurn(emitter, state, messages, response.toMessage(), List.of());
        emitter.emitIterationCompleted(state.iterations());
        return applyCompletionGuardrails(state, response, newMessages);
      }

      var toolMessages =
          executeToolCalls(response.toolCalls(), runTools, container, state, emitter);
      newMessages.addAll(toolMessages);
      if (config.memory() != null && state.sessionId() != null) {
        for (var msg : toolMessages) {
          config.memory().addMessage(state.userId(), state.sessionId(), msg);
        }
      }

      fireAfterTurn(emitter, state, messages, response.toMessage(), toolMessages);
      emitter.emitIterationCompleted(state.iterations());

      return AgentState.newBuilder()
          .withMessages(newMessages)
          .withLastResponse(response)
          .withIterations(state.iterations() + 1)
          .withComplete(false)
          .withUserId(state.userId())
          .withSessionId(state.sessionId())
          .withRunId(state.runId())
          .build();

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      throw e;
    }
  }

  private Response<?> drainStreamToQueue(
      CloseableIterator<StreamEvent> stream,
      LinkedBlockingQueue<StreamEvent> queue,
      EventEmitter emitter)
      throws InterruptedException {
    while (stream.hasNext()) {
      var event = stream.next();
      switch (event) {
        case StreamEvent.TextDelta td -> {
          queue.put(td);
          emitter.emitAssistantTextDelta(td.text());
        }
        case StreamEvent.ThinkingDelta tkd -> {
          queue.put(tkd);
          emitter.emitAssistantThinkingDelta(tkd.text());
        }
        case StreamEvent.ThinkingComplete tkc -> {
          queue.put(tkc);
          emitter.emitAssistantThinkingComplete(tkc.fullThinking(), tkc.signature());
        }
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
   * <p><b>Tracing.</b> When invoked as a tool, the caller opens a span named {@code "tool." + name}
   * of kind {@link SpanKind#TOOL_EXECUTION}. The sub-agent detects the propagated parent span via
   * {@link #PARENT_SPAN} and appends its model/tool spans as <em>children</em> of that delegation
   * span, so a single top-level {@link ai.singlr.core.trace.Trace} contains the full nested
   * subtree. The sub-agent does <em>not</em> fire its own {@code EventSink}s in nested mode — all
   * observability flows through the caller's emitter. This naming and nesting behavior is a stable
   * public contract.
   *
   * @param name the tool name; also the prefix of the delegation span ({@code tool.<name>})
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
              if (PARENT_SPAN.isBound()) {
                PARENT_SPAN.get().attribute("subAgent.nested", "true");
              }
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
      List<ToolCall> toolCalls,
      Map<String, Tool> runTools,
      SpanContainer container,
      AgentState state,
      EventEmitter emitter)
      throws Exception {
    if (config.parallelToolExecution() && toolCalls.size() > 1) {
      return executeToolCallsParallel(toolCalls, runTools, container, state, emitter);
    }
    return executeToolCallsSequential(toolCalls, runTools, container, state, emitter);
  }

  private List<Message> executeToolCallsSequential(
      List<ToolCall> toolCalls,
      Map<String, Tool> runTools,
      SpanContainer container,
      AgentState state,
      EventEmitter emitter)
      throws Exception {
    var toolMessages = new ArrayList<Message>(toolCalls.size());
    for (var toolCall : toolCalls) {
      var toolSpan = createToolSpan(toolCall, container);
      emitter.emitToolCallStarted(toolCall.id(), toolCall.name(), toolCall.arguments());
      var startedAt = Instant.now();
      try {
        var toolResult = executeSingleTool(toolCall, runTools, toolSpan, state);
        emitter.emitToolCallCompleted(
            toolCall.id(), toolResult, java.time.Duration.between(startedAt, Instant.now()));
        toolMessages.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));
      } catch (Exception e) {
        emitter.emitToolCallFailed(toolCall.id(), e.getMessage());
        throw e;
      } finally {
        forceCloseSpan(toolSpan);
      }
    }
    return toolMessages;
  }

  private List<Message> executeToolCallsParallel(
      List<ToolCall> toolCalls,
      Map<String, Tool> runTools,
      SpanContainer container,
      AgentState state,
      EventEmitter emitter) {
    var spans = new SpanBuilder[toolCalls.size()];
    if (container != null) {
      for (int i = 0; i < toolCalls.size(); i++) {
        spans[i] = createToolSpan(toolCalls.get(i), container);
      }
    }

    var results = new ToolResult[toolCalls.size()];
    var startTimes = new Instant[toolCalls.size()];
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < toolCalls.size(); i++) {
        final int idx = i;
        final var toolCall = toolCalls.get(i);
        final var span = spans[idx];
        emitter.emitToolCallStarted(toolCall.id(), toolCall.name(), toolCall.arguments());
        startTimes[idx] = Instant.now();
        executor.submit(
            () -> {
              try {
                results[idx] = executeSingleTool(toolCall, runTools, span, state);
              } catch (Exception e) {
                results[idx] = ToolResult.failure("Tool execution failed: " + e.getMessage());
                emitter.emitToolCallFailed(toolCall.id(), e.getMessage());
              }
            });
      }
    } finally {
      // Defense in depth: any pre-opened span that didn't get closed by its task (e.g., the task
      // was cancelled before running, or executeSingleTool's catch path itself threw) gets force-
      // closed here so the parent trace can finalize cleanly.
      for (var span : spans) {
        forceCloseSpan(span);
      }
    }

    var toolMessages = new ArrayList<Message>(toolCalls.size());
    var now = Instant.now();
    for (int i = 0; i < toolCalls.size(); i++) {
      var tc = toolCalls.get(i);
      var result =
          results[i] != null
              ? results[i]
              : ToolResult.failure("Tool execution failed unexpectedly");
      emitter.emitToolCallCompleted(
          tc.id(), result, java.time.Duration.between(startTimes[i], now));
      toolMessages.add(Message.tool(tc.id(), tc.name(), result.output()));
    }
    return toolMessages;
  }

  /**
   * Idempotent span closer used by tool-execution finally blocks. Best-effort: if the span was
   * already closed (success path), {@code fail()} throws {@link IllegalStateException} which we
   * swallow. If still open, this force-fails it so the parent's cleanup pass doesn't trip on it.
   */
  private static void forceCloseSpan(SpanBuilder span) {
    if (span == null) {
      return;
    }
    try {
      span.fail("span force-closed by tool-execution finally");
    } catch (RuntimeException alreadyClosed) {
      // Expected: span was already closed via end() or fail() in the normal path.
    }
  }

  private SpanBuilder createToolSpan(ToolCall toolCall, SpanContainer container) {
    if (container == null) {
      return null;
    }
    var span = container.span("tool." + toolCall.name(), SpanKind.TOOL_EXECUTION);
    span.attribute("toolName", toolCall.name());
    span.attribute("toolCallId", toolCall.id());
    if (config.traceDetail() == TraceDetail.VERBOSE && toolCall.arguments() != null) {
      span.attribute("arguments", toolCall.arguments().toString());
    }
    return span;
  }

  private ToolResult executeSingleTool(
      ToolCall toolCall, Map<String, Tool> runTools, SpanBuilder toolSpan, AgentState state)
      throws Exception {
    var tool = runTools.get(toolCall.name());
    if (tool == null) {
      var result = ToolResult.failure("Unknown tool: " + toolCall.name());
      if (toolSpan != null) {
        toolSpan.fail("Unknown tool: " + toolCall.name());
      }
      return result;
    }
    var journaled = journalStart(toolCall, state);
    try {
      var toolResult = invokeTool(tool, toolCall, toolSpan);
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
      if (journaled) {
        journalTerminal(state.runId(), toolCall.id(), toolResult);
      }
      return toolResult;
    } catch (Exception e) {
      if (toolSpan != null) {
        toolSpan.fail(e.getMessage());
      }
      if (journaled) {
        journalTerminalFailure(state.runId(), toolCall.id(), e.getMessage());
      }
      throw e;
    }
  }

  /**
   * Invoke a tool, binding {@link #PARENT_SPAN} to {@code toolSpan} so sub-agent calls (via {@link
   * #asTool}) nest their spans under the tool span. When there is no span (tracing disabled
   * upstream), no binding is established and the tool runs with whatever {@code PARENT_SPAN} state
   * its outer frame already had.
   *
   * <p>The binding is established <em>inside</em> the {@link java.util.concurrent.Callable} that
   * {@link ai.singlr.core.fault.FaultTolerance} executes, not around the call to {@code
   * faultTolerance().execute(...)}. This matters because a timeout-configured {@code
   * FaultTolerance} submits the Callable to a virtual-thread executor ({@code
   * Executors.newVirtualThreadPerTaskExecutor().submit(...)}), and {@link ScopedValue} bindings are
   * <em>not</em> inherited across arbitrary executor submissions in Java 25 — only {@link
   * java.util.concurrent.StructuredTaskScope} propagates them. Binding inside the Callable
   * re-establishes {@code PARENT_SPAN} on whichever thread FT chooses, so nested tracing keeps
   * working whether FT is {@code PASSTHROUGH} (same thread) or has a configured timeout (hops to a
   * virtual thread).
   */
  private ToolResult invokeTool(Tool tool, ToolCall toolCall, SpanBuilder toolSpan)
      throws Exception {
    var ft = config.isToolIdempotent(tool.name()) ? config.faultTolerance() : faultToleranceNoRetry;
    if (toolSpan == null) {
      return ft.execute(() -> tool.execute(toolCall.arguments()));
    }
    return ft.execute(
        () ->
            ScopedValue.where(PARENT_SPAN, toolSpan)
                .call(() -> tool.execute(toolCall.arguments())));
  }

  /**
   * Build the per-call tool map: the agent's static tools plus, when memory is configured and a
   * session is present, the session-bound memory tools. The prior single-entry cache thrashed on
   * long-lived agents serving many sessions (each new session evicted the cache and rebuilt it
   * anyway). Recomputing every call is dominated by the model call cost so the simpler shape wins.
   */
  private Map<String, Tool> resolveTools(String userId, UUID sessionId) {
    if (!config.includeMemoryTools() || config.memory() == null || sessionId == null) {
      return toolMap;
    }
    var merged = new HashMap<>(toolMap);
    for (var tool : MemoryTools.boundTo(config.memory(), userId, sessionId)) {
      merged.put(tool.name(), tool);
    }
    return Map.copyOf(merged);
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

  private AgentState applyCompletionGuardrails(
      AgentState state, Response<?> response, List<Message> newMessages) {
    var nextIter = state.iterations() + 1;

    if (nextIter < config.minIterations()) {
      var guidance =
          "[system guidance] You have completed %d of %d required research iterations. Continue investigating — call additional tools, search for more data, or cross-reference your findings before producing your final response."
              .formatted(nextIter, config.minIterations());
      return injectAndContinue(state, response, newMessages, nextIter, guidance, "minIterations");
    }

    var called = toolsCalledSoFar(newMessages);
    if (!config.requiredTools().isEmpty()) {
      var missing = new LinkedHashSet<String>(config.requiredTools());
      missing.removeAll(called);
      if (!missing.isEmpty()) {
        var guidance =
            "[system guidance] You have not yet called the following required tools: %s. These tools must be used at least once before you can complete your analysis. Continue your research."
                .formatted(String.join(", ", missing));
        return injectAndContinue(state, response, newMessages, nextIter, guidance, "requiredTools");
      }
    }

    if (config.iterationHook() != null) {
      var ctx =
          new IterationContext(
              nextIter,
              config.maxIterations(),
              config.minIterations(),
              config.requiredTools(),
              called,
              totalToolCallCount(newMessages),
              response,
              newMessages);
      IterationAction action;
      try {
        action = config.iterationHook().afterIteration(ctx);
      } catch (RuntimeException e) {
        throw new RuntimeException("iteration hook threw: " + e.getMessage(), e);
      }
      if (action == null) {
        action = IterationAction.allow();
      }
      return switch (action) {
        case IterationAction.Allow a -> completeState(state, response, newMessages, nextIter);
        case IterationAction.Stop s -> completeState(state, response, newMessages, nextIter);
        case IterationAction.Inject inject ->
            injectAndContinue(
                state, response, newMessages, nextIter, inject.message(), "iterationHook");
      };
    }

    return completeState(state, response, newMessages, nextIter);
  }

  private AgentState injectAndContinue(
      AgentState state,
      Response<?> response,
      List<Message> newMessages,
      int nextIter,
      String guidance,
      String marker) {
    var injected =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent(guidance)
            .withMetadata(Map.of("helios.injected", marker))
            .build();
    newMessages.add(injected);
    if (config.memory() != null && state.sessionId() != null) {
      config.memory().addMessage(state.userId(), state.sessionId(), injected);
    }
    return AgentState.newBuilder()
        .withMessages(newMessages)
        .withLastResponse(response)
        .withIterations(nextIter)
        .withComplete(false)
        .withUserId(state.userId())
        .withSessionId(state.sessionId())
        .withRunId(state.runId())
        .build();
  }

  private static AgentState completeState(
      AgentState state, Response<?> response, List<Message> newMessages, int nextIter) {
    return AgentState.newBuilder()
        .withMessages(newMessages)
        .withLastResponse(response)
        .withIterations(nextIter)
        .withComplete(true)
        .withUserId(state.userId())
        .withSessionId(state.sessionId())
        .withRunId(state.runId())
        .build();
  }

  private static Set<String> toolsCalledSoFar(List<Message> messages) {
    var called = new LinkedHashSet<String>();
    for (var msg : messages) {
      if (msg.role() == Role.ASSISTANT && msg.hasToolCalls()) {
        for (var call : msg.toolCalls()) {
          called.add(call.name());
        }
      }
    }
    return called;
  }

  private static int totalToolCallCount(List<Message> messages) {
    var count = 0;
    for (var msg : messages) {
      if (msg.role() == Role.ASSISTANT && msg.hasToolCalls()) {
        count += msg.toolCalls().size();
      }
    }
    return count;
  }

  private static String extractDomains(List<Citation> citations) {
    var domains = new LinkedHashSet<String>();
    for (var citation : citations) {
      var domain = citationDomain(citation);
      if (domain != null) {
        domains.add(domain);
      }
    }
    return String.join(", ", domains);
  }

  private static String citationDomain(Citation citation) {
    var title = citation.title();
    if (title != null && !title.isBlank() && title.contains(".") && !title.contains(" ")) {
      return stripWww(title);
    }
    var sourceId = citation.sourceId();
    if (sourceId == null || sourceId.isBlank()) {
      return null;
    }
    try {
      var host = URI.create(sourceId).getHost();
      return stripWww(host != null ? host : sourceId);
    } catch (IllegalArgumentException e) {
      return stripWww(sourceId);
    }
  }

  private static String stripWww(String domain) {
    return domain.startsWith("www.") ? domain.substring(4) : domain;
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

  // --- Durability helpers (thin wrappers over DurabilityCoordinator) ---

  private boolean isDurableRun(AgentState state) {
    return durabilityCoordinator != null && state != null && state.runId() != null;
  }

  private void durableInitialize(AgentState state) {
    if (!isDurableRun(state)) {
      return;
    }
    durabilityCoordinator.initialize(
        state.runId(), state.sessionId(), state.userId(), state.iterations());
  }

  private void durableCheckpoint(AgentState state) {
    if (!isDurableRun(state)) {
      return;
    }
    durabilityCoordinator.checkpoint(
        state.runId(), state.sessionId(), state.userId(), state.iterations());
  }

  private void durableComplete(AgentState state) {
    if (!isDurableRun(state)) {
      return;
    }
    durabilityCoordinator.complete(
        state.runId(), state.sessionId(), state.userId(), state.iterations());
  }

  private void durableFail(AgentState state, String error) {
    if (!isDurableRun(state)) {
      return;
    }
    durabilityCoordinator.fail(
        state.runId(), state.sessionId(), state.userId(), state.iterations(), error);
  }

  private boolean journalStart(ai.singlr.core.model.ToolCall toolCall, AgentState state) {
    if (!isDurableRun(state)) {
      return false;
    }
    return durabilityCoordinator.journalStart(
        state.runId(), state.iterations(), toolCall.id(), toolCall.name(), toolCall.arguments());
  }

  private void journalTerminal(UUID runId, String toolCallId, ToolResult toolResult) {
    durabilityCoordinator.journalTerminal(runId, toolCallId, toolResult);
  }

  private void journalTerminalFailure(UUID runId, String toolCallId, String error) {
    durabilityCoordinator.journalTerminalFailure(runId, toolCallId, error);
  }

  // --- Resume API ---

  /**
   * Resume a previously-started durable run by id alone. The session id, user id, and message
   * history are all derived from the run record and {@link AgentConfig#memory()} — callers do not
   * need to reconstruct a {@link SessionContext}.
   *
   * <p>If any in-flight tool is non-idempotent and the policy is {@link
   * UnsafeResumePolicy#FAIL_LOUD}, returns {@code Result.failure} carrying an {@link
   * UnsafeResumeException} and leaves the run {@link AgentRunStatus#SUSPENDED}. Under {@link
   * UnsafeResumePolicy#AUTO_FAIL_AND_CONTINUE}, in-flight entries are journaled as failed and the
   * loop continues.
   */
  public Result<Response> resume(UUID runId) {
    return resume(runId, Map.of());
  }

  /** Resume with extra prompt variables — useful when re-rendering {@code ${var}} placeholders. */
  public Result<Response> resume(UUID runId, Map<String, String> promptVars) {
    requireDurability("resume");
    var prep = prepareResume(runId, promptVars);
    return switch (prep) {
      case ResumePreparation.Failed f -> Result.failure(f.error(), f.cause());
      case ResumePreparation.Ready r -> toResponse(continueLoop(r.state(), null));
    };
  }

  /** Resume a durable run with structured output. */
  public <T> Result<Response<T>> resume(UUID runId, OutputSchema<T> outputSchema) {
    return resume(runId, Map.of(), outputSchema);
  }

  /** Resume a durable run with structured output and extra prompt variables. */
  public <T> Result<Response<T>> resume(
      UUID runId, Map<String, String> promptVars, OutputSchema<T> outputSchema) {
    requireDurability("resume");
    var prep = prepareResume(runId, promptVars);
    return switch (prep) {
      case ResumePreparation.Failed f -> Result.failure(f.error(), f.cause());
      case ResumePreparation.Ready r -> toTypedResponse(continueLoop(r.state(), outputSchema));
    };
  }

  /**
   * If a durable run with the given id exists in the configured {@link
   * ai.singlr.core.runtime.RunStore}: resume it when in-progress, or refuse when already terminal
   * (the runId has been used). Otherwise start a fresh run with that id. The most ergonomic entry
   * point for "kick off this work, and if it crashes, just call me again with the same id."
   */
  public Result<Response> runOrResume(UUID runId, SessionContext session) {
    requireDurability("runOrResume");
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isPresent()) {
      if (isTerminal(existing.get().status())) {
        return Result.failure(
            "runOrResume rejected: run "
                + runId
                + " is already terminal ("
                + existing.get().status()
                + ")");
      }
      return resume(runId, session.promptVars());
    }
    return run(session, runId);
  }

  /** {@link #runOrResume(UUID, SessionContext)} with structured output. */
  public <T> Result<Response<T>> runOrResume(
      UUID runId, SessionContext session, OutputSchema<T> outputSchema) {
    requireDurability("runOrResume");
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isPresent()) {
      if (isTerminal(existing.get().status())) {
        return Result.failure(
            "runOrResume rejected: run "
                + runId
                + " is already terminal ("
                + existing.get().status()
                + ")");
      }
      return resume(runId, session.promptVars(), outputSchema);
    }
    return run(session, runId, outputSchema);
  }

  private static boolean isTerminal(AgentRunStatus status) {
    return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED;
  }

  private sealed interface ResumePreparation {
    record Ready(AgentState state) implements ResumePreparation {}

    record Failed(String error, Exception cause) implements ResumePreparation {}
  }

  private ResumePreparation prepareResume(UUID runId, Map<String, String> promptVars) {
    if (runId == null) {
      return new ResumePreparation.Failed("runId must not be null", null);
    }
    var promptVarsToUse = promptVars == null ? Map.<String, String>of() : promptVars;
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isEmpty()) {
      return new ResumePreparation.Failed("No run found for id: " + runId, null);
    }
    var run = existing.get();
    if (run.agentId() != null && !run.agentId().equals(config.name())) {
      return new ResumePreparation.Failed(
          "Run "
              + runId
              + " belongs to agent '"
              + run.agentId()
              + "'; cannot be resumed by agent '"
              + config.name()
              + "'",
          null);
    }
    if (isTerminal(run.status())) {
      return new ResumePreparation.Failed(
          "Run is already terminal: " + run.status() + " (" + runId + ")", null);
    }

    var inflight = durabilityCoordinator.inflightFor(runId);
    if (!inflight.isEmpty()) {
      var unsafe = new ArrayList<ToolCallRecord>();
      for (var rec : inflight) {
        if (!config.isToolIdempotent(rec.toolName())) {
          unsafe.add(rec);
        }
      }
      var policy = config.durability().unsafeResumePolicy();
      if (!unsafe.isEmpty() && policy == UnsafeResumePolicy.FAIL_LOUD) {
        durabilityCoordinator.markSuspended(run);
        var ex = new UnsafeResumeException(unsafe);
        return new ResumePreparation.Failed(ex.getMessage(), ex);
      }
      for (var rec : unsafe) {
        durabilityCoordinator.markInflightFailed(
            runId, rec.toolCallId(), "resume after crash; outcome unknown");
      }
      for (var rec : inflight) {
        if (config.isToolIdempotent(rec.toolName())) {
          durabilityCoordinator.markInflightFailed(
              runId, rec.toolCallId(), "resume after crash; idempotent retry pending");
        }
      }
    }

    if (config.memory() == null || run.sessionId() == null) {
      return new ResumePreparation.Failed(
          "resume requires Memory and a session-scoped run; cannot reconstruct history without"
              + " durable messages",
          null);
    }
    var messages = new ArrayList<Message>();
    messages.add(Message.system(buildSystemPrompt(promptVarsToUse)));
    messages.addAll(config.memory().history(run.userId(), run.sessionId()));
    var state =
        AgentState.newBuilder()
            .withMessages(messages)
            .withIterations(run.iteration())
            .withUserId(run.userId())
            .withSessionId(run.sessionId())
            .withPromptVars(promptVarsToUse)
            .withRunId(runId)
            .build();
    durabilityCoordinator.checkpoint(run.runId(), run.sessionId(), run.userId(), run.iteration());
    return new ResumePreparation.Ready(state);
  }

  private Result<AgentState> continueLoop(AgentState initial, OutputSchema<?> outputSchema) {
    SpanContainer container = null;
    TraceBuilder traceBuilder = null;
    if (config.tracingEnabled()) {
      traceBuilder = TraceBuilder.start(config.name(), null, config.eventSinks());
      traceBuilder
          .userId(initial.userId())
          .sessionId(initial.sessionId())
          .modelId(config.model().id());
      container = traceBuilder;
    }

    var state = initial;
    while (!state.isComplete()) {
      var result = step(state, outputSchema, container, EventEmitter.NOOP);
      if (result.isFailure()) {
        var failure = (Result.Failure<AgentState>) result;
        if (traceBuilder != null) {
          traceBuilder.fail(failure.error());
        }
        durableFail(state, failure.error());
        return Result.failure(failure.error(), failure.cause());
      }
      state = ((Result.Success<AgentState>) result).value();
    }

    if (state.isError()) {
      if (traceBuilder != null) {
        traceBuilder.fail(state.error());
      }
      durableFail(state, state.error());
      return Result.failure(state.error());
    }

    if (traceBuilder != null) {
      var finalResponse = state.finalResponse();
      if (finalResponse != null && finalResponse.content() != null) {
        traceBuilder.outputText(finalResponse.content());
      }
      finalizeTrace(traceBuilder);
    }
    durableComplete(state);
    return Result.success(state);
  }
}
