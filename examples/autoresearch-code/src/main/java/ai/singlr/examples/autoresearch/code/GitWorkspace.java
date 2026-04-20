/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import ai.singlr.core.eval.Checkpoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around {@code git} and shell commands for a single working tree.
 *
 * <p>Implements {@link Checkpoint} where the snapshot is a commit hash. {@link #snapshot()} returns
 * the current {@code HEAD}; {@link #restore} runs {@code git reset --hard} to that hash. Keeping a
 * candidate means {@link #commit(String)}ing first, so {@code snapshot()} advances to the new
 * commit on the next call.
 *
 * <p>Delegates to the {@code git} binary on {@code PATH} via {@link ProcessBuilder} — no JGit,
 * because autoresearch is a thin demo and shelling out keeps the dependency surface zero.
 */
public final class GitWorkspace implements Checkpoint<String> {

  private final Path root;

  /**
   * Create a workspace rooted at the given directory. The directory must already exist and be a git
   * checkout. The path is canonicalized with {@link Path#toRealPath} so downstream scope checks can
   * compare absolute, symlink-resolved paths — protecting callers against symlink- based
   * scope-escape attempts.
   *
   * @param root directory containing {@code .git}
   * @throws IllegalArgumentException if {@code root} does not exist or cannot be canonicalized
   */
  public GitWorkspace(Path root) {
    if (root == null) {
      throw new IllegalArgumentException("root must not be null");
    }
    try {
      this.root = root.toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot canonicalize repo root: " + root, e);
    }
    if (!Files.isDirectory(this.root)) {
      throw new IllegalArgumentException("repo root is not a directory: " + this.root);
    }
  }

  /** The canonical working tree root. */
  public Path root() {
    return root;
  }

  @Override
  public String snapshot() {
    return run(List.of("git", "rev-parse", "HEAD")).stdout.trim();
  }

  @Override
  public void restore(String hash) {
    if (hash == null || hash.isBlank()) {
      throw new IllegalArgumentException("hash must not be blank");
    }
    run(List.of("git", "reset", "--hard", hash));
  }

  /**
   * Stage every change under the working tree ({@code git add -A}) and commit. Returns the new HEAD
   * hash.
   *
   * <p><b>Destructive:</b> stages and commits every modified, staged, and untracked file — not just
   * the ones the autoresearch loop edited. The caller must start from a clean working tree, which
   * {@code CodeAutoresearch.Builder.build} enforces via {@code git status --porcelain} unless
   * {@code withAllowDirtyRepo(true)} is explicitly set.
   *
   * @param message the commit message
   * @return the new HEAD hash
   */
  public String commit(String message) {
    run(List.of("git", "add", "-A"));
    run(List.of("git", "commit", "-am", message));
    return snapshot();
  }

  /**
   * Discard working-tree changes since the last commit — tracked modifications via {@code git
   * checkout -- .} and untracked files via {@code git clean -fd}.
   *
   * <p><b>Destructive:</b> removes every uncommitted change in the working tree, including
   * untracked files. Callers must assume a clean starting tree; the autoresearch loop guards
   * against running on a dirty repo by default.
   */
  public void discardWorkingChanges() {
    run(List.of("git", "checkout", "--", "."));
    run(List.of("git", "clean", "-fd"));
  }

  /**
   * Execute a shell command in the working tree and return stdout, stderr, exit code, wall-clock
   * time.
   *
   * @param command the command argv
   * @param timeout maximum run time
   * @return command result
   */
  public CommandResult exec(List<String> command, java.time.Duration timeout) {
    Process process;
    try {
      process =
          new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(false).start();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to start " + command, e);
    }
    var started = System.nanoTime();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stdoutFuture = executor.submit(() -> drain(process.getInputStream()));
      var stderrFuture = executor.submit(() -> drain(process.getErrorStream()));
      try {
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new IllegalStateException(
              "command timed out after " + timeout + ": " + String.join(" ", command));
        }
        var elapsed = java.time.Duration.ofNanos(System.nanoTime() - started);
        return new CommandResult(
            process.exitValue(), stdoutFuture.get(), stderrFuture.get(), elapsed);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new IllegalStateException("interrupted while executing " + command, e);
      } catch (ExecutionException e) {
        throw new UncheckedIOException(
            "failed to drain output of " + command, new IOException(e.getCause()));
      }
    }
  }

  private CommandResult run(List<String> command) {
    var result = exec(command, java.time.Duration.ofSeconds(30));
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "command failed ("
              + String.join(" ", command)
              + "): exit="
              + result.exitCode()
              + " stderr="
              + result.stderr());
    }
    return result;
  }

  private static String drain(java.io.InputStream stream) throws IOException {
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      var sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append('\n');
      }
      return sb.toString();
    }
  }

  /**
   * Output of an executed command.
   *
   * @param exitCode the process exit code
   * @param stdout stdout content
   * @param stderr stderr content
   * @param duration wall-clock duration
   */
  public record CommandResult(
      int exitCode, String stdout, String stderr, java.time.Duration duration) {}
}
