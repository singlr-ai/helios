/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.tools.ToolCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReadToolTest {

  @Test
  void readsFileWithLineNumbers(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("hello.txt");
    Files.writeString(file, "alpha\nbeta\ngamma", StandardCharsets.UTF_8);
    var ws = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();

    var binding = ReadTool.binding(ws, tracker);
    var result = binding.tool().execute(Map.of("path", "hello.txt"));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("     1\talpha\n"), out);
    assertTrue(out.contains("     2\tbeta\n"), out);
    assertTrue(out.contains("     3\tgamma\n"), out);
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("hello.txt", binding.permissionKey(Map.of("path", "hello.txt")).canonicalArgs());
    assertTrue(tracker.hasReadInSession(file));
  }

  @Test
  void offsetAndLimitPaginate(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("nums.txt");
    Files.writeString(file, "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 2, "limit", 2));

    assertTrue(result.success(), result.output());
    var out = result.output();
    assertTrue(out.contains("     2\ttwo\n"), out);
    assertTrue(out.contains("     3\tthree\n"), out);
    assertFalse(out.contains("one"));
    assertFalse(out.contains("four"));
    assertTrue(out.contains("[truncated at line 3 of 6]"), out);
  }

  @Test
  void acceptsLongOffsetAndLimit(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("nums.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", 1L, "limit", 1L));
    assertTrue(result.success());
    assertTrue(result.output().contains("     1\ta"));
  }

  @Test
  void acceptsNumericOffsetAndLimit(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("nums.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nums.txt", "offset", (short) 2, "limit", (short) 1));
    assertTrue(result.success());
    assertTrue(result.output().contains("     2\tb"), result.output());
  }

  @Test
  void missingPathArgFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'path'"), result.output());
  }

  @Test
  void invalidOffsetFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "x.txt", "offset", 0));
    assertFalse(result.success());
    assertTrue(result.output().contains("'offset' must be >= 1"), result.output());
  }

  @Test
  void invalidLimitFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("x.txt"), "a\n", StandardCharsets.UTF_8);
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "x.txt", "limit", 0));
    assertFalse(result.success());
    assertTrue(result.output().contains("'limit' must be >= 1"), result.output());
  }

  @Test
  void notARegularFileFails(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("dir"));
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "dir"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "../escape.txt"));
    assertFalse(result.success());
    assertTrue(result.output().startsWith("Read:"), result.output());
  }

  @Test
  void readingMissingFileFails(@TempDir Path tmp) {
    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "nope.txt"));
    assertFalse(result.success());
    assertTrue(result.output().contains("not a regular file"), result.output());
  }

  @Test
  void nameConstantMatchesBinding(@TempDir Path tmp) {
    var binding = ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create());
    assertEquals("Read", binding.name());
    assertEquals("Read", ReadTool.NAME);
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(
        NullPointerException.class, () -> ReadTool.binding(null, InMemoryFileTracker.create()));
  }

  @Test
  void rejectsNullTracker(@TempDir Path tmp) {
    assertThrows(NullPointerException.class, () -> ReadTool.binding(WorkspaceRoot.of(tmp), null));
  }
}
