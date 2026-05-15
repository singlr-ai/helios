/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.common.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Bounded filesystem root the file tools resolve every model-supplied path against. Paths that
 * escape the root — either lexically via {@code ..} or via symlink dereference — surface as a
 * {@link WorkspaceEscapeException} rather than reaching the underlying I/O layer.
 *
 * <p>Two-stage check: lexical normalize + {@code startsWith(root)} catches {@code ..}; if {@code
 * confineSymlinks} is true and the file exists, a follow-up {@link Path#toRealPath()} confirms the
 * real path is still under the real root. The lexical check runs first so it can refuse a
 * non-existent path that escapes lexically — {@code toRealPath} would have thrown {@code
 * NoSuchFileException} on the same input.
 *
 * <p>{@link #resolveSafe(String)} accepts either a relative path (resolved against the root) or an
 * absolute path (must already be under the root). The sandbox is the security boundary; this is
 * defense-in-depth.
 *
 * <p>{@code root} is normalised + absolutised at construction time, so {@link #root()} can be
 * compared safely.
 *
 * @param root the workspace root directory; must exist and be a directory at construction
 * @param confineSymlinks when {@code true}, resolved paths are checked via {@code toRealPath} to
 *     refuse symlinks that point outside the root; when {@code false}, only the lexical check runs
 */
public record WorkspaceRoot(Path root, boolean confineSymlinks) {

  /**
   * Canonical constructor; normalises and absolutises {@code root}.
   *
   * @throws NullPointerException if {@code root} is null
   * @throws IllegalArgumentException if {@code root} does not exist as a directory
   */
  public WorkspaceRoot {
    Objects.requireNonNull(root, "root must not be null");
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("root is not a directory: " + root);
    }
    root = root.toAbsolutePath().normalize();
  }

  /**
   * Build a workspace with {@code confineSymlinks=true} — the strict default.
   *
   * @param root the root directory; must exist
   * @return a fresh workspace
   */
  public static WorkspaceRoot of(Path root) {
    return new WorkspaceRoot(root, true);
  }

  /**
   * Resolve a model-supplied path against the root. The input may be a relative path (resolved
   * against the root) or an absolute path that's already under the root; either way the resolved
   * path is normalised and verified to be inside the root.
   *
   * @param requested the input path; non-null, non-blank
   * @return the resolved absolute, normalised path inside the workspace
   * @throws NullPointerException if {@code requested} is null
   * @throws WorkspaceEscapeException if the path is blank, syntactically invalid, escapes the root
   *     lexically, or (when symlinks are confined and the path exists) escapes via a symlink
   */
  public Path resolveSafe(String requested) {
    Objects.requireNonNull(requested, "requested must not be null");
    if (Strings.isBlank(requested)) {
      throw new WorkspaceEscapeException("path must not be blank");
    }
    Path candidate;
    try {
      candidate = Path.of(requested);
    } catch (InvalidPathException e) {
      throw new WorkspaceEscapeException("invalid path: " + e.getMessage());
    }
    Path resolved =
        candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
    if (!resolved.startsWith(root)) {
      throw new WorkspaceEscapeException("path escapes workspace root: " + requested);
    }
    if (confineSymlinks && Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      try {
        var real = resolved.toRealPath();
        var realRoot = root.toRealPath();
        if (!real.startsWith(realRoot)) {
          throw new WorkspaceEscapeException(
              "path escapes workspace root via symlink: " + requested);
        }
      } catch (IOException e) {
        throw new WorkspaceEscapeException(
            "failed to resolve real path for " + requested + ": " + e.getMessage());
      }
    }
    return resolved;
  }

  /**
   * The model-supplied path made relative to the workspace root, for display in tool results.
   * Returns the absolute path if {@code resolved} is not actually under the root (defensive — the
   * caller normally passes a {@link #resolveSafe(String)} result).
   *
   * @param resolved a path; non-null
   * @return a relative path string suitable for tool output
   */
  public String relativize(Path resolved) {
    Objects.requireNonNull(resolved, "resolved must not be null");
    if (!resolved.startsWith(root)) {
      return resolved.toString();
    }
    var rel = root.relativize(resolved).toString();
    return rel.isEmpty() ? "." : rel;
  }

  /** Thrown by {@link #resolveSafe(String)} when a request fails the path-jail check. */
  public static final class WorkspaceEscapeException extends RuntimeException {

    /**
     * @param message human-readable reason
     */
    public WorkspaceEscapeException(String message) {
      super(message);
    }
  }
}
