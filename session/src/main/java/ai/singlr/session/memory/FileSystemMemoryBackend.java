/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import ai.singlr.session.files.WorkspaceRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Filesystem-backed {@link MemoryBackend} that stores memory under {@code
 * <workspace>/.agent/memory} under an existing {@link WorkspaceRoot}. The {@code /memories/}
 * virtual prefix is stripped and re-joined when listing, so the model never sees the on-disk path.
 *
 * <p>The backend creates the root directory lazily — first write would, but Phase 2 only exposes
 * {@code view} + {@code list}. {@code view} on a missing entry throws {@link NoSuchFileException};
 * {@code list} on a missing root returns the empty list (a fresh workspace has nothing to list yet,
 * which should not be an error).
 *
 * <p>All paths route through {@link WorkspaceRoot#resolveSafe(String)}, so symlinks pointing
 * outside the memory root are refused by the path-jail.
 */
public final class FileSystemMemoryBackend implements MemoryBackend {

  /** The on-disk subdirectory under the workspace where memory lives. */
  public static final String STORAGE_SUBDIR = ".agent/memory";

  private final WorkspaceRoot workspace;
  private final Path memoryRoot;

  private FileSystemMemoryBackend(WorkspaceRoot workspace) {
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.memoryRoot = workspace.root().resolve(STORAGE_SUBDIR).normalize();
  }

  /**
   * Build a backend rooted under the given workspace.
   *
   * @param workspace the workspace; non-null
   * @return a fresh backend
   */
  public static FileSystemMemoryBackend of(WorkspaceRoot workspace) {
    return new FileSystemMemoryBackend(workspace);
  }

  /**
   * The workspace this backend is anchored under.
   *
   * @return the workspace root
   */
  public WorkspaceRoot workspace() {
    return workspace;
  }

  /**
   * The on-disk directory memory is stored under.
   *
   * @return absolute path
   */
  public Path memoryRoot() {
    return memoryRoot;
  }

  @Override
  public String view(String path) throws IOException {
    var resolved = resolveMemoryPath(path);
    if (!Files.exists(resolved)) {
      throw new NoSuchFileException(path);
    }
    if (!Files.isRegularFile(resolved)) {
      throw new IOException("memory entry is not a regular file: " + path);
    }
    return Files.readString(resolved, StandardCharsets.UTF_8);
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    Objects.requireNonNull(prefix, "prefix must not be null");
    var normalisedPrefix = prefix.isEmpty() ? PREFIX : prefix;
    if (!normalisedPrefix.startsWith(PREFIX)) {
      throw new IllegalArgumentException(
          "prefix must start with " + PREFIX + ", got '" + prefix + "'");
    }
    if (!Files.exists(memoryRoot)) {
      return List.of();
    }
    var rel = normalisedPrefix.substring(PREFIX.length());
    Path start;
    if (rel.isEmpty()) {
      start = memoryRoot;
    } else {
      start = workspace.resolveSafe(STORAGE_SUBDIR + "/" + rel);
      if (!start.startsWith(memoryRoot)) {
        throw new IllegalArgumentException("prefix escapes memory root: " + prefix);
      }
      if (!Files.exists(start)) {
        return List.of();
      }
    }
    if (Files.isRegularFile(start)) {
      return List.of(toMemoryPath(start));
    }
    var out = new ArrayList<String>();
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            out.add(toMemoryPath(file));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(out);
    return List.copyOf(out);
  }

  /**
   * Resolve a {@code /memories/...} path to an on-disk path, validating the prefix and routing
   * through the workspace's path-jail.
   *
   * @param path the memory path; non-null
   * @return the absolute, normalised on-disk path
   * @throws IllegalArgumentException if {@code path} is malformed
   */
  Path resolveMemoryPath(String path) {
    Objects.requireNonNull(path, "path must not be null");
    if (!path.startsWith(PREFIX)) {
      throw new IllegalArgumentException("path must start with " + PREFIX + ", got '" + path + "'");
    }
    var rel = path.substring(PREFIX.length());
    if (rel.isEmpty()) {
      throw new IllegalArgumentException("path must name a file under " + PREFIX);
    }
    var resolved = workspace.resolveSafe(STORAGE_SUBDIR + "/" + rel);
    if (!resolved.startsWith(memoryRoot)) {
      throw new IllegalArgumentException("path escapes memory root: " + path);
    }
    return resolved;
  }

  private String toMemoryPath(Path absolute) {
    var rel = memoryRoot.relativize(absolute).toString().replace('\\', '/');
    return PREFIX + rel;
  }
}
