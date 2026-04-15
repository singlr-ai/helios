/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import jdk.jshell.JShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HostBridgeTest {

  @AfterEach
  void tearDown() {
    JvmSandboxBootstrap.setInstance(null);
  }

  @Test
  void predictWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.predict("instructions", "input"));
  }

  @Test
  void submitWithNoBootstrapThrows() {
    assertThrows(IllegalStateException.class, () -> HostBridge.submit("value"));
  }

  @Test
  void predictDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("Be concise", "2+2?"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      assertTrue(line.startsWith(ProcessTransport.RPC_PREFIX));
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.Request.class, msg);
      var req = (RpcMessage.Request) msg;
      assertEquals("predict", req.method());

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("output", "4"))));

      assertEquals("4", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void predictWithNonMapResult() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), "plain")));

      assertEquals("plain", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void submitDelegatesToBootstrap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  HostBridge.submit("answer");
                  resultFuture.complete(null);
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;
      assertEquals("submit", req.method());

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("status", "accepted"))));

      resultFuture.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void submitStoresValue() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  HostBridge.submit("stored-value");
                  resultFuture.complete(null);
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(req.id(), Map.of("status", "accepted"))));

      resultFuture.get(5, TimeUnit.SECONDS);

      assertEquals("stored-value", env.bootstrap.submittedValue());
    }
  }

  @Test
  void predictWithNullOutputInMap() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(
                  req.id(),
                  new java.util.HashMap<String, Object>() {
                    {
                      put("output", null);
                    }
                  })));

      assertEquals("", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void predictWithNullResult() throws Exception {
    try (var env = new BootstrapEnv()) {
      var resultFuture = new CompletableFuture<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  resultFuture.complete(HostBridge.predict("instruct", "input"));
                } catch (Exception e) {
                  resultFuture.completeExceptionally(e);
                }
              });

      var line = env.readLine();
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      var req = (RpcMessage.Request) msg;

      env.writeLine(ProcessTransport.serializeMessage(new RpcMessage.Response(req.id(), null)));

      assertEquals("", resultFuture.get(5, TimeUnit.SECONDS));
    }
  }

  private static class BootstrapEnv implements AutoCloseable {
    final JShell jshell;
    final JvmSandboxBootstrap bootstrap;
    final PipedOutputStream stdinWriter;
    final BufferedReader stdoutBufReader;
    final Thread readLoopThread;

    BootstrapEnv() throws Exception {
      jshell = JShell.builder().executionEngine("local").build();
      var targetClasses = java.nio.file.Path.of("target", "classes").toAbsolutePath();
      if (java.nio.file.Files.isDirectory(targetClasses)) {
        jshell.addToClasspath(targetClasses.toString());
      }
      stdinWriter = new PipedOutputStream();
      var stdinPipe = new PipedInputStream(stdinWriter);
      var stdoutPipe = new PipedOutputStream();
      var stdoutReader = new PipedInputStream(stdoutPipe);
      var realOut = new PrintStream(stdoutPipe, true, StandardCharsets.UTF_8);
      var stdinBufReader =
          new BufferedReader(new InputStreamReader(stdinPipe, StandardCharsets.UTF_8));
      bootstrap = new JvmSandboxBootstrap(jshell, stdinBufReader, realOut);
      JvmSandboxBootstrap.setInstance(bootstrap);
      stdoutBufReader =
          new BufferedReader(new InputStreamReader(stdoutReader, StandardCharsets.UTF_8));
      readLoopThread = Thread.ofVirtual().name("test-readloop").start(bootstrap::readLoop);
    }

    String readLine() throws Exception {
      return stdoutBufReader.readLine();
    }

    void writeLine(String json) throws Exception {
      stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
      stdinWriter.flush();
    }

    @Override
    public void close() throws Exception {
      stdinWriter.close();
      readLoopThread.join(Duration.ofSeconds(2));
      jshell.close();
    }
  }
}
