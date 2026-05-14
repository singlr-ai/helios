/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SessionScopeTest {

  private static SessionScope scope(String id, long turn) {
    return new SessionScope(id, turn, new CancellationToken());
  }

  @Test
  void accessorsReturnConstructedValues() {
    var token = new CancellationToken();
    var s = new SessionScope("sess-1", 3, token);
    assertEquals("sess-1", s.sessionId());
    assertEquals(3, s.turnIndex());
    assertSame(token, s.cancellation());
  }

  @Test
  void zeroTurnIndexAllowed() {
    var s = scope("sess-0", 0);
    assertEquals(0, s.turnIndex());
  }

  @Test
  void nullSessionIdThrowsNullPointerException() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SessionScope(null, 0, new CancellationToken()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void blankSessionIdThrowsIllegalArgumentException() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SessionScope("   ", 0, new CancellationToken()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void negativeTurnIndexThrowsIllegalArgumentException() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SessionScope("sess", -1, new CancellationToken()));
    assertEquals("turnIndex must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void nullCancellationTokenThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> new SessionScope("sess", 0, null));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void withNextTurnAdvancesIndexAndKeepsIdentity() {
    var token = new CancellationToken();
    var s0 = new SessionScope("sess", 0, token);
    var s1 = s0.withNextTurn();
    assertNotSame(s0, s1);
    assertEquals("sess", s1.sessionId());
    assertEquals(1, s1.turnIndex());
    assertSame(token, s1.cancellation());
  }

  @Test
  void currentThrowsWhenUnbound() {
    var ex = assertThrows(IllegalStateException.class, SessionScope::current);
    assertEquals("No SessionScope is bound on this thread", ex.getMessage());
  }

  @Test
  void currentOptionalIsEmptyWhenUnbound() {
    assertEquals(Optional.empty(), SessionScope.currentOptional());
  }

  @Test
  void callWithBindsScopeForBodyDuration() throws Exception {
    var s = scope("sess", 7);
    var inside = SessionScope.callWith(s, () -> SessionScope.current());
    assertSame(s, inside);
    assertEquals(Optional.empty(), SessionScope.currentOptional(), "binding must not leak out");
  }

  @Test
  void callWithPropagatesReturnValue() throws Exception {
    var s = scope("sess", 0);
    var result = SessionScope.callWith(s, () -> "answer");
    assertEquals("answer", result);
  }

  @Test
  void callWithPropagatesCheckedException() {
    var s = scope("sess", 0);
    var ex =
        assertThrows(
            Exception.class,
            () ->
                SessionScope.callWith(
                    s,
                    () -> {
                      throw new Exception("body-failed");
                    }));
    assertEquals("body-failed", ex.getMessage());
  }

  @Test
  void runWithBindsScopeForBodyDuration() {
    var s = scope("sess", 2);
    var captured = new AtomicReference<SessionScope>();
    SessionScope.runWith(s, () -> captured.set(SessionScope.current()));
    assertSame(s, captured.get());
    assertEquals(Optional.empty(), SessionScope.currentOptional());
  }

  @Test
  void callWithNullScopeThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> SessionScope.callWith(null, () -> 1));
    assertEquals("scope must not be null", ex.getMessage());
  }

  @Test
  void callWithNullBodyThrowsNullPointerException() {
    var s = scope("sess", 0);
    var ex = assertThrows(NullPointerException.class, () -> SessionScope.callWith(s, null));
    assertEquals("body must not be null", ex.getMessage());
  }

  @Test
  void runWithNullScopeThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> SessionScope.runWith(null, () -> {}));
    assertEquals("scope must not be null", ex.getMessage());
  }

  @Test
  void runWithNullBodyThrowsNullPointerException() {
    var s = scope("sess", 0);
    var ex = assertThrows(NullPointerException.class, () -> SessionScope.runWith(s, null));
    assertEquals("body must not be null", ex.getMessage());
  }

  @Test
  void currentOptionalReturnsBoundScope() throws Exception {
    var s = scope("sess", 0);
    var inside = SessionScope.callWith(s, () -> SessionScope.currentOptional());
    assertTrue(inside.isPresent());
    assertSame(s, inside.get());
  }
}
