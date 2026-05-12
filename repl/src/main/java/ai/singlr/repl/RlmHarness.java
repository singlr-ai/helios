/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.IterationAction;
import ai.singlr.core.agent.IterationHook;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.trace.SpanListener;
import ai.singlr.core.trace.TraceListener;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.PredictFunction;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.BiPredicate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-line entrypoint for an RLM (Recursive Language Model) run. Bundles the substrate Helios
 * already has — {@link ReplSession}, {@link CodeExecutionTool}, {@link PredictFunction}, typed
 * {@code submit}, the canonical {@link RlmSystemPrompt}, the {@link ExtractFallback} recovery path,
 * and sensible defaults — into a single typed harness.
 *
 * <p>Following Trampoline's {@code predict-rlm} shape: pass an input record class, an output record
 * class, a strategy docstring, the root and sub models, and run.
 *
 * <pre>{@code
 * record Input(String query, List<String> documents) {}
 * record Output(String answer, List<String> sources) {}
 *
 * var rlm = RlmHarness.builder(Input.class, Output.class)
 *     .model(rootModel)
 *     .subModel(subModel)
 *     .strategy("Answer the query using the documents...")
 *     .sandboxFactory(JvmSandbox.factory())
 *     .build();
 *
 * RlmResult<Output> result = rlm.run(new Input("what is helios?", docs));
 * }</pre>
 *
 * <p>Defaults match Trampoline production values: {@code maxIterations=30}, {@code maxLlmCalls=50},
 * {@code maxOutputCharsToModel=5000}.
 *
 * <p>This is a thin assembly over existing primitives — not a parallel hierarchy. Every option here
 * maps to either an {@link AgentConfig} or a {@link ReplConfig} field. Drop down to the primitives
 * any time the harness is too narrow.
 */
public final class RlmHarness<I, O> {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Default outer iteration cap. Matches Trampoline. */
  public static final int DEFAULT_MAX_ITERATIONS = 30;

  private final Class<I> inputType;
  private final Class<O> outputType;
  private final OutputSchema<I> inputSchema;
  private final OutputSchema<O> outputSchema;
  private final Model rootModel;
  private final Model subModel;
  private final String strategy;
  private final List<HostFunction> extraHostFunctions;
  private final SandboxFactory sandboxFactory;
  private final int maxIterations;
  private final int maxLlmCalls;
  private final int maxOutputCharsToModel;
  private final boolean budgetHeader;
  private final List<RequiredPredictSignature> requiredPredictSignatures;
  private final BiPredicate<String, String> signatureMatcher;
  private final int maxExecutedCodeChars;
  private final SandboxBindingsListener sandboxBindingsListener;
  private final Semaphore concurrencyLimiter;
  private final List<TraceListener> traceListeners;
  private final List<SpanListener> spanListeners;
  private final String systemPromptOverride;

  private RlmHarness(Builder<I, O> b) {
    this.inputType = b.inputType;
    this.outputType = b.outputType;
    this.inputSchema = OutputSchema.of(b.inputType);
    this.outputSchema = b.outputSchema != null ? b.outputSchema : OutputSchema.of(b.outputType);
    this.rootModel = b.rootModel;
    this.subModel = b.subModel != null ? b.subModel : b.rootModel;
    this.strategy = b.strategy;
    this.extraHostFunctions = List.copyOf(b.extraHostFunctions);
    this.sandboxFactory = b.sandboxFactory;
    this.maxIterations = b.maxIterations;
    this.maxLlmCalls = b.maxLlmCalls;
    this.maxOutputCharsToModel = b.maxOutputCharsToModel;
    this.budgetHeader = b.budgetHeader;
    this.requiredPredictSignatures = List.copyOf(b.requiredPredictSignatures);
    this.signatureMatcher = b.signatureMatcher;
    this.maxExecutedCodeChars = b.maxExecutedCodeChars;
    this.sandboxBindingsListener = b.sandboxBindingsListener;
    this.concurrencyLimiter =
        b.concurrencyLimiter != null
            ? b.concurrencyLimiter
            : new Semaphore(ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS);
    this.traceListeners = List.copyOf(b.traceListeners);
    this.spanListeners = List.copyOf(b.spanListeners);
    this.systemPromptOverride = b.systemPromptOverride;
  }

