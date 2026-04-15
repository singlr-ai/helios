/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcError;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SourceCodeAnalysis;

/**
 * JShell subprocess entry point. Reads JSON-RPC execute requests on stdin, evaluates Java code via
 * JShell with {@link jdk.jshell.execution.LocalExecutionControl LocalExecutionControl}, and returns
 * structured results on stdout. Host function calls from sandbox code flow back through the same
 * stdin/stdout channel using reverse RPC.
 *
 * <p>Threading model:
 *
 * <ul>
 *   <li>Main thread runs {@link #readLoop()} — reads stdin, dispatches requests, routes responses
 *   <li>Virtual thread per execute — JShell eval with stdout/stderr capture
 *   <li>Sandbox code calling {@link HostBridge#predict} blocks on a {@link CompletableFuture} until
 *       the main thread routes the host response
 * </ul>
 *
 * <p>Only one execute may run at a time. {@code System.out}/{@code System.err} are redirected to
 * capture buffers during eval — concurrent executes would corrupt each other's streams. A {@link
 * Semaphore} enforces this invariant; the host side ({@link ai.singlr.repl.protocol.RpcChannel#call
 * RpcChannel.call}) also serializes naturally by blocking until each response arrives.
 */
public final class JvmSandboxBootstrap {

  private static final long CALL_TIMEOUT_MS = 300_000;

  private static volatile JvmSandboxBootstrap instance;

  private final JShell jshell;
  private final BufferedReader stdinReader;
  private final PrintStream realOut;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCallbacks =
      new ConcurrentHashMap<>();
  private final AtomicLong idCounter = new AtomicLong(0);
  private final Semaphore executeLock = new Semaphore(1);
  private volatile Object submittedValue;

  JvmSandboxBootstrap(JShell jshell, BufferedReader stdinReader, PrintStream realOut) {
    this.jshell = jshell;
    this.stdinReader = stdinReader;
    this.realOut = realOut;
  }

  /** Subprocess entry point. */
  public static void main(String[] args) {
    var realOut = System.out;
    var jshell = JShell.builder().executionEngine("local").build();
    jshell.eval("import static ai.singlr.repl.sandbox.HostBridge.*;");
    jshell.eval("import ai.singlr.repl.sandbox.HostBridge;");

    var stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    var bootstrap = new JvmSandboxBootstrap(jshell, stdinReader, realOut);
    setInstance(bootstrap);

    bootstrap.readLoop();

    jshell.close();
  }

  static JvmSandboxBootstrap instance() {
    return instance;
  }

  static void setInstance(JvmSandboxBootstrap inst) {
    instance = inst;
  }

  void readLoop() {
    try {
      String line;
      while ((line = stdinReader.readLine()) != null) {
        RpcMessage message;
        try {
          message = ProcessTransport.deserializeMessage(line);
        } catch (Exception e) {
          try {
            sendRpc(
                new RpcMessage.ErrorResponse(
                    null, RpcError.of(RpcError.PARSE_ERROR, e.getMessage())));
          } catch (IOException sendErr) {
            // Cannot send parse error response — ignore
          }
          continue;
        }
        dispatch(message);
      }
    } catch (IOException e) {
      // stdin closed or read error — exit gracefully
    } finally {
      pendingCallbacks.forEach(
          (id, future) ->
              future.completeExceptionally(new RuntimeException("Sandbox stdin closed")));
      pendingCallbacks.clear();
    }
  }

  Map<String, Object> handleExecute(Map<String, Object> params) {
    if (!executeLock.tryAcquire()) {
      var error = new LinkedHashMap<String, Object>();
      error.put("stdout", "");
      error.put("stderr", "Concurrent execution rejected — only one execute may run at a time");
      error.put("exitCode", 1);
      error.put("submitted", null);
      return error;
    }
    try {
      return doExecute(params);
    } finally {
      executeLock.release();
    }
  }

  void sendRpc(RpcMessage message) throws IOException {
    var json = ProcessTransport.serializeMessage(message);
    synchronized (realOut) {
      realOut.print(ProcessTransport.RPC_PREFIX + json + "\n");
      realOut.flush();
    }
  }

