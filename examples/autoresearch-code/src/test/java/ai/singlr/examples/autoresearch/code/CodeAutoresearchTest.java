/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeAutoresearchTest {

  private static Model stopImmediately(String message) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(message)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "stop";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static void initRepo(Path dir) throws IOException {
    var ws = new GitWorkspace(dir);
    ws.exec(List.of("git", "init", "--quiet"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.email", "t@example.com"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.name", "t"), Duration.ofSeconds(5));
    Files.writeString(dir.resolve("target.txt"), "base\n", StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("bench.sh"), "#!/bin/sh\necho METRIC score=50\n", StandardCharsets.UTF_8);
    ws.exec(List.of("chmod", "+x", "bench.sh"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "add", "."), Duration.ofSeconds(5));
    ws.exec(List.of("git", "commit", "-m", "init"), Duration.ofSeconds(5));
  }

  @Test
  void builderValidatesRequiredFields(@TempDir Path dir) {
    var log = new InMemoryExperimentLog();
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withLog(log)
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withLog(log)
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withLog(log)
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withMetricName("m")
                .withLog(log)
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withLog(log)
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withTask("t")
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withLog(log)
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            CodeAutoresearch.newBuilder()
                .withRepoRoot(dir)
                .withCoachModel(stopImmediately("x"))
                .withFile(Path.of("f"))
                .withBenchmarkCommand(List.of("echo"))
                .withMetricName("m")
                .withLog(log)
                .withTask("t")
                .withMaxIterations(0)
                .build());
  }

  @Test
  void runExecutesToolCalls(@TempDir Path dir) throws IOException {
    initRepo(dir);
    var log = new InMemoryExperimentLog();
    var turns = new AtomicInteger();
    var coach =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            int t = turns.getAndIncrement();
            if (t == 0) {
              return Response.newBuilder()
                  .withContent("")
                  .withToolCalls(
                      List.of(
                          new ToolCall(
                              "w1",
                              "write_file",
                              Map.of("path", "target.txt", "content", "edit\n"))))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            if (t == 1) {
              return Response.newBuilder()
                  .withContent("")
                  .withToolCalls(List.of(new ToolCall("r1", "run_experiment", Map.of())))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            if (t == 2) {
              return Response.newBuilder()
                  .withContent("")
                  .withToolCalls(
                      List.of(
                          new ToolCall(
                              "l1",
                              "log_experiment",
                              Map.of("status", "keep", "description", "first"))))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "coach";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var result =
        CodeAutoresearch.newBuilder()
            .withRepoRoot(dir)
            .withCoachModel(coach)
            .withFile(Path.of("target.txt"))
            .withBenchmarkCommand(List.of("sh", "bench.sh"))
            .withMetricName("score")
            .withBenchmarkTimeout(Duration.ofSeconds(5))
            .withLog(log)
            .withTask("optimize target.txt")
            .withMaxIterations(10)
            .build()
            .run();

    assertEquals(1, log.entries().size());
    assertEquals(ExperimentStatus.KEEP, log.entries().get(0).status());
    assertEquals(50.0, log.entries().get(0).primaryMetric());
    assertEquals("edit\n", Files.readString(dir.resolve("target.txt")));
    assertEquals("done", result.coachFinalMessage());
    assertEquals(40, result.finalHead().length());
  }

  @Test
  void currentHeadReflectsWorkspace(@TempDir Path dir) throws IOException {
    initRepo(dir);
    var optimizer =
        CodeAutoresearch.newBuilder()
            .withRepoRoot(dir)
            .withCoachModel(stopImmediately("done"))
            .withFile(Path.of("target.txt"))
            .withBenchmarkCommand(List.of("sh", "bench.sh"))
            .withMetricName("score")
            .withLog(new InMemoryExperimentLog())
            .withTask("t")
            .build();
    assertTrue(optimizer.bestScore() == null);
    assertEquals(40, optimizer.currentHead().length());
  }
}
