/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * Receives completed, immutable traces. Implementations should not throw exceptions; any exception
 * thrown by a listener will be caught and will not prevent other listeners from being notified.
 */
@FunctionalInterface
public interface TraceListener {

  /**
   * Called synchronously when a trace completes (either successfully or with failure).
   *
   * @param trace the completed trace
   */
  void onTrace(Trace trace);
}
