/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.eval.InMemoryExperimentLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeCoachToolsTest {

  private static GitWorkspace initRepo(Path dir) throws IOException {
    var ws = new GitWorkspace(dir);
    ws.exec(List.of("git", "init", "--quiet"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.email", "t@example.com"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.name", "t"), Duration.ofSeconds(5));
    Files.writeString(dir.resolve("target.txt"), "baseline\n", StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("bench.sh"), "#!/bin/sh\necho METRIC score=42\n", StandardCharsets.UTF_8);
    ws.exec(List.of("chmod", "+x", "bench.sh"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "add", "."), Duration.ofSeconds(5));
    ws.exec(List.of("git", "commit", "-m", "init"), Duration.ofSeconds(5));
    return ws;
  }

  @Test
  void readFileInScope(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.readFile().execute(Map.of("path", "target.txt"));
    assertTrue(result.success());
    assertEquals("baseline\n", result.output());
  }

  @Test
  void readFileOutOfScopeRejected(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.readFile().execute(Map.of("path", "bench.sh"));
    assertFalse(result.success());
  }

  @Test
  void readFileRejectsPathTraversal(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.readFile().execute(Map.of("path", "../outside"));
    assertFalse(result.success());
  }

  @Test
  void readFileRejectsSymlinkEscape(@TempDir Path dir) throws IOException {
    var outside = Files.createTempDirectory("escape-");
    try {
      var secret = outside.resolve("secret.txt");
      Files.writeString(secret, "leak-me", StandardCharsets.UTF_8);
      var ws = initRepo(dir);
      Files.createSymbolicLink(dir.resolve("escape"), outside);
      var tools =
          CodeCoachTools.create(
              ws,
              List.of(Path.of("target.txt"), Path.of("escape")),
              List.of("sh", "bench.sh"),
              "score",
              Duration.ofSeconds(5),
              new InMemoryExperimentLog(),
              true,
              new AtomicReference<>());
      var result = tools.readFile().execute(Map.of("path", "escape/secret.txt"));
      assertFalse(result.success(), "symlink-resolved path should be rejected");
    } finally {
      try {
        Files.deleteIfExists(outside.resolve("secret.txt"));
        Files.deleteIfExists(outside);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  @Test
  void writeFileRejectsSymlinkEscape(@TempDir Path dir) throws IOException {
    var outside = Files.createTempDirectory("escape-");
    try {
      var ws = initRepo(dir);
      Files.createSymbolicLink(dir.resolve("escape"), outside);
      var tools =
          CodeCoachTools.create(
              ws,
              List.of(Path.of("target.txt"), Path.of("escape")),
              List.of("sh", "bench.sh"),
              "score",
              Duration.ofSeconds(5),
              new InMemoryExperimentLog(),
              true,
              new AtomicReference<>());
      var result = tools.writeFile().execute(Map.of("path", "escape/planted.txt", "content", "x"));
      assertFalse(result.success(), "writing through symlink should be rejected");
      assertFalse(
          Files.exists(outside.resolve("planted.txt")), "file must not be created outside scope");
    } finally {
      try {
        Files.walk(outside)
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                    // best effort
                  }
                });
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  @Test
  void writeFilePersistsInWorkingTree(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    tools.writeFile().execute(Map.of("path", "target.txt", "content", "new body"));
    assertEquals("new body", Files.readString(dir.resolve("target.txt")));
  }

  @Test
  void writeFileRejectsOutOfScope(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.writeFile().execute(Map.of("path", "evil.txt", "content", "x"));
    assertFalse(result.success());
  }

  @Test
  void writeFileRejectsMissingContent(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.writeFile().execute(Map.of("path", "target.txt"));
    assertFalse(result.success());
  }

  @Test
  void runExperimentParsesMetric(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.runExperiment().execute(Map.of());
    assertTrue(result.output().contains("score=42"));
  }

  @Test
  void runExperimentReportsMissingMetric(@TempDir Path dir) throws IOException {
    var ws = new GitWorkspace(dir);
    ws.exec(List.of("git", "init", "--quiet"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.email", "t@example.com"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.name", "t"), Duration.ofSeconds(5));
    Files.writeString(dir.resolve("x"), "x", StandardCharsets.UTF_8);
    ws.exec(List.of("git", "add", "."), Duration.ofSeconds(5));
    ws.exec(List.of("git", "commit", "-m", "init"), Duration.ofSeconds(5));
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("x")),
            List.of("sh", "-c", "echo hi"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.runExperiment().execute(Map.of());
    assertTrue(result.output().contains("no METRIC"));
  }

  @Test
  void logExperimentKeepCommits(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var log = new InMemoryExperimentLog();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            log,
            true,
            new AtomicReference<>());
    var headBefore = ws.snapshot();
    tools.writeFile().execute(Map.of("path", "target.txt", "content", "edit"));
    tools.runExperiment().execute(Map.of());
    tools.logExperiment().execute(Map.of("status", "keep", "description", "test edit"));
    assertEquals(ExperimentStatus.KEEP, log.entries().get(0).status());
    var headAfter = ws.snapshot();
    assertTrue(!headBefore.equals(headAfter), "head should advance on keep");
  }

  @Test
  void logExperimentDiscardReverts(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var log = new InMemoryExperimentLog();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            log,
            true,
            new AtomicReference<>());
    var headBefore = ws.snapshot();
    tools.writeFile().execute(Map.of("path", "target.txt", "content", "worse"));
    tools.runExperiment().execute(Map.of());
    tools.logExperiment().execute(Map.of("status", "discard", "description", "worse edit"));
    assertEquals(ExperimentStatus.DISCARD, log.entries().get(0).status());
    assertEquals(headBefore, ws.snapshot());
    assertEquals("baseline\n", Files.readString(dir.resolve("target.txt")));
  }

  @Test
  void logExperimentRejectsUnknownStatus(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    var result = tools.logExperiment().execute(Map.of("status", "bogus", "description", "x"));
    assertFalse(result.success());
  }

  @Test
  void logExperimentTracksAsi(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var log = new InMemoryExperimentLog();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            log,
            true,
            new AtomicReference<>());
    tools.writeFile().execute(Map.of("path", "target.txt", "content", "e"));
    tools.runExperiment().execute(Map.of());
    tools
        .logExperiment()
        .execute(
            Map.of(
                "status",
                "keep",
                "description",
                "d",
                "asi",
                Map.of("theory", "shorter wins", "touches", 1)));
    var asi = log.entries().get(0).asi();
    assertEquals("shorter wins", asi.get("theory"));
    assertEquals("1", asi.get("touches"));
  }

  @Test
  void logExperimentUpdatesBestScore(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var bestScore = new AtomicReference<Double>();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            bestScore);
    tools.runExperiment().execute(Map.of());
    tools.logExperiment().execute(Map.of("status", "keep", "description", "d"));
    assertEquals(42.0, bestScore.get());
  }

  @Test
  void showLogReturnsEntries(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var log = new InMemoryExperimentLog();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            log,
            true,
            new AtomicReference<>());
    tools.runExperiment().execute(Map.of());
    tools.logExperiment().execute(Map.of("status", "keep", "description", "one"));
    var text = tools.showLog().execute(Map.of()).output();
    assertTrue(text.contains("one"));
  }

  @Test
  void showLogRespectsLimit(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var log = new InMemoryExperimentLog();
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            log,
            true,
            new AtomicReference<>());
    for (int i = 0; i < 4; i++) {
      tools.writeFile().execute(Map.of("path", "target.txt", "content", "v" + i));
      tools.runExperiment().execute(Map.of());
      tools.logExperiment().execute(Map.of("status", "keep", "description", "e" + i));
    }
    var text = tools.showLog().execute(Map.of("limit", 1)).output();
    assertTrue(text.contains("e3"));
    assertFalse(text.contains("e0"));
  }

  @Test
  void showLogEmpty(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    assertTrue(tools.showLog().execute(Map.of()).output().contains("log empty"));
  }

  @Test
  void accessorsReturnTools(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var tools =
        CodeCoachTools.create(
            ws,
            List.of(Path.of("target.txt")),
            List.of("sh", "bench.sh"),
            "score",
            Duration.ofSeconds(5),
            new InMemoryExperimentLog(),
            true,
            new AtomicReference<>());
    assertNotNull(tools.readFile());
    assertNotNull(tools.writeFile());
    assertNotNull(tools.runExperiment());
    assertNotNull(tools.logExperiment());
    assertNotNull(tools.showLog());
    assertEquals("read_file", tools.readFile().name());
    assertEquals("write_file", tools.writeFile().name());
    assertEquals("run_experiment", tools.runExperiment().name());
    assertEquals("log_experiment", tools.logExperiment().name());
    assertEquals("show_log", tools.showLog().name());
  }
}
