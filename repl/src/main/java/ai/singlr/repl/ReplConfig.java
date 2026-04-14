/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

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
 */
public record ReplConfig(
    SandboxFactory sandboxFactory,
    Duration executionTimeout,
    int maxConcurrentSessions,
    List<HostFunction> hostFunctions) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max concurrent sessions. */
  public static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 50;

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

    public ReplConfig build() {
      return new ReplConfig(sandboxFactory, executionTimeout, maxConcurrentSessions, hostFunctions);
    }
  }
}