  public static <I, O> Builder<I, O> builder(Class<I> inputType, Class<O> outputType) {
    if (inputType == null || outputType == null) {
      throw new IllegalArgumentException("inputType and outputType are required");
    }
    return new Builder<>(inputType, outputType);
  }

  /**
   * Run the harness against {@code input}. Drives the full RLM loop: serializes the input, builds
   * the system prompt, opens a {@link ReplSession}, runs the agent loop, then either unwraps the
   * submitted output or runs {@link ExtractFallback} when the loop exited without {@code submit()}.
   *
   * @param input the typed input record. Serialized to JSON for the user message
   * @return an {@link RlmResult} describing what happened
   */
  public RlmResult<O> run(I input) {
    if (input == null) {
      return failure("input must not be null", List.of(), 0);
    }
    PreparedInput prepared;
    try {
      prepared = serializeInput(input);
    } catch (Exception e) {
      return failure("failed to serialize input: " + e.getMessage(), List.of(), 0);
    }

    var hostFunctions = assembleHostFunctions(prepared.inputMap());
    var bindingSnippet = InputBindings.snippet(inputType);
    var replConfig = buildReplConfig(hostFunctions);
    var systemPrompt = chooseSystemPrompt(hostFunctions);

    try (var session = ReplSession.create(replConfig, concurrencyLimiter)) {
      var bindingFailure = runBindingSnippet(session, bindingSnippet);
      if (bindingFailure != null) {
        return bindingFailure;
      }

      var memory = InMemoryMemory.withDefaults();
      var userId = "rlm";
      var sessionId = UUID.randomUUID();

      var agentConfig = buildAgentConfig(session, memory, systemPrompt);
      var agent = new Agent(agentConfig);
      var sessionCtx =
          SessionContext.newBuilder()
              .withUserId(userId)
              .withSessionId(sessionId)
              .withUserInput(prepared.inputJson())
              .build();
      var runResult = agent.run(sessionCtx);

      var submitted = session.submittedOutput();
      if (submitted != null) {
        var handled = handleSubmittedResult(session, submitted);
        if (handled != null) {
          return handled;
        }
      }
      return runExtractFallback(session, memory, userId, sessionId, runResult);
    } catch (Exception e) {
      return failure("RLM run failed: " + e.getMessage(), List.of(), 0);
    }
  }

  /** Serialized form of a typed input — JSON for the user message, Map for {@code __getInput}. */
  private record PreparedInput(String inputJson, Map<String, Object> inputMap) {}

  private PreparedInput serializeInput(I input) throws Exception {
    var json = MAPPER.writeValueAsString(input);
    Map<String, Object> map =
        MAPPER.convertValue(input, new tools.jackson.core.type.TypeReference<>() {});
    return new PreparedInput(json, map);
  }

  /**
   * Builds the sandbox's host-function surface: user-supplied extras plus the default {@code
   * predict} (unless the user supplied their own) plus the harness-internal {@code __getInput}
   * source for typed input bindings (when bindings are emitted).
   */
  private List<HostFunction> assembleHostFunctions(Map<String, Object> inputMap) {
    var all = new ArrayList<HostFunction>(extraHostFunctions);
    if (all.stream().noneMatch(f -> "predict".equals(f.name()))) {
      all.add(PredictFunction.create(subModel));
    }
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
        .withSubmitSchema(outputSchema)
        .withMaxLlmCalls(maxLlmCalls)
        .withBudgetHeader(budgetHeader)
        .withRequiredPredictSignatures(requiredPredictSignatures)
        .withSignatureMatcher(signatureMatcher)
        .withMaxExecutedCodeChars(maxExecutedCodeChars)
        .withSandboxBindingsListener(sandboxBindingsListener)
        .build();
  }

