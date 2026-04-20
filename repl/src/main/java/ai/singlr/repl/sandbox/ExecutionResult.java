/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

/**
 * Result of executing code in a sandbox.
 *
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param exitCode the exit code (0 = success)
 * @param submitted the value passed to {@code submit()}, or {@code null} if not called
 */
public record ExecutionResult(String stdout, String stderr, int exitCode, Object submitted) {

  public ExecutionResult {
    if (stdout == null) {
      stdout = "";
    }
    if (stderr == null) {
      stderr = "";
    }
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

    public ExecutionResult build() {
      return new ExecutionResult(stdout, stderr, exitCode, submitted);
    }
  }
}
