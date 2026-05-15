/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.files.WorkspaceRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemMemoryBackendTest {

  private static Path seed(Path workspace, String relPath, String content) throws IOException {
    var target = workspace.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve(relPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  @Test
  void viewReadsSeededFile(@TempDir Path tmp) throws IOException {
    seed(tmp, "user/profile.md", "hello");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals("hello", backend.view("/memories/user/profile.md"));
  }

  @Test
  void viewMissingFails(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NoSuchFileException.class, () -> backend.view("/memories/missing.md"));
  }

  @Test
  void viewRejectsBadPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.view("/elsewhere/x.md"));
  }

  @Test
  void viewRejectsBarePrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.view("/memories/"));
  }

  @Test
  void viewRejectsDirectory(@TempDir Path tmp) throws IOException {
    var dir = tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve("subdir");
    Files.createDirectories(dir);
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    var ex = assertThrows(IOException.class, () -> backend.view("/memories/subdir"));
    assertTrue(ex.getMessage().contains("not a regular file"), ex.getMessage());
  }

  @Test
  void listReturnsAllEntriesUnderPrefix(@TempDir Path tmp) throws IOException {
    seed(tmp, "INDEX.md", "1");
    seed(tmp, "user/a.md", "2");
    seed(tmp, "user/b.md", "3");
    seed(tmp, "project/c.md", "4");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));

    var all = backend.list("/memories/");
    assertEquals(
        List.of(
            "/memories/INDEX.md",
            "/memories/project/c.md",
            "/memories/user/a.md",
            "/memories/user/b.md"),
        all);

    var users = backend.list("/memories/user");
    assertEquals(List.of("/memories/user/a.md", "/memories/user/b.md"), users);
  }

  @Test
  void listOnSingleFileReturnsThatPath(@TempDir Path tmp) throws IOException {
    seed(tmp, "x.md", "x");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of("/memories/x.md"), backend.list("/memories/x.md"));
  }

  @Test
  void listEmptyPrefixTreatedAsRoot(@TempDir Path tmp) throws IOException {
    seed(tmp, "a.md", "a");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of("/memories/a.md"), backend.list(""));
  }

  @Test
  void listMissingMemoryRootReturnsEmpty(@TempDir Path tmp) throws IOException {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of(), backend.list("/memories/"));
  }

  @Test
  void listMissingSubprefixReturnsEmpty(@TempDir Path tmp) throws IOException {
    seed(tmp, "user/a.md", "");
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertEquals(List.of(), backend.list("/memories/project"));
  }

  @Test
  void listRejectsBadPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(IllegalArgumentException.class, () -> backend.list("/foo"));
  }

  @Test
  void listRejectsNullPrefix(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(NullPointerException.class, () -> backend.list(null));
  }

  @Test
  void viewRefusesPathEscape(@TempDir Path tmp) {
    var backend = FileSystemMemoryBackend.of(WorkspaceRoot.of(tmp));
    assertThrows(RuntimeException.class, () -> backend.view("/memories/../escape.md"));
  }

  @Test
  void factoryRejectsNull() {
    assertThrows(NullPointerException.class, () -> FileSystemMemoryBackend.of(null));
  }

  @Test
  void accessorsExposeWorkspaceAndRoot(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var backend = FileSystemMemoryBackend.of(ws);
    assertEquals(ws, backend.workspace());
    assertNotNull(backend.memoryRoot());
    assertTrue(backend.memoryRoot().toString().endsWith(FileSystemMemoryBackend.STORAGE_SUBDIR));
  }
}