  private String chooseSystemPrompt(List<HostFunction> hostFunctions) {
    if (systemPromptOverride != null) {
      return systemPromptOverride;
    }
    var boundNames = InputBindings.boundFieldNames(inputType);
    return RlmSystemPrompt.build(
        strategy,
        inputSchema,
        outputSchema,
        hostFunctions,
        maxOutputCharsToModel,
        maxLlmCalls,
        boundNames);
  }

  /**
   * Run the typed-input binding snippet (if generated for this input shape) and return a failure
   * result if it exited non-zero or wrote to stderr; {@code null} on success or when no snippet was
   * needed.
   */
  private RlmResult<O> runBindingSnippet(ReplSession session, String bindingSnippet) {
    if (bindingSnippet == null) {
      return null;
    }
    var bindingResult = session.execute(bindingSnippet);
    if (bindingResult.exitCode() != 0 || !bindingResult.stderr().isBlank()) {
      return failure(
          "input binding failed: "
              + (bindingResult.stderr().isBlank()
                  ? "exit code " + bindingResult.exitCode()
                  : bindingResult.stderr()),
          session.history(),
          session.predictCallCount());
    }
    return null;
  }

  private AgentConfig buildAgentConfig(
      ReplSession session, InMemoryMemory memory, String systemPrompt) {
    return AgentConfig.newBuilder()
        .withName("rlm-harness")
        .withModel(rootModel)
        .withSystemPrompt(systemPrompt)
        .withTool(CodeExecutionTool.create(session))
        .withIncludeMemoryTools(false)
        .withMaxIterations(maxIterations)
        .withMemory(memory)
        .withTraceListeners(traceListeners)
        .withSpanListeners(spanListeners)
        .withIterationHook(requireSubmitHook(session, requiredPredictSignatures))
        .build();
  }

  /**
   * Post-loop check on a {@code submit()}-bearing trajectory. Returns:
   *
   * <ul>
   *   <li>a {@code FAILED} result if a registered {@link RequiredPredictSignature} was never
   *       invoked (the in-loop hook only fires when the model volunteers a STOP turn; heavy
   *       tool-using models like Opus 4.7 may keep emitting {@code execute_code} until
   *       maxIterations and never hit the hook),
   *   <li>a {@code SUBMITTED} result when the submitted value coerces to {@code O},
   *   <li>{@code null} to signal the caller should fall through to extract-fallback (coercion
   *       failed — defensive against schema drift).
   * </ul>
   */
  private RlmResult<O> handleSubmittedResult(ReplSession session, Object submitted) {
    var missingSignatures = missingRequiredSignatures(session);
    if (!missingSignatures.isEmpty()) {
      return failure(
          session,
          "submit() succeeded but required predict() signature(s) were never called: "
              + missingSignatures
              + ". Trajectory rejected. To debug: compare ReplSession.predictInstructions()"
              + " against the registered RequiredPredictSignature.instructions() —"
              + " mismatches usually mean the model paraphrased the registered text. Use"
              + " withSignatureMatcher(...) to relax the matcher (substring/prefix/regex)"
              + " when needed.");
    }
    var typed = coerce(submitted);
    if (typed == null) {
      return null;
    }
    return new RlmResult<>(
        typed,
        RlmResult.Status.SUBMITTED,
        null,
        session.history(),
        session.predictCallCount(),
        session.predictCalls(),
        session.calledHostFunctions());
  }

