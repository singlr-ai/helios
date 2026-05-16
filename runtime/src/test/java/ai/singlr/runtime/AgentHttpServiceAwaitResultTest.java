/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.session.ResultMessage;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link AgentHttpService#awaitResult(CompletableFuture, long,
 * String)} helper. The integration test covers the happy and timeout paths through HTTP; this
 * fixture covers the exception paths (interrupted, execution-exception) that are awkward to reach
 * via a black-box HTTP call.
 */
final class AgentHttpServiceAwaitResultTest {

  private static ResultMessage stubSuccess() {
    return new ResultMessage.Success(
        "sess-1", "the result", Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO);
  }

  @Test
  void successFutureReturns200WithTypedBody() {
    var future = CompletableFuture.completedFuture(stubSuccess());
    var outcome = AgentHttpService.awaitResult(future, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.OK_200, outcome.status());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) outcome.body();
    assertEquals("Success", body.get("type"));
    assertEquals(stubSuccess(), body.get("result"));
  }

  @Test
  void unresolvedFutureWithinShortTimeoutReturns204() {
    var future = new CompletableFuture<ResultMessage>();
    var outcome = AgentHttpService.awaitResult(future, 0L, "sess-1");
    assertEquals(io.helidon.http.Status.NO_CONTENT_204, outcome.status());
    assertNull(outcome.body(), "204 must carry a null body sentinel");
  }

  @Test
  void interruptedWhileWaitingReturns503AndPreservesInterruptFlag() {
    var future = new CompletableFuture<ResultMessage>();
    Thread.currentThread().interrupt();
    try {
      var outcome = AgentHttpService.awaitResult(future, 60L, "sess-1");
      assertEquals(io.helidon.http.Status.SERVICE_UNAVAILABLE_503, outcome.status());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) outcome.body();
      assertTrue(body.get("error").toString().contains("interrupted"));
    } finally {
      // Clear the flag for any subsequent tests sharing this thread.
      assertTrue(Thread.interrupted(), "interrupt flag must have been re-set by awaitResult");
    }
  }

  @Test
  void exceptionallyCompletedFutureReturns500WithCauseMessage() {
    var future = new CompletableFuture<ResultMessage>();
    future.completeExceptionally(new IllegalStateException("backend exploded"));
    var outcome = AgentHttpService.awaitResult(future, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.INTERNAL_SERVER_ERROR_500, outcome.status());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) outcome.body();
    assertTrue(body.get("error").toString().contains("backend exploded"));
  }

  @Test
  void exceptionallyCompletedFutureWithNullMessageStillReports500() {
    var future = new CompletableFuture<ResultMessage>();
    // Throwable with null message — cover the cause.getMessage() == null branch.
    future.completeExceptionally(new RuntimeException((String) null));
    var outcome = AgentHttpService.awaitResult(future, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.INTERNAL_SERVER_ERROR_500, outcome.status());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) outcome.body();
    assertTrue(body.get("error").toString().contains("unknown"));
  }
}
