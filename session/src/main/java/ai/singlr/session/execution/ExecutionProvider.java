/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.runtime.CancellationToken;
import java.util.concurrent.CompletionStage;

/**
 * Dispatch surface the {@code Execute} tool calls into to actually run a request. Implementations
 * range from "refuses everything" ({@link NoopExecutionProvider}) through "host-process subprocess
 * with a {@code CommandGrant} per runtime" ({@code LocalProcessExecutionProvider}) to "spawn an
 * Incus instance, run the script, dispose the instance" ({@code IncusExecutionProvider}, ships
 * separately).
 *
 * <p>One {@code Execute} tool dispatches every runtime — providers route by {@link
 * ExecutionRequest#runtime()}. A provider must declare its supported runtimes via {@link
 * #capabilities()}; the {@code Execute} tool refuses requests for unsupported runtimes before
 * calling {@code execute(...)}.
 *
 * <p>{@code execute(...)} is asynchronous so providers that block on I/O (Incus boot, JDBC,
 * subprocess wait) need not pin a thread. The returned {@link CompletionStage} must complete with a
 * non-null {@link ExecutionResult}; exceptional completion is acceptable for unrecoverable provider
 * errors (e.g. the binary isn't installed) and the {@code Execute} tool surfaces them as
 * tool-failures. The {@link CancellationToken} is cooperative — providers should call {@link
 * CancellationToken#throwIfCancelled()} at safe points (before fork, between drain rounds) so a
 * session shutdown halts the in-flight work promptly.
 *
 * <p>Implementations are non-sealed: customers will plug their own (Incus, custom remote runners),
 * so the surface stays open for extension.
 *
 * <h2>Thread-safety</h2>
 *
 * Providers must be safe for concurrent calls — the session loop is fully serial today, but the
 * surrounding {@link ai.singlr.session.tools.ToolCategory#EXECUTION EXECUTION} category has its own
 * dedicated concurrency cap in {@link ai.singlr.session.ConcurrencyLimits}, and future loops may
 * dispatch parallel execute tool calls.
 */
public interface ExecutionProvider {

  /**
   * Capabilities advertised by this provider. Stable for the lifetime of the provider — providers
   * should compute this once at construction and return a constant.
   *
   * @return non-null capabilities record
   */
  ExecutionCapabilities capabilities();

  /**
   * Execute one request. The returned future must complete with a non-null {@link ExecutionResult}
   * for any reasonably-handled outcome (success, non-zero exit, timeout, refused runtime). Throw
   * exceptionally only when the provider itself is broken (binary missing, internal misconfig); the
   * {@code Execute} tool surfaces those as tool failures.
   *
   * @param request the request to execute; non-null
   * @param cancellation cooperative cancellation token; non-null. Providers should poll {@link
   *     CancellationToken#throwIfCancelled()} at safe points and complete the returned future
   *     exceptionally with a {@link java.util.concurrent.CancellationException} when the token
   *     fires
   * @return a completion stage that yields the result
   */
  CompletionStage<ExecutionResult> execute(
      ExecutionRequest request, CancellationToken cancellation);
}
