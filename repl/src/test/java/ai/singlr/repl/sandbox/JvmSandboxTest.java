/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcChannel;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JvmSandboxTest {

  @Test
  void factoryWithNullConfigThrows() {
    assertThrows(IllegalArgumentException.class, () -> JvmSandbox.factory(null));
  }

  @Test
  void factoryCreatesNonNull() {
    var factory = JvmSandbox.factory(JvmSandboxConfig.defaults());
    assertNotNull(factory);
  }

  @Test
  void defaultFactoryCreatesNonNull() {
    var factory = JvmSandbox.factory();
    assertNotNull(factory);
  }

  @Test
  void executeOnDeadSandboxReturnsFailure() throws Exception {
    var pb = new ProcessBuilder("true");
    var process = pb.start();
    process.waitFor();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertFalse(result.hasTypeSuccess());
    assertTrue(result.stderr().contains("not alive"));

    sandbox.close();
  }

  @Test
  void closeDestroysProcess() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertTrue(sandbox.isAlive());

    sandbox.close();

    assertFalse(sandbox.isAlive());
    assertFalse(process.isAlive());
  }

  @Test
  void doubleCloseIsSafe() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    sandbox.close();
    sandbox.close();

    assertFalse(sandbox.isAlive());
  }

  @Test
  void accessors() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertEquals(process, sandbox.process());
    assertEquals(transport, sandbox.transport());
    assertEquals(channel, sandbox.channel());

    sandbox.close();
  }

  @Test
  void executeWithRequestTimeout() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofMillis(100));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result =
        sandbox.execute(
            ExecutionRequest.newBuilder()
                .withCode("1+1")
                .withTimeout(Duration.ofMillis(100))
                .build());

    assertEquals(1, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeWithDefaultTimeout() throws Exception {
    // When request has no timeout, the sandbox config timeout is used
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofMillis(100));
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofMillis(100)).build();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    // Request with null timeout — should use config default
    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertEquals(1, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeSuccessWithMapResult() throws Exception {
    // Simulate a process that responds with a proper RPC response containing a Map result
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    // Virtual thread to simulate the sandbox responding
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                // Read the request from stdin (we need to consume it to unblock)
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  // Parse the request to get the id
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write a response with RPC prefix
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", "hello world", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("println(\"hello world\")"));

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("hello world"));

    sandbox.close();
  }

  @Test
  void executeSuccessWithNonMapResult() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(req.id(), "just a string"));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    assertEquals(0, result.exitCode());
    assertEquals("just a string", result.stdout());

    sandbox.close();
  }

  @Test
  void executeSuccessWithCapturedStdoutAndMapResult() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write some non-RPC output first (captured stdout), then the RPC response
                    processStdout.write("print output\n".getBytes(StandardCharsets.UTF_8));
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", "rpc stdout", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("println()"));

    assertEquals(0, result.exitCode());
    // Combined: captured stdout + rpc stdout
    assertTrue(result.stdout().contains("print output"));
    assertTrue(result.stdout().contains("rpc stdout"));

    sandbox.close();
  }

  @Test
  void executeSuccessMapWithSubmitted() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of(
                                    "stdout",
                                    "",
                                    "stderr",
                                    "warning",
                                    "exitCode",
                                    1,
                                    "submitted",
                                    "answer")));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("code"));

    assertEquals(1, result.exitCode());
    assertEquals("warning", result.stderr());
    assertEquals("answer", result.submitted());

    sandbox.close();
  }

  @Test
  void executeSuccessWithCapturedStdoutOnlyEmptyMapStdout() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    processStdout.write("captured line\n".getBytes(StandardCharsets.UTF_8));
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(), Map.of("stdout", "", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    assertEquals(0, result.exitCode());
    assertEquals("captured line", result.stdout());

    sandbox.close();
  }

  @Test
  void executeSuccessMapWithNonStringFields() throws Exception {
    // Cover branches where map values are NOT String/Number (fallback to defaults)
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Return a map with non-standard types for stdout/stderr/exitCode
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", 123, "stderr", true, "exitCode", "not-a-number")));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    // Non-string stdout/stderr should default to ""
    assertEquals("", result.stdout());
    assertEquals("", result.stderr());
    // Non-number exitCode should default to 0
    assertEquals(0, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeSuccessNonMapResultWithCapturedStdout() throws Exception {
    // Cover the branch: capturedStdout is not empty AND result is not a Map
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write captured stdout first
                    processStdout.write("captured output\n".getBytes(StandardCharsets.UTF_8));
                    // Return a non-map result (a string)
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(req.id(), "plain result"));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    // When captured stdout is not empty and result is not a Map, capturedStdout wins
    assertEquals("captured output", result.stdout());
    assertEquals(0, result.exitCode());

    sandbox.close();
  }

  @Test
  void createStaticMethodLaunchesSubprocess() {
    // The create() method tries to start a JVM subprocess with the bootstrap class
    // which doesn't exist yet, so it will fail - but we exercise the code path
    var config = JvmSandboxConfig.defaults();
    var registry = new HostFunctionRegistry();

    // The bootstrap class doesn't exist, so this will either:
    // 1. Start the process but it will fail immediately (no main class)
    // 2. The channel reader will detect the process died
    // Either way, we cover the create() code path
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      // If it gets here, the process started (but may die soon)
      assertNotNull(sandbox);
      assertTrue(registry.isFrozen());
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }
}
