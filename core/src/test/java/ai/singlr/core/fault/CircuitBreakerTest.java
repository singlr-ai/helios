/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  @Test
  void closedStateAllowsCalls() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();
    var calls = new AtomicInteger(0);

    var result =
        cb.execute(
            () -> {
              calls.incrementAndGet();
              return "success";
            });

    assertEquals("success", result);
    assertEquals(1, calls.get());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void tripOpenAfterFailureThreshold() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();

    for (int i = 0; i < 3; i++) {
      assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    }

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertEquals(3, cb.failureCount());
  }

  @Test
  void openStateRejectsCalls() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> "should not run"));
  }

  @Test
  void successResetsFailureCount() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(2, cb.failureCount());

    cb.execute(() -> "success");

    assertEquals(0, cb.failureCount());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void transitionToHalfOpenAfterDelay() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
  }

  @Test
  void halfOpenSuccessClosesCircuit() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(1)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success");

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());
  }

  @Test
  void halfOpenFailureOpensCircuit() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail again")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());
  }

  @Test
  void multipleSuccessesRequiredToClose() throws Exception {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(2)
            .withSuccessThreshold(3)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    Thread.sleep(100);

    cb.execute(() -> "success 1");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 2");
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.execute(() -> "success 3");
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void reset() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));
    assertThrows(RuntimeException.class, () -> cb.execute(() -> throwRuntime("fail")));

    assertEquals(CircuitBreaker.State.OPEN, cb.state());

    cb.reset();

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(0, cb.failureCount());

    var result = cb.execute(() -> "works again");
    assertEquals("works again", result);
  }

  @Test
  void executeRunnableSuccess() throws Exception {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(3).build();
    var executed = new AtomicInteger(0);

    Runnable operation = () -> executed.incrementAndGet();
    cb.execute(operation);

    assertEquals(1, executed.get());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void executeRunnableFailure() {
    var cb = CircuitBreaker.newBuilder().withFailureThreshold(2).build();

    Runnable operation =
        () -> {
          throw new RuntimeException("fail");
        };
    assertThrows(RuntimeException.class, () -> cb.execute(operation));

    assertEquals(1, cb.failureCount());
  }

  @Test
  void stateCheckInClosedWithNoFailures() {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(5)
            .withHalfOpenAfter(Duration.ofMillis(50))
            .build();

    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void defaultValues() {
    var cb = CircuitBreaker.newBuilder().build();

    assertEquals(5, cb.failureThreshold());
    assertEquals(1, cb.successThreshold());
    assertEquals(Duration.ofSeconds(30), cb.halfOpenAfter());
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
  }

  @Test
  void builderConfiguration() {
    var cb =
        CircuitBreaker.newBuilder()
            .withFailureThreshold(10)
            .withSuccessThreshold(3)
            .withHalfOpenAfter(Duration.ofMinutes(1))
            .build();

    assertEquals(10, cb.failureThreshold());
    assertEquals(3, cb.successThreshold());
    assertEquals(Duration.ofMinutes(1), cb.halfOpenAfter());
  }

  private static String throwRuntime(String message) {
    throw new RuntimeException(message);
  }
}
