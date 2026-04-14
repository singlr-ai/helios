/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.ReplException;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcChannel;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JVM subprocess sandbox. Launches a child JVM process that reads JSON-RPC execute requests on
 * stdin and returns results on stdout. Host function calls from the sandbox flow back through the
 * same channel.
 *
 * <p>The subprocess is started with the {@code repl-bootstrap} module (designed separately). For
 * unit testing, a mock process can be injected via the package-private constructor.
 */
public final class JvmSandbox implements Sandbox {

  private static final Logger LOG = Logger.getLogger(JvmSandbox.class.getName());

  private final Process process;
  private final ProcessTransport transport;
  private final RpcChannel channel;
  private final JvmSandboxConfig config;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Create a sandbox wrapping an existing process. Used by the factory method and for testing.
   *
   * @param process the subprocess
   * @param transport the transport over the subprocess streams
   * @param channel the RPC channel
   * @param config the sandbox configuration
   */
  JvmSandbox(
      Process process, ProcessTransport transport, RpcChannel channel, JvmSandboxConfig config) {
    this.process = process;
    this.transport = transport;
    this.channel = channel;
    this.config = config;
  }

  /**
   * Create a JVM sandbox factory with the given configuration.
   *
   * @param config the sandbox configuration
   * @return a factory that creates JVM sandboxes
   */
  public static SandboxFactory factory(JvmSandboxConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config must not be null");
    }
    return registry -> create(config, registry);
  }

  /**
   * Create a JVM sandbox factory with default configuration.
   *
   * @return a factory that creates JVM sandboxes
   */
  public static SandboxFactory factory() {
    return factory(JvmSandboxConfig.defaults());
  }

  /**
   * Create and start a new JVM subprocess sandbox.
   *
   * @param config the sandbox configuration
   * @param registry the host function registry
   * @return a running sandbox
   */
  static JvmSandbox create(JvmSandboxConfig config, HostFunctionRegistry registry) {
    try {
      var javaHome = System.getProperty("java.home");
      var javaBin = javaHome + "/bin/java";
      var pb =
          new ProcessBuilder(
              javaBin,
              "-Xmx" + config.maxHeapMb() + "m",
              "--enable-preview",
              "-cp",
              System.getProperty("java.class.path"),
              "ai.singlr.repl.sandbox.JvmSandboxBootstrap");
      pb.redirectErrorStream(false);
      var process = pb.start();
      var processTransport =
          new ProcessTransport(process.getInputStream(), process.getOutputStream());
      var rpcChannel = new RpcChannel(processTransport, registry, config.callTimeout());
      registry.freeze();
      return new JvmSandbox(process, processTransport, rpcChannel, config);
    } catch (IOException e) {
      throw new ReplException("Failed to start JVM sandbox subprocess", e);
    }
  }

  @Override
  public ExecutionResult execute(ExecutionRequest request) {
    if (!isAlive()) {
      return ExecutionResult.failure("Sandbox process is not alive");
    }
    var timeout = request.timeout() != null ? request.timeout() : config.executionTimeout();
    try {
      var params =
          Map.<String, Object>of(
              "code", request.code(),
              "language", request.language(),
              "timeoutMs", timeout.toMillis());
      var result = channel.call("execute", params);
      var stdout = transport.drainStdout();
      return toExecutionResult(result, stdout);
    } catch (RpcChannel.RpcException e) {
      var stdout = transport.drainStdout();
      return ExecutionResult.newBuilder()
          .withStdout(stdout)
          .withStderr(e.getMessage())
          .withExitCode(1)
          .build();
    }
  }

  @Override
  public boolean isAlive() {
    return !closed.get() && process.isAlive();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      channel.close();
      process.destroyForcibly();
      try {
        process.waitFor(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.log(Level.FINE, "Interrupted while waiting for sandbox process to exit", e);
      }
    }
  }

  /** Access the underlying process (for testing). */
  Process process() {
    return process;
  }

  /** Access the transport (for testing). */
  ProcessTransport transport() {
    return transport;
  }

  /** Access the RPC channel (for testing). */
  RpcChannel channel() {
    return channel;
  }

  @SuppressWarnings("unchecked")
  private static ExecutionResult toExecutionResult(Object result, String capturedStdout) {
    if (result instanceof Map<?, ?> map) {
      var stdout = map.get("stdout") instanceof String s ? s : "";
      var stderr = map.get("stderr") instanceof String s ? s : "";
      var exitCode = map.get("exitCode") instanceof Number n ? n.intValue() : 0;
      var submitted = map.get("submitted");
      var combinedStdout =
          capturedStdout.isEmpty()
              ? stdout
              : stdout.isEmpty() ? capturedStdout : capturedStdout + "\n" + stdout;
      return new ExecutionResult(combinedStdout, stderr, exitCode, submitted);
    }
    return ExecutionResult.success(
        capturedStdout.isEmpty() ? String.valueOf(result) : capturedStdout);
  }
}
