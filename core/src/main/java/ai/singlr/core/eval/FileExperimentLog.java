/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only {@link ExperimentLog} backed by a JSONL file. One entry per line, flushed after every
 * {@link #append}. Opens idempotently — existing entries are replayed on construction so callers
 * can resume sessions after a crash or context reset.
 *
 * <p>Thread-safe within a single process via an internal lock. Multi-process concurrency is not
 * supported — running two writers against the same file will produce interleaved lines.
 */
public final class FileExperimentLog implements ExperimentLog {

  private final Path path;
  private final List<ExperimentEntry> entries;
  private final ReentrantLock lock = new ReentrantLock();
  private BufferedWriter writer;
  private int segment;
  private boolean closed;

  /**
   * Open a log at {@code path}. Creates the file if it does not exist; otherwise replays existing
   * lines into memory and opens the file for append.
   *
   * @param path filesystem location of the log
   * @return an open log
   */
  public static FileExperimentLog open(Path path) {
    return new FileExperimentLog(path);
  }

  private FileExperimentLog(Path path) {
    this.path = path;
    this.entries = new ArrayList<>();
    if (path.getParent() != null) {
      try {
        Files.createDirectories(path.getParent());
      } catch (IOException e) {
        throw new UncheckedIOException("could not create parent directory of " + path, e);
      }
    }
    if (Files.exists(path)) {
      replay();
    }
    try {
      this.writer =
          Files.newBufferedWriter(
              path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("could not open log for append at " + path, e);
    }
    this.segment = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).segment();
  }

  private void replay() {
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      int lineNum = 0;
      while ((line = reader.readLine()) != null) {
        lineNum++;
        if (line.isBlank()) {
          continue;
        }
        try {
          entries.add(JsonlCodec.decode(line));
        } catch (JsonlCodecException e) {
          throw new IllegalStateException(
              "corrupt log at " + path + ":" + lineNum + ": " + e.getMessage(), e);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read log at " + path, e);
    }
  }

  @Override
  public void append(ExperimentEntry entry) {
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("log is closed");
      }
      writer.write(JsonlCodec.encode(entry));
      writer.newLine();
      writer.flush();
      entries.add(entry);
    } catch (IOException e) {
      throw new UncheckedIOException("could not append entry to " + path, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<ExperimentEntry> entries() {
    lock.lock();
    try {
      return List.copyOf(entries);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<ExperimentEntry> segment(int segmentId) {
    lock.lock();
    try {
      var out = new ArrayList<ExperimentEntry>();
      for (var e : entries) {
        if (e.segment() == segmentId) {
          out.add(e);
        }
      }
      return Collections.unmodifiableList(out);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int currentSegment() {
    lock.lock();
    try {
      return segment;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int newSegment() {
    lock.lock();
    try {
      segment++;
      return segment;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Return the path this log is backed by.
   *
   * @return filesystem path
   */
  public Path path() {
    return path;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      try {
        writer.close();
      } catch (IOException e) {
        throw new UncheckedIOException("could not close log at " + path, e);
      }
    } finally {
      lock.unlock();
    }
  }
}
