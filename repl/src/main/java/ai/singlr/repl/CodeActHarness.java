/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.trace.Trace;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-line entrypoint for a CodeAct-style run: model writes Java code in a sandboxed JShell REPL
 * over multiple turns, then returns its structured answer directly as the final assistant message.
 *
 * <p>Deliberately narrower than {@link RlmHarness}: no sub-LM, no {@code predict()} call, no {@code
 * submit()} dance, no extract-fallback. SRLM Table 1 attributes the bulk of accuracy gains to the
 * REPL-over-externalized-context mechanism; the sub-LM recursion is a smaller, separable lever.
 * CodeAct keeps only the REPL teaching.
 *
 * <pre>{@code
 * record Input(ColumnDescriptor column, CdiscIndex cdiscIndex) {}
 * record Output(String targetVariable, String reasoning, double confidence) {}
 *
 * var harness = CodeActHarness.builder(Input.class, Output.class)
 *     .model(opus47)
 *     .sandboxFactory(JvmSandbox.factory())
 *     .strategy("Inspect the column metadata. Search the CDISC index for candidate target ...")
 *     .build();
 *
 * CodeActResult<Output> result = harness.run(new Input(col, idx));
 * }</pre>
 *
 * <p>Composition: this is an independent assembly over {@link ReplSession} / {@link
 * CodeExecutionTool} — not a subclass of {@link RlmHarness}. The two harnesses share substrate but
 * have different control flow.
 */
public final class CodeActHarness<I, O> {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Default outer iteration cap. */
  public static final int DEFAULT_MAX_ITERATIONS = 30;

  private final Class<I> inputType;
  private final Class<O> outputType;
  private final OutputSchema<I> inputSchema;
  private final OutputSchema<O> outputSchema;
  private final Model model;
  private final String strategy;
  private final List<HostFunction> extraHostFunctions;
  private final SandboxFactory sandboxFactory;
  private final int maxIterations;
  private final int maxOutputCharsToModel;
  private final int maxExecutedCodeChars;
  private final SandboxBindingsListener sandboxBindingsListener;
  private final Semaphore concurrencyLimiter;
  private final List<EventSink> eventSinks;
  private final String systemPromptOverride;

  private CodeActHarness(Builder<I, O> b) {
    this.inputType = b.inputType;
    this.outputType = b.outputType;
    this.inputSchema = OutputSchema.of(b.inputType);
    this.outputSchema = b.outputSchema != null ? b.outputSchema : OutputSchema.of(b.outputType);
    this.model = b.model;
    this.strategy = b.strategy;
    this.extraHostFunctions = List.copyOf(b.extraHostFunctions);
    this.sandboxFactory = b.sandboxFactory;
    this.maxIterations = b.maxIterations;
    this.maxOutputCharsToModel = b.maxOutputCharsToModel;
    this.maxExecutedCodeChars = b.maxExecutedCodeChars;
    this.sandboxBindingsListener = b.sandboxBindingsListener;
    this.concurrencyLimiter =
        b.concurrencyLimiter != null
            ? b.concurrencyLimiter
            : new Semaphore(ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS);
    this.eventSinks = List.copyOf(b.eventSinks);
    this.systemPromptOverride = b.systemPromptOverride;
  }

  public static <I, O> Builder<I, O> builder(Class<I> inputType, Class<O> outputType) {
    if (inputType == null || outputType == null) {
      throw new IllegalArgumentException("inputType and outputType are required");
    }
    return new Builder<>(inputType, outputType);
  }

  /** Run the harness against {@code input} using a synthetic session context. */
  public CodeActResult<O> run(I input) {
    return run(input, null);
  }

  /**
   * Run the harness against {@code input} using {@code session}'s user/session identifiers. When
   * {@code session} is {@code null}, anonymous identifiers are synthesized.
   */
  public CodeActResult<O> run(I input, SessionContext session) {
    if (input == null) {
      return CodeActResult.failed("input must not be null", List.of(), Map.of(), null);
    }
    PreparedInput prepared;
    try {
      prepared = serializeInput(input);
    } catch (Exception e) {
      return CodeActResult.failed(
          "failed to serialize input: " + e.getMessage(), List.of(), Map.of(), null);
    }

    var hostFunctions = assembleHostFunctions(prepared.inputMap());
    var bindingSnippet = InputBindings.snippet(inputType);
    var replConfig = buildReplConfig(hostFunctions);
    var systemPrompt = chooseSystemPrompt(hostFunctions);
    var traceCapture = new AtomicReference<Trace>();
    var capturingSink = traceCapturingSink(traceCapture);

    try (var replSession = ReplSession.create(replConfig, concurrencyLimiter)) {
      var bindingFailure = runBindingSnippet(replSession, bindingSnippet);
      if (bindingFailure != null) {
        return bindingFailure;
      }

      var memory = InMemoryMemory.withDefaults();
      var sessionCtx = buildSessionContext(session, prepared);

      var agentConfig = buildAgentConfig(replSession, memory, systemPrompt, capturingSink);
      var agent = new Agent(agentConfig);
      var runResult = agent.run(sessionCtx, outputSchema);

      return interpret(runResult, replSession, traceCapture.get());
    } catch (Exception e) {
      return CodeActResult.failed(
          "CodeAct run failed: " + e.getMessage(), List.of(), Map.of(), traceCapture.get());
    }
  }

