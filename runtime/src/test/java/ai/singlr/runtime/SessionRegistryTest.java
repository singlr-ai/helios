/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionRegistryTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static SessionOptions optionsFor(String sessionId) {
    return SessionOptions.newBuilder().withModel(stubModel()).withSessionId(sessionId).build();
  }

  @Test
  void inMemoryFactoryProducesEmptyRegistry() {
    var registry = SessionRegistry.inMemory();
    assertEquals(0, registry.size());
    assertTrue(registry.sessionIds().isEmpty());
  }

  @Test
  void withFactoryRejectsNullFactory() {
    var ex = assertThrows(NullPointerException.class, () -> SessionRegistry.withFactory(null));
    assertEquals("factory must not be null", ex.getMessage());
  }

  @Test
  void createRegistersAndReturnsSession() {
    var registry = SessionRegistry.inMemory();
    var session = registry.create(optionsFor("s1"));
    assertEquals("s1", session.sessionId());
    assertEquals(1, registry.size());
    assertTrue(registry.get("s1").isPresent());
    registry.closeAll();
  }

  @Test
  void createRejectsNullOptions() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.create(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createRejectsDuplicateSessionId() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("dup"));
    var ex = assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("dup")));
    assertTrue(ex.getMessage().contains("already registered"));
    assertEquals(1, registry.size());
    registry.closeAll();
  }

  @Test
  void createRejectsFactoryReturningNull() {
    var registry = SessionRegistry.withFactory(opts -> null);
    var ex = assertThrows(NullPointerException.class, () -> registry.create(optionsFor("ignored")));
    assertEquals("factory returned null session", ex.getMessage());
  }

  @Test
  void getRejectsNullId() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.get(null));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void getReturnsEmptyForUnknownId() {
    assertTrue(SessionRegistry.inMemory().get("nope").isEmpty());
  }

  @Test
  void getReturnsSameInstanceAcrossCalls() {
    var registry = SessionRegistry.inMemory();
    var session = registry.create(optionsFor("s1"));
    assertSame(session, registry.get("s1").orElseThrow());
    assertSame(session, registry.get("s1").orElseThrow());
    registry.closeAll();
  }

  @Test
  void closeRemovesAndClosesSession() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"));
    assertTrue(registry.close("s1"));
    assertEquals(0, registry.size());
    assertTrue(registry.get("s1").isEmpty());
  }

  @Test
  void closeReturnsFalseForUnknownId() {
    assertFalse(SessionRegistry.inMemory().close("nope"));
  }

  @Test
  void closeRejectsNullId() {
    var registry = SessionRegistry.inMemory();
    var ex = assertThrows(NullPointerException.class, () -> registry.close(null));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void closeAllUnregistersAllSessions() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"));
    registry.create(optionsFor("s2"));
    registry.create(optionsFor("s3"));
    assertEquals(3, registry.size());
    registry.closeAll();
    assertEquals(0, registry.size());
  }

  @Test
  void closeAllOnEmptyRegistryIsNoOp() {
    var registry = SessionRegistry.inMemory();
    registry.closeAll();
    assertEquals(0, registry.size());
  }

  @Test
  void sessionIdsSnapshotIsImmutable() {
    var registry = SessionRegistry.inMemory();
    registry.create(optionsFor("s1"));
    var snapshot = registry.sessionIds();
    assertEquals(1, snapshot.size());
    registry.create(optionsFor("s2"));
    assertEquals(1, snapshot.size(), "snapshot is point-in-time");
    assertEquals(2, registry.size());
    registry.closeAll();
  }

  @Test
  void duplicateIdClosesTheRejectedSession() {
    var closeCount = new AtomicInteger();
    var registry =
        SessionRegistry.withFactory(opts -> new TrackingSession(opts.sessionId(), closeCount));
    registry.create(optionsFor("s1"));
    assertThrows(IllegalStateException.class, () -> registry.create(optionsFor("s1")));
    // The freshly-built tracking session for the rejected duplicate should be closed once;
    // the original remains open until we tear down.
    assertEquals(1, closeCount.get(), "rejected duplicate must close its freshly-built session");
    registry.closeAll();
    assertEquals(2, closeCount.get(), "original closes during closeAll");
  }

  @Test
  void createReturnsFreshInstancePerCall() {
    var registry = SessionRegistry.inMemory();
    var a = registry.create(optionsFor("a"));
    var b = registry.create(optionsFor("b"));
    assertNotSame(a, b);
    registry.closeAll();
  }

  /** Minimal AgentSession used by the duplicate-id test to assert close() is called. */
  private static final class TrackingSession implements AgentSession {

    private final String sessionId;
    private final AtomicInteger closeCount;
    private final java.util.concurrent.CompletableFuture<ai.singlr.session.ResultMessage> future =
        new java.util.concurrent.CompletableFuture<>();

    TrackingSession(String sessionId, AtomicInteger closeCount) {
      this.sessionId = sessionId;
      this.closeCount = closeCount;
    }

    @Override
    public void send(ai.singlr.session.UserMessage message) {}

    @Override
    public void interrupt(String reason) {}

    @Override
    public java.util.concurrent.Flow.Publisher<ai.singlr.session.QueryEvent> events() {
      return s -> {};
    }

    @Override
    public java.util.concurrent.CompletableFuture<ai.singlr.session.ResultMessage> result() {
      return future;
    }

    @Override
    public String sessionId() {
      return sessionId;
    }

    @Override
    public long currentTurnIndex() {
      return 0;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
    }

    @Override
    public void answer(String questionId, ai.singlr.session.ask.AskUserQuestionResponse response) {}
  }
}
