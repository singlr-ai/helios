/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CancellationTokenTest {

  @Test
  void freshTokenIsNotCancelled() {
    var t = new CancellationToken();
    assertFalse(t.isCancelled());
    assertEquals(Optional.empty(), t.reason());
  }

  @Test
  void cancelTransitionsToCancelledAndRecordsReason() {
    var t = new CancellationToken();
    assertTrue(t.cancel("user-stop"));
    assertTrue(t.isCancelled());
    assertEquals(Optional.of("user-stop"), t.reason());
  }

  @Test
  void secondCancelReturnsFalseAndPreservesFirstReason() {
    var t = new CancellationToken();
    assertTrue(t.cancel("first"));
    assertFalse(t.cancel("second"));
    assertEquals(Optional.of("first"), t.reason());
  }

  @Test
  void cancelNullReasonThrowsNullPointerException() {
    var t = new CancellationToken();
    var ex = assertThrows(NullPointerException.class, () -> t.cancel(null));
    assertEquals("reason must not be null", ex.getMessage());
    assertFalse(t.isCancelled(), "failed cancel must not flip state");
  }

  @Test
  void cancelBlankReasonThrowsIllegalArgumentException() {
    var t = new CancellationToken();
    var ex = assertThrows(IllegalArgumentException.class, () -> t.cancel("   "));
    assertEquals("reason must not be blank", ex.getMessage());
    assertFalse(t.isCancelled(), "failed cancel must not flip state");
  }

  @Test
  void cancelEmptyReasonThrowsIllegalArgumentException() {
    var t = new CancellationToken();
    assertThrows(IllegalArgumentException.class, () -> t.cancel(""));
  }

  @Test
  void throwIfCancelledIsSilentBeforeCancel() {
    new CancellationToken().throwIfCancelled();
  }

  @Test
  void throwIfCancelledRaisesCancellationExceptionWithReasonAfterCancel() {
    var t = new CancellationToken();
    t.cancel("user-stop");
    var ex = assertThrows(CancellationException.class, t::throwIfCancelled);
    assertEquals("user-stop", ex.getMessage());
  }

  @Test
  void concurrentCancelsExactlyOneWins() throws Exception {
    var t = new CancellationToken();
    var threadCount = 32;
    var ready = new CountDownLatch(threadCount);
    var start = new CountDownLatch(1);
    var winners = new AtomicInteger(0);
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int id = i;
        exec.submit(
            () -> {
              ready.countDown();
              start.await();
              if (t.cancel("thread-" + id)) {
                winners.incrementAndGet();
              }
              return null;
            });
      }
      assertTrue(ready.await(2, TimeUnit.SECONDS), "threads must arrive at start barrier");
      start.countDown();
    }
    assertEquals(1, winners.get(), "exactly one cancel must report winning the race");
    assertTrue(t.isCancelled());
    assertTrue(t.reason().orElseThrow().startsWith("thread-"));
  }
}
