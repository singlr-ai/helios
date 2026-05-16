/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.session.ask.AskUserQuestionResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

final class AgentSessionTest {

  /** Minimal AgentSession used to exercise default-method bodies on the interface. */
  private static final class StubSession implements AgentSession {

    final java.util.List<UserMessage> sent = new java.util.ArrayList<>();
    final java.util.List<String> interrupts = new java.util.ArrayList<>();
    int closes = 0;
    boolean started = false;
    final CompletableFuture<ResultMessage> future = new CompletableFuture<>();

    @Override
    public void send(UserMessage message) {
      sent.add(message);
      started = true;
    }

    @Override
    public void interrupt(String reason) {
      interrupts.add(reason);
    }

    @Override
    public Flow.Publisher<QueryEvent> events() {
      return subscriber -> {};
    }

    @Override
    public CompletableFuture<ResultMessage> result() {
      return future;
    }

    @Override
    public String sessionId() {
      return "stub";
    }

    @Override
    public long currentTurnIndex() {
      return 0;
    }

    @Override
    public void close() {
      closes++;
    }

    @Override
    public void answer(String questionId, AskUserQuestionResponse response) {
      // not exercised by these interface-level tests
    }
  }

  @Test
  void sendStringConvertsToUserMessage() {
    var s = new StubSession();
    s.send("hello world");
    assertEquals(1, s.sent.size());
    assertEquals("hello world", s.sent.get(0).text());
  }

  @Test
  void runBlockingSendsAndAwaitsResult() {
    var s = new StubSession();
    var expected =
        new ResultMessage.Success(
            "stub",
            "done",
            ai.singlr.core.model.Response.Usage.of(0, 0),
            CostEstimate.zero(),
            java.time.Duration.ZERO);
    s.future.complete(expected);
    var result = s.runBlocking(UserMessage.text("hi"));
    assertSame(expected, result);
    assertEquals("hi", s.sent.get(0).text());
  }

  /** Trivial record used to build an OutputSchema for the typed-runBlocking tests. */
  public record StubOutput(String answer) {}

  @Test
  void typedRunBlockingRejectsNullMessage() {
    var s = new StubSession();
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> s.runBlocking(null, OutputSchema.of(StubOutput.class)));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void typedRunBlockingRejectsNullSchema() {
    var s = new StubSession();
    var ex =
        assertThrows(NullPointerException.class, () -> s.runBlocking(UserMessage.text("hi"), null));
    assertEquals("schema must not be null", ex.getMessage());
  }
}
