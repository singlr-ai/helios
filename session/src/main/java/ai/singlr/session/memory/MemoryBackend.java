/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import java.io.IOException;
import java.util.List;

/**
 * Storage adapter for the agent's persistent memory surface. The Phase 2 read-only contract carries
 * {@link #view(String)} and {@link #list(String)}; Phase 4 will extend it with {@code create},
 * {@code strReplace}, {@code insert}, and {@code delete} once write tools land.
 *
 * <p>Every path the model supplies is normalised to start with {@code /memories/} — the prefix
 * matches the spec's path-space and lets the permission system match rules with a single glob.
 * Implementations validate the prefix and translate it onto a concrete storage layout (filesystem,
 * Postgres, etc.). Anything outside the prefix is an {@link IllegalArgumentException}.
 *
 * <p>{@link FileSystemMemoryBackend} is the reference implementation; it maps {@code /memories/**}
 * to {@code <workspace>/.agent/memory/**} under the existing {@link
 * ai.singlr.session.files.WorkspaceRoot} path-jail.
 */
public interface MemoryBackend {

  /** The required path prefix for every memory operation. */
  String PREFIX = "/memories/";

  /**
   * Read the text content at {@code path}.
   *
   * @param path the memory path; non-null, non-blank, must begin with {@link #PREFIX}
   * @return the file's UTF-8 content
   * @throws IllegalArgumentException if {@code path} is malformed
   * @throws IOException if reading fails
   * @throws java.nio.file.NoSuchFileException if no entry exists at {@code path}
   */
  String view(String path) throws IOException;

  /**
   * List the entries under {@code prefix}, recursively. Returned paths are normalised back into the
   * {@code /memories/...} namespace, sorted ascending.
   *
   * @param prefix the path prefix; non-null. If empty or equal to {@link #PREFIX}, returns the
   *     whole memory tree.
   * @return immutable list of {@code /memories/...} paths
   * @throws IllegalArgumentException if {@code prefix} is malformed
   * @throws IOException if listing fails
   */
  List<String> list(String prefix) throws IOException;
}