  /**
   * Recovery path: summarize the agent's message history and run a single schema-constrained
   * extract on a fresh context. Status flips to {@code EXTRACTED} on success; otherwise the primary
   * loop error and the fallback error are both surfaced.
   */
  private RlmResult<O> runExtractFallback(
      ReplSession session,
      InMemoryMemory memory,
      String userId,
      UUID sessionId,
      Result<?> runResult) {
    var messageHistory = memory.history(userId, sessionId);
    var summary = ExtractFallback.summarize(messageHistory);
    if (Strings.isBlank(summary)) {
      summary =
          "The previous run produced no usable trajectory. Reconstitute a best-effort output "
              + "based on the original input only.";
    }
    var fallback = ExtractFallback.attempt(rootModel, outputSchema, summary);
    return switch (fallback) {
      case Result.Success<O>(O extracted) ->
          new RlmResult<>(
              extracted,
              RlmResult.Status.EXTRACTED,
              null,
              session.history(),
              session.predictCallCount(),
              session.predictCalls(),
              session.calledHostFunctions());
      case Result.Failure<O>(String fallbackError, Exception ignored) -> {
        var primaryError =
            runResult instanceof Result.Failure<?> rf
                ? rf.error()
                : "agent loop exited without submit()";
        yield failure(session, primaryError + "; extract-fallback also failed: " + fallbackError);
      }
    };
  }

  /**
   * Build the in-loop "you forgot to do X" guard. Two failure modes get caught here:
   *
   * <ul>
   *   <li><b>No submit.</b> Trampoline learned (paper Appendix B.2) that RLMs frequently compute
   *       the answer in code and stop without calling {@code submit()}, treating the REPL as a
   *       notebook with implicit-last-expression semantics. Hook injects a corrective USER message
   *       naming submit as the missing step.
   *   <li><b>Required predict signatures skipped.</b> Some specialist signatures (e.g. a
   *       devil's-advocate pass) are critical to the strategy; if the model stops without invoking
   *       them, prompt-only enforcement is brittle. Hook compares declared {@link
   *       RequiredPredictSignature}s against {@link ReplSession#calledSignatures()} (matched by
   *       exact equality on the {@code instructions} string) and names the missing ones.
   * </ul>
   *
   * <p>Both checks fire on the same iteration boundary: when the model tries to stop. The
   * corrective message lists every missing piece so the model fixes them in one retry rather than
   * cycling. Extract-fallback remains the safety net for cases where even the nudge doesn't take.
   */
  private static IterationHook requireSubmitHook(
      ReplSession session, List<RequiredPredictSignature> required) {
    return ctx -> {
      if (ctx.iteration() >= ctx.maxIterations()) {
        return IterationAction.allow();
      }
      List<RequiredPredictSignature> missingSignatures = List.of();
      if (required != null && !required.isEmpty()) {
        var called = session.calledSignatures();
        var pending = new ArrayList<RequiredPredictSignature>();
        for (var sig : required) {
          if (!called.contains(sig.name())) {
            pending.add(sig);
          }
        }
        missingSignatures = pending;
      }
      var submitMissing = session.submittedOutput() == null;
      if (!submitMissing && missingSignatures.isEmpty()) {
        return IterationAction.allow();
      }

      var message = new StringBuilder();
      if (!missingSignatures.isEmpty()) {
        message.append(
            "You stopped without invoking required predict() signature(s). Each listed signature"
                + " must run before you submit:\n");
        for (var sig : missingSignatures) {
          message.append("  - ").append(sig.name()).append(": ");
          if (!Strings.isBlank(sig.remediation())) {
            message.append(sig.remediation().strip());
          } else {
            message
                .append("call predict(<the exact instructions string for ")
                .append(sig.name())
                .append(">, <input>) and capture the result before submitting.");
          }
          message.append('\n');
        }
        if (submitMissing) {
          message.append("Then call submit(...) with your final result.");
        } else {
          message.append(
              "You already called submit() but the run will be marked incomplete unless these"
                  + " signatures run. Re-do the missing predict(...) calls now.");
        }
      } else {
        message.append(
            "You stopped without calling submit(). Your work is not captured until you do."
                + " The harness has computed values in your sandbox variables but does NOT"
                + " auto-extract them. Call submit(Map.of(\"field1\", value1, \"field2\","
                + " value2, ...)) now in your next execute_code call, using the values you have"
                + " already computed. Do not recompute; just submit.");
      }
      return IterationAction.inject(message.toString());
    };
  }

