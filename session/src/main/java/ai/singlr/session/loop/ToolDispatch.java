/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;

/**
 * Synchronous tool dispatch surface. The agent loop calls {@link #dispatch(ToolCall,
 * CancellationToken)} once per tool call the model emits; the dispatcher looks the call up in its
 * {@link ToolRegistry}, acquires the per-{@link ToolCategory category} semaphore (blocking the
 * calling virtual thread when the cap is full), runs the tool, and returns its {@link ToolResult}.
 *
 * <p>{@link ToolCategory#WRITE} tool calls acquire from the {@code fileWritePermits} pool; {@link
 * ToolCategory#EXECUTION} calls acquire from the {@code executionPermits} pool; every other
 * category acquires from the general {@code toolCallPermits} pool. Sizes come from {@link
 * ConcurrencyLimits}.
 *
 * <p>Unknown tool names return a synthetic {@link ToolResult#failure(String) failure}; the agent
 * loop feeds that result back to the model so it can self-correct rather than crash.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable apart from the semaphore counters. Safe to share across the agent loop's threads.
 */
public final class ToolDispatch {

  private final ToolRegistry registry;
  private final ConcurrencyLimits limits;
  private final Semaphore toolCallPermits;
  private final Semaphore fileWritePermits;
  private final Semaphore executionPermits;

  /**
   * Build a dispatcher.
   *
   * @param registry the bindings the dispatcher will look calls up against; non-null
   * @param limits concurrency caps; non-null
   * @throws NullPointerException if any argument is null
   */
  public ToolDispatch(ToolRegistry registry, ConcurrencyLimits limits) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.toolCallPermits = new Semaphore(limits.maxConcurrentToolCalls(), true);
    this.fileWritePermits = new Semaphore(limits.maxConcurrentFileWrites(), true);
    this.executionPermits = new Semaphore(limits.maxConcurrentExecutions(), true);
  }

  /**
   * The bound tool registry.
   *
   * @return non-null registry
   */
  public ToolRegistry registry() {
    return registry;
  }

  /**
   * The concurrency caps in force.
   *
   * @return the limits record
   */
  public ConcurrencyLimits limits() {
    return limits;
  }

  /**
   * Available permits for general tool calls.
   *
   * @return non-negative count
   */
  public int availableToolCallPermits() {
    return toolCallPermits.availablePermits();
  }

  /**
   * Available permits for file writes.
   *
   * @return non-negative count
   */
  public int availableFileWritePermits() {
    return fileWritePermits.availablePermits();
  }

  /**
   * Available permits for code executions.
   *
   * @return non-negative count
   */
  public int availableExecutionPermits() {
    return executionPermits.availablePermits();
  }

  /**
   * Dispatch one tool call. Blocks the calling thread until the appropriate semaphore is available;
   * runs the tool synchronously; returns its result. Cancellation observed before acquire and
   * re-thrown if signalled during the acquire wait.
   *
   * @param call the call; non-null
   * @param cancellation cooperative cancellation token; non-null
   * @return the tool's result, or a synthetic failure if the tool is not registered
   * @throws NullPointerException if either argument is null
   * @throws CancellationException if cancellation fires before or during the permit wait
   */
  public ToolResult dispatch(ToolCall call, CancellationToken cancellation) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    cancellation.throwIfCancelled();

    var bindingOpt = registry.get(call.name());
    if (bindingOpt.isEmpty()) {
      return ToolResult.failure("tool not found: " + call.name());
    }
    var binding = bindingOpt.orElseThrow();
    var permits = permitsFor(binding.category());
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while acquiring permit for " + call.name());
    }
    try {
      return binding.tool().execute(call.arguments());
    } finally {
      permits.release();
    }
  }

  private Semaphore permitsFor(ToolCategory category) {
    return switch (category) {
      case WRITE -> fileWritePermits;
      case EXECUTION -> executionPermits;
      case READ, SEARCH, CONTROL, NETWORK, DELEGATION -> toolCallPermits;
    };
  }
}