  Object callHost(String method, Map<String, Object> params) {
    var id = "sub-" + idCounter.incrementAndGet();
    var future = new CompletableFuture<Object>();
    pendingCallbacks.put(id, future);
    try {
      sendRpc(new RpcMessage.Request(id, method, params));
      return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      throw new RuntimeException("Failed to send host call", e);
    } catch (TimeoutException e) {
      throw new RuntimeException("Host call timed out after " + CALL_TIMEOUT_MS + "ms", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Host call interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Host call failed: " + e.getCause().getMessage(), e.getCause());
    } finally {
      pendingCallbacks.remove(id);
    }
  }

  void setSubmittedValue(Object value) {
    this.submittedValue = value;
  }

  Object submittedValue() {
    return submittedValue;
  }

  @SuppressWarnings("unchecked")
  void dispatch(RpcMessage message) {
    switch (message) {
      case RpcMessage.Request req -> {
        if ("execute".equals(req.method())) {
          Thread.ofVirtual()
              .name("jshell-execute")
              .start(
                  () -> {
                    try {
                      var params =
                          req.params() instanceof Map<?, ?> m
                              ? (Map<String, Object>) m
                              : Map.<String, Object>of();
                      var result = handleExecute(params);
                      sendRpc(new RpcMessage.Response(req.id(), result));
                    } catch (Exception e) {
                      try {
                        sendRpc(
                            new RpcMessage.ErrorResponse(
                                req.id(), RpcError.internalError(e.getMessage())));
                      } catch (IOException sendErr) {
                        // Cannot send error response
                      }
                    }
                  });
        } else {
          try {
            sendRpc(new RpcMessage.ErrorResponse(req.id(), RpcError.methodNotFound(req.method())));
          } catch (IOException e) {
            // Cannot send error response
          }
        }
      }
      case RpcMessage.Response resp -> {
        var future = pendingCallbacks.remove(resp.id());
        if (future != null) {
          future.complete(resp.result());
        }
      }
      case RpcMessage.ErrorResponse err -> {
        var future = err.id() != null ? pendingCallbacks.remove(err.id()) : null;
        if (future != null) {
          future.completeExceptionally(
              new RuntimeException(
                  "Host error [" + err.error().code() + "]: " + err.error().message()));
        }
      }
      case RpcMessage.Notification _ -> {
        // Notifications ignored
      }
    }
  }

  private Map<String, Object> doExecute(Map<String, Object> params) {
    var code = params.get("code") instanceof String s ? s : "";
    var timeoutMs = params.get("timeoutMs") instanceof Number n ? n.longValue() : 30000L;

    submittedValue = null;

    var stdoutCapture = new ByteArrayOutputStream();
    var stderrCapture = new ByteArrayOutputStream();
    var captureOut = new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8);
    var captureErr = new PrintStream(stderrCapture, true, StandardCharsets.UTF_8);

    var originalOut = System.out;
    var originalErr = System.err;
    var exitCode = new AtomicInteger(0);

    var evalThread =
        Thread.ofVirtual()
            .name("jshell-eval")
            .start(
                () -> {
                  System.setOut(captureOut);
                  System.setErr(captureErr);
                  try {
                    if (!evalCode(code, captureOut, captureErr)) {
                      exitCode.set(1);
                    }
                  } catch (Exception e) {
                    captureErr.println(e.getMessage());
                    exitCode.set(1);
                  } finally {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                  }
                });

    try {
      evalThread.join(Duration.ofMillis(timeoutMs));
      if (evalThread.isAlive()) {
        evalThread.interrupt();
        evalThread.join(Duration.ofMillis(1000));
        captureErr.println("Execution timed out");
        exitCode.set(1);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exitCode.set(1);
    }

    captureOut.flush();
    captureErr.flush();

    var result = new LinkedHashMap<String, Object>();
    result.put("stdout", stdoutCapture.toString(StandardCharsets.UTF_8));
    result.put("stderr", stderrCapture.toString(StandardCharsets.UTF_8));
    result.put("exitCode", exitCode.get());
    result.put("submitted", submittedValue);
    return result;
  }

  private boolean evalCode(String code, PrintStream out, PrintStream err) {
    var analysis = jshell.sourceCodeAnalysis();
    var remaining = code;
    var success = true;

    while (!remaining.isEmpty()) {
      var info = analysis.analyzeCompletion(remaining);
      if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) {
        break;
      }
      var events = jshell.eval(info.source());

      for (var event : events) {
        if (event.status() == Snippet.Status.REJECTED) {
          jshell.diagnostics(event.snippet()).forEach(d -> err.println(d.getMessage(null)));
          success = false;
        }
        if (event.exception() != null) {
          event.exception().printStackTrace(err);
          success = false;
        }
        if (event.value() != null
            && (event.snippet().subKind() == Snippet.SubKind.TEMP_VAR_EXPRESSION_SUBKIND
                || event.snippet().kind() == Snippet.Kind.EXPRESSION)) {
          out.println(event.value());
        }
      }

      remaining = info.remaining();
    }

    return success;
  }
}
