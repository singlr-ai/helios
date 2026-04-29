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
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.trace.SpanListener;
import ai.singlr.core.trace.TraceListener;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.PredictFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
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
  private final Semaphore concurrencyLimiter;
  private final List<TraceListener> traceListeners;
  private final List<SpanListener> spanListeners;
  private final String systemPromptOverride;

  private RlmHarness(Builder<I, O> b) {
    this.inputType = b.inputType;
    this.outputType = b.outputType;
    this.inputSchema = OutputSchema.of(b.inputType);
    this.outputSchema = OutputSchema.of(b.outputType);
    this.rootModel = b.rootModel;
    this.subModel = b.subModel != null ? b.subModel : b.rootModel;
    this.strategy = b.strategy;
    this.extraHostFunctions = List.copyOf(b.extraHostFunctions);
    this.sandboxFactory = b.sandboxFactory;
    this.maxIterations = b.maxIterations;
    this.maxLlmCalls = b.maxLlmCalls;
    this.maxOutputCharsToModel = b.maxOutputCharsToModel;
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
    String inputJson;
    Map<String, Object> inputMap;
    try {
      inputJson = MAPPER.writeValueAsString(input);
      inputMap = MAPPER.convertValue(input, new tools.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      return failure("failed to serialize input: " + e.getMessage(), List.of(), 0);
    }

    var allHostFunctions = new ArrayList<HostFunction>(extraHostFunctions);
    if (allHostFunctions.stream().noneMatch(f -> "predict".equals(f.name()))) {
      allHostFunctions.add(PredictFunction.create(subModel));
    }
    var bindingSnippet = InputBindings.snippet(inputType);
    if (bindingSnippet != null) {
      // Routed by HostBridge.getInput(). Per-run state lives in this lambda's closure.
      allHostFunctions.add(
          new HostFunction(
              "__getInput",
              "Harness-internal: returns the input record fields as Map<String,Object>",
              params -> inputMap));
    }

    var replConfig =
        ReplConfig.newBuilder()
            .withSandboxFactory(sandboxFactory)
            .withHostFunctions(allHostFunctions)
            .withMaxOutputCharsToModel(maxOutputCharsToModel)
            .withSubmitSchema(outputSchema)
            .withMaxLlmCalls(maxLlmCalls)
            .build();

    var boundNames = InputBindings.boundFieldNames(inputType);

    var systemPrompt =
        systemPromptOverride != null
            ? systemPromptOverride
            : RlmSystemPrompt.build(
                strategy,
                inputSchema,
                outputSchema,
                allHostFunctions,
                maxOutputCharsToModel,
                maxLlmCalls,
                boundNames);

    try (var session = ReplSession.create(replConfig, concurrencyLimiter)) {
      if (bindingSnippet != null) {
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
      }
      var memory = InMemoryMemory.withDefaults();
      var userId = "rlm";
      var sessionId = UUID.randomUUID();

      var agentConfig =
          AgentConfig.newBuilder()
              .withName("rlm-harness")
              .withModel(rootModel)
              .withSystemPrompt(systemPrompt)
              .withTool(CodeExecutionTool.create(session))
              .withIncludeMemoryTools(false)
              .withMaxIterations(maxIterations)
              .withMemory(memory)
              .withTraceListeners(traceListeners)
              .withSpanListeners(spanListeners)
              .withIterationHook(requireSubmitHook(session))
              .build();

      var agent = new Agent(agentConfig);
      var sessionCtx =
          SessionContext.newBuilder()
              .withUserId(userId)
              .withSessionId(sessionId)
              .withUserInput(inputJson)
              .build();
      var runResult = agent.run(sessionCtx);

      var submitted = session.submittedOutput();
      if (submitted != null) {
        var typed = coerce(submitted);
        if (typed != null) {
          return new RlmResult<>(
              typed,
              RlmResult.Status.SUBMITTED,
              null,
              session.history(),
              session.predictCallCount());
        }
      }

      // No clean submit. Try extract-fallback against the agent's message history.
      var messageHistory = memory.history(userId, sessionId);
      var summary = ExtractFallback.summarize(messageHistory);
      if (Strings.isBlank(summary)) {
        summary =
            "The previous run produced no usable trajectory. Reconstitute a best-effort output "
                + "based on the original input only.";
      }
      var fallback = ExtractFallback.attempt(rootModel, outputSchema, summary);
      if (fallback.isSuccess()) {
        return new RlmResult<>(
            ((Result.Success<O>) fallback).value(),
            RlmResult.Status.EXTRACTED,
            null,
            session.history(),
            session.predictCallCount());
      }

      var primaryError =
          runResult instanceof Result.Failure<?> rf
              ? rf.error()
              : "agent loop exited without submit()";
      var fallbackError = ((Result.Failure<O>) fallback).error();
      return failure(
          primaryError + "; extract-fallback also failed: " + fallbackError,
          session.history(),
          session.predictCallCount());
    } catch (Exception e) {
      return failure("RLM run failed: " + e.getMessage(), List.of(), 0);
    }
  }

  /**
   * Build the in-loop "you forgot to submit" guard. Trampoline learned (paper Appendix B.2) that
   * RLMs frequently compute the answer in code and then stop without calling {@code submit()},
   * treating the REPL as a notebook with implicit-last-expression semantics. This hook fires when
   * the model tries to stop, and if {@code submit()} was never called and budget remains, it
   * injects a corrective USER message naming the failure mode and the exact remediation.
   * Extract-fallback remains as the safety net for cases where even the nudge doesn't take.
   */
  private static IterationHook requireSubmitHook(ReplSession session) {
    return ctx -> {
      if (session.submittedOutput() != null) {
        return IterationAction.allow();
      }
      if (ctx.iteration() >= ctx.maxIterations()) {
        return IterationAction.allow();
      }
      return IterationAction.inject(
          "You stopped without calling submit(). Your work is not captured until you do."
              + " The harness has computed values in your sandbox variables but does NOT"
              + " auto-extract them. Call submit(Map.of(\"field1\", value1, \"field2\","
              + " value2, ...)) now in your next execute_code call, using the values you have"
              + " already computed. Do not recompute; just submit.");
    };
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

  private RlmResult<O> failure(
      String error, List<ai.singlr.repl.sandbox.ExecutionResult> history, int predictCallCount) {
    return new RlmResult<>(null, RlmResult.Status.FAILED, error, history, predictCallCount);
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
    private Semaphore concurrencyLimiter;
    private final List<TraceListener> traceListeners = new ArrayList<>();
    private final List<SpanListener> spanListeners = new ArrayList<>();
    private String systemPromptOverride;

    private Builder(Class<I> inputType, Class<O> outputType) {
      this.inputType = inputType;
      this.outputType = outputType;
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
