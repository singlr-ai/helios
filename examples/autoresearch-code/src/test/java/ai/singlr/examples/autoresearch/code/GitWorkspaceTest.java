/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {

  private static GitWorkspace initRepo(Path dir) throws IOException {
    var ws = new GitWorkspace(dir);
    ws.exec(List.of("git", "init", "--quiet"), Duration.ofSeconds(10));
    ws.exec(List.of("git", "config", "user.email", "test@example.com"), Duration.ofSeconds(5));
    ws.exec(List.of("git", "config", "user.name", "test"), Duration.ofSeconds(5));
    Files.writeString(dir.resolve("README"), "hello\n", StandardCharsets.UTF_8);
    ws.exec(List.of("git", "add", "."), Duration.ofSeconds(5));
    ws.exec(List.of("git", "commit", "-m", "initial"), Duration.ofSeconds(5));
    return ws;
  }

  @Test
  void snapshotReturnsHead(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var head = ws.snapshot();
    assertTrue(head.length() == 40, "expected 40-char hash, got: " + head);
  }

  @Test
  void commitAdvancesHead(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var before = ws.snapshot();
    Files.writeString(dir.resolve("x.txt"), "body\n", StandardCharsets.UTF_8);
    var after = ws.commit("add x");
    assertNotEquals(before, after);
  }

  @Test
  void restoreResetsToHash(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var before = ws.snapshot();
    Files.writeString(dir.resolve("x.txt"), "body\n", StandardCharsets.UTF_8);
    ws.commit("add x");
    ws.restore(before);
    assertEquals(before, ws.snapshot());
    assertTrue(!Files.exists(dir.resolve("x.txt")));
  }

  @Test
  void discardWorkingChangesRevertsTree(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    Files.writeString(dir.resolve("README"), "mutated\n", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("new.txt"), "untracked\n", StandardCharsets.UTF_8);
    ws.discardWorkingChanges();
    assertEquals("hello\n", Files.readString(dir.resolve("README"), StandardCharsets.UTF_8));
    assertTrue(!Files.exists(dir.resolve("new.txt")));
  }

  @Test
  void restoreRejectsBlankHash(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    assertThrows(IllegalArgumentException.class, () -> ws.restore(""));
    assertThrows(IllegalArgumentException.class, () -> ws.restore(null));
  }

  @Test
  void execCapturesStdoutAndExit(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var result = ws.exec(List.of("sh", "-c", "echo hello"), Duration.ofSeconds(5));
    assertEquals(0, result.exitCode());
    assertEquals("hello\n", result.stdout());
  }

  @Test
  void execCapturesNonZeroExit(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var result = ws.exec(List.of("sh", "-c", "exit 7"), Duration.ofSeconds(5));
    assertEquals(7, result.exitCode());
  }

  @Test
  void execTimeoutThrows(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    assertThrows(
        IllegalStateException.class,
        () -> ws.exec(List.of("sh", "-c", "sleep 5"), Duration.ofMillis(200)));
  }

  @Test
  void rootReturnsCanonicalizedPath(@TempDir Path dir) throws IOException {
    var ws = new GitWorkspace(dir);
    assertEquals(dir.toRealPath(), ws.root());
  }

  @Test
  void constructorRejectsNullRoot() {
    assertThrows(IllegalArgumentException.class, () -> new GitWorkspace(null));
  }

  @Test
  void constructorRejectsNonExistentRoot(@TempDir Path dir) {
    assertThrows(
        IllegalArgumentException.class, () -> new GitWorkspace(dir.resolve("does-not-exist")));
  }

  @Test
  void constructorRejectsFileAsRoot(@TempDir Path dir) throws IOException {
    var file = dir.resolve("file");
    Files.writeString(file, "x");
    assertThrows(IllegalArgumentException.class, () -> new GitWorkspace(file));
  }

  @Test
  void snapshotOutsideRepoThrows(@TempDir Path dir) {
    var ws = new GitWorkspace(dir);
    assertThrows(IllegalStateException.class, ws::snapshot);
  }

  @Test
  void commitRejectsRestoreOfInvalidHash(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    assertThrows(IllegalStateException.class, () -> ws.restore("abc1234"));
  }

  @Test
  void execReportsDuration(@TempDir Path dir) throws IOException {
    var ws = initRepo(dir);
    var result = ws.exec(List.of("sh", "-c", "true"), Duration.ofSeconds(5));
    assertTrue(result.duration().toNanos() > 0);
  }
}
