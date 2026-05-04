/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves model-supplied paths against a fixed root and rejects any path that escapes the root,
 * either lexically (via {@code ..} traversal) or by symlink dereference.
 *
 * <p>Two-stage check: first a lexical {@link Path#normalize()} + {@link Path#startsWith(Path)}
 * defends against {@code ..} segments; then {@link Path#toRealPath()} resolves any symlinks and the
 * result is checked against the real path of the root. Both stages run because relying on the
 * lexical check alone would miss symlink escapes, and relying on {@code toRealPath} alone would
 * follow a symlink before checking, which is why the lexical refusal goes first.
 */
final class PathJail {

  private final Path root;
  private final Path realRoot;

  PathJail(Path root) throws IOException {
    Objects.requireNonNull(root, "root");
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Root is not a directory: " + root);
    }
    this.root = root.toAbsolutePath().normalize();
    this.realRoot = this.root.toRealPath();
  }

  /** The configured root, normalized and absolute. */
  Path root() {
    return root;
  }

  /**
   * Resolve {@code requested} against the root and refuse if it would escape. Refuses absolute
   * paths supplied by the caller.
   *
   * @throws JailException if the request escapes the jail or names something not under it
   */
  Path resolve(String requested) {
    Objects.requireNonNull(requested, "requested");
    if (requested.isEmpty()) {
      throw new JailException("Path must not be empty");
    }
    Path candidate;
    try {
      candidate = Path.of(requested);
    } catch (java.nio.file.InvalidPathException e) {
      throw new JailException("Invalid path: " + e.getMessage());
    }
    if (candidate.isAbsolute()) {
      throw new JailException("Path must be relative to the knowledge root: " + requested);
    }
    var resolved = root.resolve(candidate).normalize();
    if (!resolved.startsWith(root)) {
      throw new JailException("Path escapes knowledge root: " + requested);
    }
    return resolved;
  }

  /**
   * Verify that the resolved path, after symlink dereference, is still within the real root. Call
   * after {@link #resolve(String)} and after confirming the file exists.
   *
   * @throws JailException if symlink resolution would escape the jail
   */
  Path verifyReal(Path resolved) throws IOException {
    var real = resolved.toRealPath();
    if (!real.startsWith(realRoot)) {
      throw new JailException("Path escapes knowledge root via symlink");
    }
    return real;
  }

  /** Thrown when a path-jail check refuses a request. */
  static final class JailException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    JailException(String message) {
      super(message);
    }
  }
}
