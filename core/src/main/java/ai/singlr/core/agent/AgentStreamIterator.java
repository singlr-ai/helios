/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.StreamEvent;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link CloseableIterator} backed by a {@link LinkedBlockingQueue} and a background virtual
 * thread running the agent loop. Events flow from the loop thread through the queue to the
 * consumer.
 *
 * <p>Closing this iterator interrupts the background thread and drains the queue.
 */
final class AgentStreamIterator implements CloseableIterator<StreamEvent> {

  private static final long POLL_TIMEOUT_MS = 100;

  private final LinkedBlockingQueue<StreamEvent> queue;
  private final Thread loopThread;
  private StreamEvent cached;
  private boolean done;

  AgentStreamIterator(LinkedBlockingQueue<StreamEvent> queue, Thread loopThread) {
    this.queue = queue;
    this.loopThread = loopThread;
  }

  @Override
  public boolean hasNext() {
    if (cached != null) {
      return true;
    }
    if (done) {
      return false;
    }
    while (true) {
      try {
        cached = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        done = true;
        return false;
      }
      if (cached != null) {
        if (cached instanceof StreamEvent.Done || cached instanceof StreamEvent.Error) {
          done = true;
        }
        return true;
      }
      if (!loopThread.isAlive()) {
        cached = new StreamEvent.Error("Stream loop thread terminated unexpectedly");
        done = true;
        return true;
      }
    }
  }

  @Override
  public StreamEvent next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    var event = cached;
    cached = null;
    return event;
  }

  @Override
  public void close() {
    done = true;
    loopThread.interrupt();
    queue.clear();
  }
}