  /** Serialized form of a typed input — JSON for the user message, Map for {@code __getInput}. */
  private record PreparedInput(String inputJson, Map<String, Object> inputMap) {}

  private PreparedInput serializeInput(I input) throws Exception {
    var json = MAPPER.writeValueAsString(input);
    Map<String, Object> map = MAPPER.convertValue(input, new TypeReference<>() {});
    return new PreparedInput(json, map);
  }

  private List<HostFunction> assembleHostFunctions(Map<String, Object> inputMap) {
    var all = new ArrayList<HostFunction>(extraHostFunctions);
    if (InputBindings.snippet(inputType) != null) {
      all.add(
          new HostFunction(
              "__getInput",
              "Harness-internal: returns the input record fields as Map<String,Object>",
              params -> inputMap));
    }
    return all;
  }

  private ReplConfig buildReplConfig(List<HostFunction> hostFunctions) {
    return ReplConfig.newBuilder()
        .withSandboxFactory(sandboxFactory)
        .withHostFunctions(hostFunctions)
        .withMaxOutputCharsToModel(maxOutputCharsToModel)
        .withMaxExecutedCodeChars(maxExecutedCodeChars)
        .withSandboxBindingsListener(sandboxBindingsListener)
        .withAutoRegisterSubmit(false)
        .build();
  }

  private String chooseSystemPrompt(List<HostFunction> hostFunctions) {
    if (systemPromptOverride != null) {
      return systemPromptOverride;
    }
    var boundNames = InputBindings.boundFieldNames(inputType);
    return CodeActSystemPrompt.build(
        strategy, inputSchema, outputSchema, hostFunctions, maxOutputCharsToModel, boundNames);
  }

  private CodeActResult<O> runBindingSnippet(ReplSession session, String bindingSnippet) {
    if (bindingSnippet == null) {
      return null;
    }
    var bindingResult = session.execute(bindingSnippet);
    if (bindingResult.exitCode() != 0 || !bindingResult.stderr().isBlank()) {
      var msg =
          "input binding failed: "
              + (bindingResult.stderr().isBlank()
                  ? "exit code " + bindingResult.exitCode()
                  : bindingResult.stderr());
      return CodeActResult.failed(msg, session.history(), session.calledHostFunctions(), null);
    }
    return null;
  }

  private SessionContext buildSessionContext(SessionContext supplied, PreparedInput prepared) {
    var builder = SessionContext.newBuilder().withUserInput(prepared.inputJson());
    if (supplied != null) {
      builder.withUserId(supplied.userId());
      builder.withSessionId(supplied.sessionId());
      if (supplied.promptVars() != null) {
        builder.withPromptVars(supplied.promptVars());
      }
      if (supplied.metadata() != null) {
        builder.withMetadata(supplied.metadata());
      }
      if (supplied.inlineFiles() != null) {
        builder.withInlineFiles(supplied.inlineFiles());
      }
    } else {
      builder.withUserId("codeact").withSessionId(UUID.randomUUID());
    }
    return builder.build();
  }

  private AgentConfig buildAgentConfig(
      ReplSession replSession, InMemoryMemory memory, String systemPrompt, EventSink captureSink) {
    var allSinks = new ArrayList<EventSink>(eventSinks.size() + 1);
    allSinks.addAll(eventSinks);
    allSinks.add(captureSink);
    return AgentConfig.newBuilder()
        .withName("codeact-harness")
        .withModel(model)
        .withSystemPrompt(systemPrompt)
        .withTool(CodeExecutionTool.create(replSession))
        .withIncludeMemoryTools(false)
        .withMaxIterations(maxIterations)
        .withMemory(memory)
        .withEventSinks(allSinks)
        .build();
  }

  private CodeActResult<O> interpret(
      Result<? extends ai.singlr.core.model.Response<O>> runResult,
      ReplSession session,
      Trace trace) {
    return switch (runResult) {
      case Result.Success<? extends ai.singlr.core.model.Response<O>>(var response) -> {
        var parsed = response.parsed();
        if (parsed == null) {
          var content = response.content();
          var preview =
              content == null || content.length() <= 200 ? content : content.substring(0, 200);
          yield CodeActResult.failed(
              "Agent run produced no parsed output. content=" + preview,
              session.history(),
              session.calledHostFunctions(),
              trace);
        }
        yield CodeActResult.succeeded(
            parsed, session.history(), session.calledHostFunctions(), trace);
      }
      case Result.Failure<? extends ai.singlr.core.model.Response<O>>(String error, var ignored) ->
          CodeActResult.failed(error, session.history(), session.calledHostFunctions(), trace);
    };
  }

