/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.util.Map;

/**
 * Result of executing code in a sandbox.
 *
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param exitCode the exit code (0 = success)
 * @param submitted the value passed to {@code submit()}, or {@code null} if not called
 * @param bindings post-execute snapshot of every user-declared {@code var} in the sandbox, mapped
 *     to a length-capped {@code toString} repr. Excludes harness-internal {@code __}-prefixed
 *     names. Empty (not {@code null}) when no bindings were captured (sandbox not configured to
 *     emit them, or no user vars exist yet). Drives the {@code SandboxBindingsListener} callback so
 *     live observers can watch the agent's working memory across iterations
 */
public record ExecutionResult(
    String stdout, String stderr, int exitCode, Object submitted, Map<String, String> bindings) {

  public ExecutionResult {
    if (stdout == null) {
      stdout = "";
    }
    if (stderr == null) {
      stderr = "";
    }
    bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
  }

  /** Convenience constructor pre-bindings; defaults bindings to an empty map. */
  public ExecutionResult(String stdout, String stderr, int exitCode, Object submitted) {
    this(stdout, stderr, exitCode, submitted, Map.of());
  }

  /** Whether the execution completed successfully (exit code 0). */
  public boolean succeeded() {
    return exitCode == 0;
  }

  /** Whether a value was submitted via the submit() host function. */
  public boolean hasSubmittedValue() {
    return submitted != null;
  }

  /** Create a successful result with stdout only. */
  public static ExecutionResult success(String stdout) {
    return new ExecutionResult(stdout, "", 0, null);
  }

  /** Create a successful result with stdout and a submitted value. */
  public static ExecutionResult success(String stdout, Object submitted) {
    return new ExecutionResult(stdout, "", 0, submitted);
  }

  /** Create a failure result with stderr. */
  public static ExecutionResult failure(String stderr) {
    return new ExecutionResult("", stderr, 1, null);
  }

  /** Create a failure result with a specific exit code. */
  public static ExecutionResult failure(String stderr, int exitCode) {
    return new ExecutionResult("", stderr, exitCode, null);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String stdout = "";
    private String stderr = "";
    private int exitCode;
    private Object submitted;
    private Map<String, String> bindings = Map.of();

    private Builder() {}

    public Builder withStdout(String stdout) {
      this.stdout = stdout;
      return this;
    }

    public Builder withStderr(String stderr) {
      this.stderr = stderr;
      return this;
    }

    public Builder withExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    public Builder withSubmitted(Object submitted) {
      this.submitted = submitted;
      return this;
    }

    public Builder withBindings(Map<String, String> bindings) {
      this.bindings = bindings == null ? Map.of() : bindings;
      return this;
    }

    public ExecutionResult build() {
      return new ExecutionResult(stdout, stderr, exitCode, submitted, bindings);
    }
  }
}