  /**
   * Names of registered {@link RequiredPredictSignature}s the session has NOT invoked yet, in
   * declaration order. Empty list means all required signatures were called (or none were
   * registered). Used by the post-loop check to fail trajectories that submitted without exercising
   * the full specialist set.
   */
  private List<String> missingRequiredSignatures(ReplSession session) {
    if (requiredPredictSignatures.isEmpty()) {
      return List.of();
    }
    var called = session.calledSignatures();
    var missing = new ArrayList<String>();
    for (var sig : requiredPredictSignatures) {
      if (!called.contains(sig.name())) {
        missing.add(sig.name());
      }
    }
    return missing;
  }

  @SuppressWarnings("unchecked")
  private O coerce(Object submitted) {
    if (outputType.isInstance(submitted)) {
      return (O) submitted;
    }
    try {
      var json = MAPPER.writeValueAsString(submitted);
      return MAPPER.readValue(json, outputType);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Failure path used pre-session (input validation, JSON serialization). No session yet, so
   * trajectory accessors get empty defaults.
   */
  private RlmResult<O> failure(
      String error, List<ai.singlr.repl.sandbox.ExecutionResult> history, int predictCallCount) {
    return new RlmResult<>(
        null, RlmResult.Status.FAILED, error, history, predictCallCount, List.of(), Map.of());
  }

  /**
   * Failure path with a live session — preserves the trajectory data the run produced. Used when
   * submit() ran but a required signature was missing, or when extract-fallback also failed;
   * downstream callers (e.g. eval metrics) still want the predict transcript and host function
   * counts even on failed trajectories.
   */
  private RlmResult<O> failure(ReplSession session, String error) {
    return new RlmResult<>(
        null,
        RlmResult.Status.FAILED,
        error,
        session.history(),
        session.predictCallCount(),
        session.predictCalls(),
        session.calledHostFunctions());
  }

  /** The output schema this harness submits against. Useful for tests and downstream wiring. */
  public OutputSchema<O> outputSchema() {
    return outputSchema;
  }

  /** The input type this harness was built with. */
  public Class<I> inputType() {
    return inputType;
  }

  /** The output type this harness was built with. */
  public Class<O> outputType() {
    return outputType;
  }

  public static final class Builder<I, O> {
    private final Class<I> inputType;
    private final Class<O> outputType;
    private Model rootModel;
    private Model subModel;
    private String strategy;
    private final List<HostFunction> extraHostFunctions = new ArrayList<>();
    private final List<Skill> skills = new ArrayList<>();
    private SandboxFactory sandboxFactory;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private int maxLlmCalls = ReplConfig.DEFAULT_MAX_LLM_CALLS;
    private int maxOutputCharsToModel = ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;
    private boolean budgetHeader = true;
    private final List<RequiredPredictSignature> requiredPredictSignatures = new ArrayList<>();
    private BiPredicate<String, String> signatureMatcher;
    private int maxExecutedCodeChars = ReplConfig.DEFAULT_MAX_EXECUTED_CODE_CHARS;
    private SandboxBindingsListener sandboxBindingsListener;
    private Semaphore concurrencyLimiter;
    private final List<TraceListener> traceListeners = new ArrayList<>();
    private final List<SpanListener> spanListeners = new ArrayList<>();
    private String systemPromptOverride;

    private OutputSchema<O> outputSchema;

    private Builder(Class<I> inputType, Class<O> outputType) {
      this.inputType = inputType;
      this.outputType = outputType;
    }

    /**
     * Provide a custom {@link OutputSchema} for the submit path. When unset, the harness uses
     * {@code OutputSchema.of(outputType)}. Use this to attach a {@link SubmitValidator} (for
     * semantic checks the model must satisfy before submit accepts) or a hand-built {@link
     * ai.singlr.core.schema.JsonSchema} for non-record output shapes.
     *
     * <p>Typical: pair with {@link OutputSchema#withSubmitValidator(java.util.function.Predicate,
     * String)} to reject degenerate submits and force the model to retry within the existing
     * iteration / LLM-call budget. See {@link #submitValidator} for a convenience that wraps this
     * for the common case.
     *
     * @param schema the schema to use for submit validation; never {@code null}
     * @return this builder
     */
    public Builder<I, O> outputSchema(OutputSchema<O> schema) {
      if (schema == null) {
        throw new IllegalArgumentException("schema must not be null");
      }
      this.outputSchema = schema;
      return this;
    }

    /**
     * Attach a {@link SubmitValidator} without supplying a custom schema. Equivalent to {@code
     * outputSchema(OutputSchema.of(outputType).withSubmitValidator(validator))} when no schema has
     * been set yet, or to applying the validator on top of the previously-supplied schema
     * otherwise.
     *
     * @param validator the validator to apply at submit time; never {@code null}
     * @return this builder
     */
    public Builder<I, O> submitValidator(SubmitValidator<O> validator) {
      var base = outputSchema != null ? outputSchema : OutputSchema.of(outputType);
      this.outputSchema = base.withSubmitValidator(validator);
      return this;
    }

    /**
     * Convenience for the simple {@link java.util.function.Predicate}-plus-correction case.
     *
     * @param predicate the test the parsed output must satisfy; never {@code null}
     * @param correction model-readable correction message surfaced when the predicate fails
     * @return this builder
     */
    public Builder<I, O> submitValidator(
        java.util.function.Predicate<O> predicate, String correction) {
      var base = outputSchema != null ? outputSchema : OutputSchema.of(outputType);
      this.outputSchema = base.withSubmitValidator(predicate, correction);
      return this;
    }

    /** The root model that drives the outer agent loop. Required. */
    public Builder<I, O> model(Model model) {
      this.rootModel = model;
      return this;
    }

    /** The sub-model called by {@code predict()}. Defaults to the root model when unset. */
    public Builder<I, O> subModel(Model subModel) {
      this.subModel = subModel;
      return this;
    }

    /** Task-specific instructions injected into the system prompt. Strongly recommended. */
    public Builder<I, O> strategy(String strategy) {
      this.strategy = strategy;
      return this;
    }

    /**
     * Replace the entire generated system prompt with a custom one. When set, {@link #strategy} is
     * ignored. For advanced users porting prompts from other RLM frameworks.
     */
    public Builder<I, O> systemPrompt(String fullSystemPrompt) {
      this.systemPromptOverride = fullSystemPrompt;
      return this;
    }

    /** Sandbox factory. Required. Typically {@code JvmSandbox.factory()}. */
    public Builder<I, O> sandboxFactory(SandboxFactory factory) {
      this.sandboxFactory = factory;
      return this;
    }

    /** Add a host function reachable inside the sandbox in addition to {@code predict/submit}. */
    public Builder<I, O> hostFunction(HostFunction function) {
      this.extraHostFunctions.add(function);
      return this;
    }

    /** Add several host functions in one call. */
    public Builder<I, O> hostFunctions(List<HostFunction> functions) {
      this.extraHostFunctions.addAll(functions);
      return this;
    }

    /**
     * Attach a {@link Skill} bundle. The skill's tools are added to the sandbox surface and its
     * instructions are appended to the system prompt under a {@code "## Skill: <name>"} header.
     */
    public Builder<I, O> skill(Skill skill) {
      this.skills.add(skill);
      return this;
    }

    /** Attach several skills in one call. */
    public Builder<I, O> skills(List<Skill> skills) {
      this.skills.addAll(skills);
      return this;
    }

    /** Outer agent iteration cap. Defaults to {@value #DEFAULT_MAX_ITERATIONS}. */
    public Builder<I, O> maxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    /**
     * Per-session {@code predict()} call budget. Defaults to {@link
     * ReplConfig#DEFAULT_MAX_LLM_CALLS}.
     */
    public Builder<I, O> maxLlmCalls(int maxLlmCalls) {
      this.maxLlmCalls = maxLlmCalls;
      return this;
    }

    /** Cap on {@code execute_code} output shown to the model. Defaults to 5000. */
    public Builder<I, O> maxOutputCharsToModel(int max) {
      this.maxOutputCharsToModel = max;
      return this;
    }

    /**
     * Whether each {@code execute_code} tool result is prefixed with a budget header (e.g. {@code
     * [budget: predicts=12/50, last_exec=2.4s, timeout=30s]}) so the model can self-regulate
     * parallelism and notice inefficient code. Default {@code true}; the header is omitted
     * automatically when no budget is configured.
     */
    public Builder<I, O> budgetHeader(boolean enabled) {
      this.budgetHeader = enabled;
      return this;
    }

    /**
     * Add a {@link RequiredPredictSignature} the model must invoke before stopping. The harness's
     * iteration hook checks each signature's {@code instructions} string verbatim against recorded
     * {@code predict()} calls; misses trigger a corrective USER message naming the omitted
     * signature.
     */
    public Builder<I, O> requiredPredictSignature(RequiredPredictSignature signature) {
      this.requiredPredictSignatures.add(signature);
      return this;
    }

    /** Add several required signatures in one call. */
    public Builder<I, O> requiredPredictSignatures(List<RequiredPredictSignature> signatures) {
      this.requiredPredictSignatures.addAll(signatures);
      return this;
    }

    /**
     * Predicate that decides whether a registered {@link RequiredPredictSignature} matches an
     * actual {@code predict()} call's instructions text. Defaults to {@link String#equals}.
     * Override with substring/prefix/regex semantics when models paraphrase the registered text.
     *
     * <p>The predicate is called {@code matcher.test(registered, actual)}. If you suspect a
     * mismatch in production, dump {@link ReplSession#predictInstructions()} from a failed run and
     * compare to your registered signatures.
     */
    public Builder<I, O> signatureMatcher(BiPredicate<String, String> matcher) {
      this.signatureMatcher = matcher;
      return this;
    }

    /**
     * Per-call cap on the {@code executedCode} field returned in {@link ExecutionResult}. Defaults
     * to {@link ReplConfig#DEFAULT_MAX_EXECUTED_CODE_CHARS}. Set to {@code 0} to disable per-call
     * truncation (full snippet always returned).
     */
    public Builder<I, O> maxExecutedCodeChars(int max) {
      this.maxExecutedCodeChars = max;
      return this;
    }

    /**
     * Listener that observes the sandbox's working memory after each {@code execute_code}. {@code
     * null} disables the callback (default). When set, the harness instructs the sandbox to collect
     * a length-capped snapshot of every user-declared {@code var}; the listener fires synchronously
     * inside {@code session.execute(...)}.
     */
    public Builder<I, O> sandboxBindingsListener(SandboxBindingsListener listener) {
      this.sandboxBindingsListener = listener;
      return this;
    }

    /** Override the concurrency semaphore. Defaults to a fresh permit pool of 50. */
    public Builder<I, O> concurrencyLimiter(Semaphore semaphore) {
      this.concurrencyLimiter = semaphore;
      return this;
    }

    /** Add a {@link TraceListener} to the agent. */
    public Builder<I, O> traceListener(TraceListener listener) {
      this.traceListeners.add(listener);
      return this;
    }

    /** Add a {@link SpanListener} to the agent. */
    public Builder<I, O> spanListener(SpanListener listener) {
      this.spanListeners.add(listener);
      return this;
    }

    public RlmHarness<I, O> build() {
      if (rootModel == null) {
        throw new IllegalStateException("model is required");
      }
      if (sandboxFactory == null) {
        throw new IllegalStateException("sandboxFactory is required");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      if (maxLlmCalls < 0) {
        throw new IllegalStateException("maxLlmCalls must be >= 0");
      }
      if (maxOutputCharsToModel < 0) {
        throw new IllegalStateException("maxOutputCharsToModel must be >= 0");
      }
      if (!skills.isEmpty()) {
        var merged = Skill.merge(skills);
        this.extraHostFunctions.addAll(merged.tools());
        if (!merged.instructions().isBlank()) {
          this.strategy =
              Strings.isBlank(this.strategy)
                  ? merged.instructions()
                  : this.strategy + "\n\n" + merged.instructions();
        }
      }
      return new RlmHarness<>(this);
    }
  }
}