  /**
   * Build a tiny {@link EventSink} that captures the run's complete {@link Trace} when {@code
   * RunCompleted} or {@code RunFailed} fires. Listener exceptions are swallowed — observability
   * must not abort the harness.
   */
  private static EventSink traceCapturingSink(AtomicReference<Trace> sink) {
    return event -> {
      switch (event) {
        case HeliosEvent.RunCompleted rc -> sink.set(rc.trace());
        case HeliosEvent.RunFailed rf -> sink.set(rf.trace());
        default -> {
          // Other events are not used for trace capture.
        }
      }
    };
  }

  /** Helper for tests / downstream wiring. */
  public OutputSchema<O> outputSchema() {
    return outputSchema;
  }

  public Class<I> inputType() {
    return inputType;
  }

  public Class<O> outputType() {
    return outputType;
  }

  public static final class Builder<I, O> {
    private final Class<I> inputType;
    private final Class<O> outputType;
    private Model model;
    private String strategy;
    private final List<HostFunction> extraHostFunctions = new ArrayList<>();
    private final List<Skill> skills = new ArrayList<>();
    private SandboxFactory sandboxFactory;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private int maxOutputCharsToModel = ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;
    private int maxExecutedCodeChars = ReplConfig.DEFAULT_MAX_EXECUTED_CODE_CHARS;
    private SandboxBindingsListener sandboxBindingsListener;
    private Semaphore concurrencyLimiter;
    private final List<EventSink> eventSinks = new ArrayList<>();
    private String systemPromptOverride;
    private OutputSchema<O> outputSchema;

    private Builder(Class<I> inputType, Class<O> outputType) {
      this.inputType = inputType;
      this.outputType = outputType;
    }

    /** The model that drives the agent loop. Required. */
    public Builder<I, O> model(Model model) {
      this.model = model;
      return this;
    }

    /** Task-specific instructions injected into the system prompt. Strongly recommended. */
    public Builder<I, O> strategy(String strategy) {
      this.strategy = strategy;
      return this;
    }

    /** Sandbox factory. Required. */
    public Builder<I, O> sandboxFactory(SandboxFactory factory) {
      this.sandboxFactory = factory;
      return this;
    }

    /** Provide a custom {@link OutputSchema}; defaults to {@code OutputSchema.of(outputType)}. */
    public Builder<I, O> outputSchema(OutputSchema<O> schema) {
      if (schema == null) {
        throw new IllegalArgumentException("schema must not be null");
      }
      this.outputSchema = schema;
      return this;
    }

    /** Replace the entire generated system prompt with a custom one. */
    public Builder<I, O> systemPrompt(String fullSystemPrompt) {
      this.systemPromptOverride = fullSystemPrompt;
      return this;
    }

    /** Add a host function reachable inside the sandbox. */
    public Builder<I, O> hostFunction(HostFunction function) {
      this.extraHostFunctions.add(function);
      return this;
    }

    /** Add several host functions in one call. */
    public Builder<I, O> hostFunctions(List<HostFunction> functions) {
      this.extraHostFunctions.addAll(functions);
      return this;
    }

    /** Attach a {@link Skill} bundle. Tools enter the sandbox; instructions append to strategy. */
    public Builder<I, O> skill(Skill skill) {
      this.skills.add(skill);
      return this;
    }

    /** Attach several skills in one call. */
    public Builder<I, O> skills(List<Skill> skills) {
      this.skills.addAll(skills);
      return this;
    }

    /** Outer agent iteration cap. */
    public Builder<I, O> maxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    /** Cap on {@code execute_code} output shown to the model. */
    public Builder<I, O> maxOutputCharsToModel(int max) {
      this.maxOutputCharsToModel = max;
      return this;
    }

    /** Per-call cap on {@code ExecutionResult.executedCode()}. */
    public Builder<I, O> maxExecutedCodeChars(int max) {
      this.maxExecutedCodeChars = max;
      return this;
    }

    /** Listener that observes the sandbox's working memory after each {@code execute_code}. */
    public Builder<I, O> sandboxBindingsListener(SandboxBindingsListener listener) {
      this.sandboxBindingsListener = listener;
      return this;
    }

    /** Override the concurrency semaphore. Defaults to a fresh permit pool of 50. */
    public Builder<I, O> concurrencyLimiter(Semaphore semaphore) {
      this.concurrencyLimiter = semaphore;
      return this;
    }

    /** Add an {@link EventSink} to receive the unified Helios event stream. */
    public Builder<I, O> eventSink(EventSink sink) {
      this.eventSinks.add(sink);
      return this;
    }

    public CodeActHarness<I, O> build() {
      if (model == null) {
        throw new IllegalStateException("model is required");
      }
      if (sandboxFactory == null) {
        throw new IllegalStateException("sandboxFactory is required");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      if (maxOutputCharsToModel < 0) {
        throw new IllegalStateException("maxOutputCharsToModel must be >= 0");
      }
      if (!skills.isEmpty()) {
        var merged = Skill.merge(skills);
        this.extraHostFunctions.addAll(merged.tools());
        if (!Strings.isBlank(merged.instructions())) {
          this.strategy =
              Strings.isBlank(this.strategy)
                  ? merged.instructions()
                  : this.strategy + "\n\n" + merged.instructions();
        }
      }
      return new CodeActHarness<>(this);
    }
  }
}
