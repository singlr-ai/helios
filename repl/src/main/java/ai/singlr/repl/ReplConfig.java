/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 */
public record ReplConfig(
    SandboxFactory sandboxFactory,
    Duration executionTimeout,
    int maxConcurrentSessions,
    List<HostFunction> hostFunctions,
    int maxOutputCharsToModel,
    OutputSchema<?> submitSchema,
    int maxLlmCalls) {

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
    hostFunctions = List.copyOf(hostFunctions);
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

    public ReplConfig build() {
      return new ReplConfig(
          sandboxFactory,
          executionTimeout,
          maxConcurrentSessions,
          hostFunctions,
          maxOutputCharsToModel,
          submitSchema,
          maxLlmCalls);
    }
  }
}
