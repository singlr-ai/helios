/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.time.Duration;

/**
 * Configuration for the JVM subprocess sandbox.
 *
 * @param executionTimeout default timeout for code execution
 * @param maxHeapMb maximum heap size for the subprocess in MB
 * @param callTimeout timeout for JSON-RPC calls (host function responses)
 */
public record JvmSandboxConfig(Duration executionTimeout, int maxHeapMb, Duration callTimeout) {

  /** Default execution timeout: 30 seconds. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

  /** Default max heap: 256 MB. */
  public static final int DEFAULT_MAX_HEAP_MB = 256;

  /** Default JSON-RPC call timeout: 60 seconds. */
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);

  public JvmSandboxConfig {
    if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
      throw new IllegalArgumentException("Execution timeout must be positive");
    }
    if (maxHeapMb <= 0) {
      throw new IllegalArgumentException("Max heap must be positive");
    }
    if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
      throw new IllegalArgumentException("Call timeout must be positive");
    }
  }

  /** Create a default configuration. */
  public static JvmSandboxConfig defaults() {
    return new JvmSandboxConfig(
        DEFAULT_EXECUTION_TIMEOUT, DEFAULT_MAX_HEAP_MB, DEFAULT_CALL_TIMEOUT);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maxHeapMb = DEFAULT_MAX_HEAP_MB;
    private Duration callTimeout = DEFAULT_CALL_TIMEOUT;

    private Builder() {}

    public Builder withExecutionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    public Builder withMaxHeapMb(int maxHeapMb) {
      this.maxHeapMb = maxHeapMb;
      return this;
    }

    public Builder withCallTimeout(Duration callTimeout) {
      this.callTimeout = callTimeout;
      return this;
    }

    public JvmSandboxConfig build() {
      return new JvmSandboxConfig(executionTimeout, maxHeapMb, callTimeout);
    }
  }
}
