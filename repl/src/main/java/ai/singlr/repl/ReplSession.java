/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.host.SubmitFunction;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stateful REPL session backed by a sandbox. Each session has its own sandbox instance, host
 * function registry, and execution history.
 *
 * <p>Sessions are bound to a single agent run and must be closed when done to release sandbox
 * resources and the concurrency semaphore permit.
 */
public final class ReplSession implements AutoCloseable {

  private final ReplConfig config;
  private final Sandbox sandbox;
  private final HostFunctionRegistry registry;
  private final AtomicReference<Object> submittedValue = new AtomicReference<>();
  private final List<ExecutionResult> history = new ArrayList<>();
  private final Semaphore semaphore;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private ReplSession(
      ReplConfig config, Sandbox sandbox, HostFunctionRegistry registry, Semaphore semaphore) {
    this.config = config;
    this.sandbox = sandbox;
    this.registry = registry;
    this.semaphore = semaphore;
  }

  /**
   * Create a new session from the given config, acquiring a semaphore permit.
   *
   * @param config the REPL configuration
   * @param semaphore the concurrency limiter
   * @return a new session ready for code execution
   * @throws ReplException if no permits are available or sandbox creation fails
   */
  public static ReplSession create(ReplConfig config, Semaphore semaphore) {
    if (config == null) {
      throw new IllegalArgumentException("Config must not be null");
    }
    if (semaphore == null) {
      throw new IllegalArgumentException("Semaphore must not be null");
    }
    if (!semaphore.tryAcquire()) {
      throw new ReplException(
          "Max concurrent sessions reached (" + (semaphore.availablePermits()) + " available)");
    }
    try {
      var registry = new HostFunctionRegistry();
      for (var fn : config.hostFunctions()) {
        registry.register(fn);
      }
      var sandbox = config.sandboxFactory().create(registry);
      return new ReplSession(config, sandbox, registry, semaphore);
    } catch (Exception e) {
      semaphore.release();
      if (e instanceof ReplException re) {
        throw re;
      }
      throw new ReplException("Failed to create session", e);
    }
  }

  /**
   * Execute code in the sandbox.
   *
   * @param code the source code to execute
   * @return the execution result
   * @throws ReplException if the session is closed or the sandbox is dead
   */
  public ExecutionResult execute(String code) {
    if (closed.get()) {
      throw new ReplException("Session is closed");
    }
    if (!sandbox.isAlive()) {
      throw new ReplException("Sandbox is no longer alive");
    }
    var request =
        ExecutionRequest.newBuilder().withCode(code).withTimeout(config.executionTimeout()).build();
    var result = sandbox.execute(request);
    history.add(result);
    return result;
  }

  /**
   * The value passed to {@code submit()} from sandbox code, or {@code null} if not yet called.
   *
   * @return the submitted output
   */
  public Object submittedOutput() {
    return submittedValue.get();
  }

  /**
   * The submit holder for use by {@link SubmitFunction}.
   *
   * @return the atomic reference for the submitted value
   */
  public AtomicReference<Object> submitHolder() {
    return submittedValue;
  }

  /**
   * The execution history for this session.
   *
   * @return unmodifiable list of execution results
   */
  public List<ExecutionResult> history() {
    return Collections.unmodifiableList(history);
  }

  /** The host function registry for this session. */
  public HostFunctionRegistry registry() {
    return registry;
  }

  /** Whether this session is still open. */
  public boolean isOpen() {
    return !closed.get() && sandbox.isAlive();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        sandbox.close();
      } finally {
        semaphore.release();
      }
    }
  }
}
