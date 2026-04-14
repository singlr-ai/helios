/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * NDJSON transport over stdin/stdout streams. JSON-RPC lines from the subprocess are prefixed with
 * a magic marker ({@code \0RPC:}) to distinguish them from regular print output.
 */
public final class ProcessTransport implements RpcTransport {

  /** Magic prefix on JSON-RPC lines from the sandbox process. */
  public static final String RPC_PREFIX = "\0RPC:";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final BufferedReader reader;
  private final BufferedWriter writer;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final StringBuilder stdoutBuffer = new StringBuilder();

  /**
   * Create a transport over the given streams.
   *
   * @param input the stream to read messages from (sandbox stdout)
   * @param output the stream to write messages to (sandbox stdin)
   */
  public ProcessTransport(InputStream input, OutputStream output) {
    if (input == null) {
      throw new IllegalArgumentException("Input stream must not be null");
    }
    if (output == null) {
      throw new IllegalArgumentException("Output stream must not be null");
    }
    this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
  }

  @Override
  public void send(RpcMessage message) throws IOException {
    if (!open.get()) {
      throw new IOException("Transport is closed");
    }
    var json = serializeMessage(message);
    synchronized (writer) {
      writer.write(json);
      writer.newLine();
      writer.flush();
    }
  }

  @Override
  public RpcMessage receive() throws IOException {
    while (open.get()) {
      var line = reader.readLine();
      if (line == null) {
        open.set(false);
        return null;
      }
      if (line.startsWith(RPC_PREFIX)) {
        var json = line.substring(RPC_PREFIX.length());
        return deserializeMessage(json);
      }
      synchronized (stdoutBuffer) {
        if (!stdoutBuffer.isEmpty()) {
          stdoutBuffer.append('\n');
        }
        stdoutBuffer.append(line);
      }
    }
    return null;
  }

  /**
   * Drain and return any non-RPC stdout lines accumulated since the last drain.
   *
   * @return captured stdout text, or empty string if none
   */
  public String drainStdout() {
    synchronized (stdoutBuffer) {
      var result = stdoutBuffer.toString();
      stdoutBuffer.setLength(0);
      return result;
    }
  }

  @Override
  public boolean isOpen() {
    return open.get();
  }

  @Override
  public void close() throws IOException {
    if (open.compareAndSet(true, false)) {
      reader.close();
      writer.close();
    }
  }

  @SuppressWarnings("unchecked")
  public static RpcMessage deserializeMessage(String json) throws IOException {
    var map = MAPPER.readValue(json, Map.class);

    if (map.containsKey("method") && map.containsKey("id")) {
      return new RpcMessage.Request(
          String.valueOf(map.get("id")), (String) map.get("method"), map.get("params"));
    }
    if (map.containsKey("method") && !map.containsKey("id")) {
      return new RpcMessage.Notification((String) map.get("method"), map.get("params"));
    }
    if (map.containsKey("error")) {
      var errorMap = (Map<String, Object>) map.get("error");
      var code = errorMap.get("code") instanceof Number n ? n.intValue() : 0;
      var message = (String) errorMap.get("message");
      var data = errorMap.get("data");
      var id = map.get("id") != null ? String.valueOf(map.get("id")) : null;
      return new RpcMessage.ErrorResponse(id, new RpcError(code, message, data));
    }
    if (map.containsKey("result") || (map.containsKey("id") && !map.containsKey("method"))) {
      return new RpcMessage.Response(String.valueOf(map.get("id")), map.get("result"));
    }
    throw new IOException("Unrecognized JSON-RPC message: " + json);
  }

  public static String serializeMessage(RpcMessage message) throws IOException {
    return switch (message) {
      case RpcMessage.Request req ->
          MAPPER.writeValueAsString(
              Map.of(
                  "jsonrpc",
                  RpcMessage.VERSION,
                  "id",
                  req.id(),
                  "method",
                  req.method(),
                  "params",
                  req.params() != null ? req.params() : Map.of()));
      case RpcMessage.Response resp -> {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("jsonrpc", RpcMessage.VERSION);
        map.put("id", resp.id());
        map.put("result", resp.result());
        yield MAPPER.writeValueAsString(map);
      }
      case RpcMessage.ErrorResponse err -> {
        var errorMap = new java.util.LinkedHashMap<String, Object>();
        errorMap.put("code", err.error().code());
        errorMap.put("message", err.error().message());
        if (err.error().data() != null) {
          errorMap.put("data", err.error().data());
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("jsonrpc", RpcMessage.VERSION);
        map.put("id", err.id());
        map.put("error", errorMap);
        yield MAPPER.writeValueAsString(map);
      }
      case RpcMessage.Notification notif ->
          MAPPER.writeValueAsString(
              Map.of(
                  "jsonrpc",
                  RpcMessage.VERSION,
                  "method",
                  notif.method(),
                  "params",
                  notif.params() != null ? notif.params() : Map.of()));
    };
  }
}
