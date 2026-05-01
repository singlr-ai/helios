/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Configuration for REPL sessions. Immutable and reusable across sessions.
 *
 * @param sandboxFactory creates the sandbox for each session
 * @param executionTimeout default timeout per code execution
 * @param maxConcurrentSessions maximum concurrent sessions (enforced via semaphore)
 * @param hostFunctions additional host functions registered for each session
 * @param maxOutputCharsToModel cap on the size of the {@code execute_code} tool result returned to
 *     the model. The full untruncated output stays in {@link ReplSession#history()} for operators.
 *     This is the load-bearing context-rot fix for RLM-style trajectories: variables persist fully
 *     across iterations in the sandbox, but the printed output the model sees on each turn is
 *     bounded. Defaults to 5000 (matches the production Trampoline {@code predict-rlm}
 *     implementation). Set to {@code 0} to disable truncation
 * @param submitSchema optional output schema. When set, the auto-registered {@code submit} host
 *     function validates the submitted value against this schema and, on failure, throws back
 *     through the JSON-RPC bridge so the model sees the error in its next {@code execute_code}
 *     iteration and can correct without losing sandbox variables. {@code null} means {@code submit}
 *     accepts anything (legacy behavior). Ignored if a user-supplied {@code submit} host function
 *     is already in {@link #hostFunctions()}
 * @param maxLlmCalls cap on cumulative {@code predict()} invocations within a single session. When
 *     the cap is exceeded the {@code predict} host function throws a {@link
 *     SandboxBudgetExceededException} that surfaces as a JShell-side error in the next {@code
 *     execute_code} tool result, so the model can wrap up via {@code submit()} instead of spending
 *     further. Defaults to 50 (Trampoline's production value). Set to {@code 0} to disable the
 *     budget. Defense against runaway recursion on simple tasks (paper Appendix B.3)
 * @param budgetHeader when {@code true} (default) the {@code execute_code} tool prepends a one-line
 *     budget header (e.g. {@code [budget: predicts=12/50]}) to every tool result so the model can
 *     self-regulate parallelism. The header is rendered conditionally — if {@code maxLlmCalls} is
 *     {@code 0} (unlimited) the line is omitted entirely
 * @param requiredPredictSignatures signatures the model is required to invoke before stopping. The
 *     iteration hook in {@link RlmHarness} compares each entry's {@code instructions} text verbatim
 *     against the {@code instructions} arg of recorded {@code predict()} calls; missing entries
 *     trigger a corrective USER-message injection naming the omitted signature. Empty list means no
 *     required-signature check. Lower-level callers using {@link ReplSession} directly can read
 *     {@link ReplSession#calledSignatures()} and build their own check
 * @param sandboxBindingsListener optional observer of the sandbox's working memory after each
 *     {@code execute_code}. Fires synchronously inside {@link ReplSession#execute(String)} after
 *     the underlying sandbox returns. {@code null} disables the callback (default)
 * @param maxBindingValueChars per-value cap on the sandbox-side {@code toString} repr emitted in
 *     {@link ExecutionResult#bindings()}. Defaults to {@value #DEFAULT_MAX_BINDING_VALUE_CHARS}.
 *     Set to {@code 0} to disable per-value truncation
 * @param maxBindingSnapshotChars total cap across all values in a single bindings snapshot.
 *     Defaults to {@value #DEFAULT_MAX_BINDING_SNAPSHOT_CHARS}. Once exceeded, remaining variables
 *     are dropped from the snapshot. Set to {@code 0} to disable the total cap
 * @param signatureMatcher predicate that decides whether a {@code predict()} call's actual {@code
 *     instructions} text matches a registered {@link RequiredPredictSignature}. Called as {@code
 *     matcher.test(registered.instructions(), actualInstructions)}. {@code null} (default) uses
 *     {@link String#equals} — exact match. Override with substring/prefix/regex semantics when
 *     models paraphrase the registered text
 * @param maxExecutedCodeChars per-call cap on the {@code executedCode} field returned in {@link
 *     ExecutionResult}. Defaults to {@value #DEFAULT_MAX_EXECUTED_CODE_CHARS}. Set to {@code 0} to
 *     disable per-call truncation (full snippet always returned). Truncation appends a {@code ...
 *     (len=N)} marker so consumers know the original length
 */
public record ReplConfig(
    SandboxFactory sandboxFactory,
    Duration executionTimeout,
    int maxConcurrentSessions,
    List<HostFunction> hostFunctions,
    int maxOutputCharsToModel,
    OutputSchema<?> submitSchema,
    int maxLlmCalls,
    boolean budgetHeader,
    List<RequiredPredictSignature> requiredPredictSignatures,
    SandboxBindingsListener sandboxBindingsListener,
    int maxBindingValueChars,
    int maxBindingSnapshotChars,
    BiPredicate<String, String> signatureMatcher,
    int maxExecutedCodeChars) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max concurrent sessions. */
  public static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 50;

  /** Default {@code execute_code} output cap shown to the model: 5000 chars. */
  public static final int DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL = 5000;

  /**
   * Default per-session {@code predict()} call budget: 50. Matches Trampoline {@code predict-rlm}.
   */
  public static final int DEFAULT_MAX_LLM_CALLS = 50;

  /** Default per-value cap on the {@code toString} repr in a bindings snapshot. */
  public static final int DEFAULT_MAX_BINDING_VALUE_CHARS = 200;

  /** Default total cap on a bindings snapshot. */
  public static final int DEFAULT_MAX_BINDING_SNAPSHOT_CHARS = 16 * 1024;

  /**
   * Default per-call cap on the {@code executedCode} field. Matches the model-facing output cap so
   * a 5K snippet renders the same as 5K of stdout — keeps live-UI displays aligned.
   */
  public static final int DEFAULT_MAX_EXECUTED_CODE_CHARS = 5000;

  public ReplConfig {
    if (sandboxFactory == null) {
      throw new IllegalArgumentException("Sandbox factory must not be null");
    }
    if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
      throw new IllegalArgumentException("Execution timeout must be positive");
    }
    if (maxConcurrentSessions <= 0) {
      throw new IllegalArgumentException("Max concurrent sessions must be positive");
    }
    if (maxOutputCharsToModel < 0) {
      throw new IllegalArgumentException(
          "maxOutputCharsToModel must be >= 0 (0 disables truncation)");
    }
    if (maxLlmCalls < 0) {
      throw new IllegalArgumentException("maxLlmCalls must be >= 0 (0 disables the budget)");
    }
    if (maxBindingValueChars < 0) {
      throw new IllegalArgumentException(
          "maxBindingValueChars must be >= 0 (0 disables per-value truncation)");
    }
    if (maxBindingSnapshotChars < 0) {
      throw new IllegalArgumentException(
          "maxBindingSnapshotChars must be >= 0 (0 disables the total cap)");
    }
    if (maxExecutedCodeChars < 0) {
      throw new IllegalArgumentException(
          "maxExecutedCodeChars must be >= 0 (0 disables per-call truncation)");
    }
    hostFunctions = List.copyOf(hostFunctions);
    requiredPredictSignatures =
        requiredPredictSignatures == null ? List.of() : List.copyOf(requiredPredictSignatures);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private SandboxFactory sandboxFactory;
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS;
    private final List<HostFunction> hostFunctions = new ArrayList<>();
    private int maxOutputCharsToModel = DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;
    private OutputSchema<?> submitSchema;
    private int maxLlmCalls = DEFAULT_MAX_LLM_CALLS;
    private boolean budgetHeader = true;
    private final List<RequiredPredictSignature> requiredPredictSignatures = new ArrayList<>();
    private SandboxBindingsListener sandboxBindingsListener;
    private int maxBindingValueChars = DEFAULT_MAX_BINDING_VALUE_CHARS;
    private int maxBindingSnapshotChars = DEFAULT_MAX_BINDING_SNAPSHOT_CHARS;
    private BiPredicate<String, String> signatureMatcher;
    private int maxExecutedCodeChars = DEFAULT_MAX_EXECUTED_CODE_CHARS;

    private Builder() {}

    public Builder withSandboxFactory(SandboxFactory sandboxFactory) {
      this.sandboxFactory = sandboxFactory;
      return this;
    }

    public Builder withExecutionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    public Builder withMaxConcurrentSessions(int maxConcurrentSessions) {
      this.maxConcurrentSessions = maxConcurrentSessions;
      return this;
    }

    public Builder withHostFunction(HostFunction function) {
      this.hostFunctions.add(function);
      return this;
    }

    public Builder withHostFunctions(List<HostFunction> functions) {
      this.hostFunctions.addAll(functions);
      return this;
    }

    public Builder withMaxOutputCharsToModel(int maxOutputCharsToModel) {
      this.maxOutputCharsToModel = maxOutputCharsToModel;
      return this;
    }

    public Builder withSubmitSchema(OutputSchema<?> submitSchema) {
      this.submitSchema = submitSchema;
      return this;
    }

    public Builder withMaxLlmCalls(int maxLlmCalls) {
      this.maxLlmCalls = maxLlmCalls;
      return this;
    }

    /**
     * Whether the {@code execute_code} tool result is prefixed with a one-line budget header.
     * Default {@code true}. When the only configured budget is unlimited the header is omitted.
     */
    public Builder withBudgetHeader(boolean budgetHeader) {
      this.budgetHeader = budgetHeader;
      return this;
    }

    /**
     * Add a required predict signature. The iteration hook checks each call's {@code instructions}
     * text verbatim against the registered list; missing signatures trigger a corrective USER
     * message naming the omitted signature.
     */
    public Builder withRequiredPredictSignature(RequiredPredictSignature signature) {
      this.requiredPredictSignatures.add(signature);
      return this;
    }

    /** Add several required predict signatures in one call. */
    public Builder withRequiredPredictSignatures(List<RequiredPredictSignature> signatures) {
      this.requiredPredictSignatures.addAll(signatures);
      return this;
    }

    /**
     * Listener that observes the sandbox's working memory after each {@code execute_code}. {@code
     * null} disables the callback (default).
     */
    public Builder withSandboxBindingsListener(SandboxBindingsListener listener) {
      this.sandboxBindingsListener = listener;
      return this;
    }

    /** Per-value cap on the bindings snapshot. */
    public Builder withMaxBindingValueChars(int chars) {
      this.maxBindingValueChars = chars;
      return this;
    }

    /** Total cap on a single bindings snapshot. */
    public Builder withMaxBindingSnapshotChars(int chars) {
      this.maxBindingSnapshotChars = chars;
      return this;
    }

    /**
     * Predicate that decides whether a registered {@link RequiredPredictSignature} matches a given
     * {@code predict()} call's instructions. Called as {@code matcher.test(registered, actual)}.
     * Defaults to {@link String#equals} — exact match. Use this escape hatch for substring or
     * prefix matching when the model is known to paraphrase.
     */
    public Builder withSignatureMatcher(BiPredicate<String, String> matcher) {
      this.signatureMatcher = matcher;
      return this;
    }

    /**
     * Per-call cap on {@link ExecutionResult#executedCode()}. {@code 0} disables truncation (full
     * snippet returned). Truncated values get a {@code ... (len=N)} marker.
     */
    public Builder withMaxExecutedCodeChars(int chars) {
      this.maxExecutedCodeChars = chars;
      return this;
    }

    public ReplConfig build() {
      return new ReplConfig(
          sandboxFactory,
          executionTimeout,
          maxConcurrentSessions,
          hostFunctions,
          maxOutputCharsToModel,
          submitSchema,
          maxLlmCalls,
          budgetHeader,
          requiredPredictSignatures,
          sandboxBindingsListener,
          maxBindingValueChars,
          maxBindingSnapshotChars,
          signatureMatcher,
          maxExecutedCodeChars);
    }
  }
}
