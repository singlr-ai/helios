/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.ConcurrencyLimits;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Dispatch surface for parallel and sequential tool execution.
 *
 * <p>Phase 1 stub. Phase 1 ships text-only session loops with no registered tools, so {@link
 * #dispatch(ToolCall, CancellationToken)} is wired but unimplemented; the loop never calls it.
 * Phase 2 fills the body with the actual semaphore-bounded virtual-thread dispatch over the
 * registered tool list. The class is declared here so the loop wiring (constructor injection,
 * lifecycle), permission caps ({@link ConcurrencyLimits}), and call sites can already be laid out
 * against the eventual API.
 *
 * <p>The class holds three {@link Semaphore}s — one per ConcurrencyLimits axis (general tool calls,
 * file writes, code executions). The semaphores are constructed here so Phase 1 acceptance smoke
 * tests can already verify the loop creates and disposes of them correctly.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. The underlying {@link Semaphore}s synchronise; {@link #limits()} returns an
 * immutable record.
 */
public final class ToolDispatch {

  private final ConcurrencyLimits limits;
  private final Semaphore toolCallPermits;
  private final Semaphore fileWritePermits;
  private final Semaphore executionPermits;

  /**
   * Build a tool dispatch with permits sized per {@code limits}.
   *
   * @param limits concurrency caps; non-null
   * @throws NullPointerException if {@code limits} is null
   */
  public ToolDispatch(ConcurrencyLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.toolCallPermits = new Semaphore(limits.maxConcurrentToolCalls(), true);
    this.fileWritePermits = new Semaphore(limits.maxConcurrentFileWrites(), true);
    this.executionPermits = new Semaphore(limits.maxConcurrentExecutions(), true);
  }

  /**
   * The concurrency caps in force for this dispatch.
   *
   * @return the limits record
   */
  public ConcurrencyLimits limits() {
    return limits;
  }

  /**
   * Number of permits available for general tool calls. Useful for tests and observability.
   *
   * @return available permits
   */
  public int availableToolCallPermits() {
    return toolCallPermits.availablePermits();
  }

  /**
   * Number of permits available for file writes.
   *
   * @return available permits
   */
  public int availableFileWritePermits() {
    return fileWritePermits.availablePermits();
  }

  /**
   * Number of permits available for code executions.
   *
   * @return available permits
   */
  public int availableExecutionPermits() {
    return executionPermits.availablePermits();
  }

  /**
   * Dispatch one tool call. Phase 1 stub: rejects all calls with {@link
   * UnsupportedOperationException} since Phase 1 sessions are text-only. Phase 2 fills the body
   * with the real semaphore-acquire, virtual-thread submit, FT envelope dispatch.
   *
   * @param call the call to dispatch; non-null
   * @param cancellation cooperative cancellation token; non-null
   * @return the tool result (Phase 2)
   * @throws NullPointerException if {@code call} or {@code cancellation} is null
   * @throws UnsupportedOperationException always, until Phase 2 lands
   */
  public ToolResult dispatch(ToolCall call, CancellationToken cancellation) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    throw new UnsupportedOperationException(
        "ToolDispatch.dispatch is unimplemented in Phase 1 (text-only sessions). Tool support "
            + "lands in Phase 2 with the Tool / ToolRegistry / Hook abstractions.");
  }
}
