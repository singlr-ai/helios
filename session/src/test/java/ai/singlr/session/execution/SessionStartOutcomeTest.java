/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SessionStartOutcomeTest {

  @Test
  void acceptFactoryReturnsSingleton() {
    assertSame(SessionStartOutcome.ACCEPT, SessionStartOutcome.accept());
    assertSame(SessionStartOutcome.accept(), SessionStartOutcome.accept());
  }

  @Test
  void acceptIsAcceptInstance() {
    assertInstanceOf(SessionStartOutcome.Accept.class, SessionStartOutcome.accept());
  }

  @Test
  void refuseCarriesReason() {
    var r = SessionStartOutcome.refuse("pool saturated");
    assertInstanceOf(SessionStartOutcome.Refuse.class, r);
    assertEquals("pool saturated", r.reason());
  }

  @Test
  void refuseRejectsNullReason() {
    var ex = assertThrows(NullPointerException.class, () -> SessionStartOutcome.refuse(null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void refuseRejectsBlankReason() {
    var ex = assertThrows(IllegalArgumentException.class, () -> SessionStartOutcome.refuse("  "));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  @Test
  void refuseRejectsEmptyReason() {
    var ex = assertThrows(IllegalArgumentException.class, () -> SessionStartOutcome.refuse(""));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  @Test
  void switchPatternCoversBothVariants() {
    SessionStartOutcome accept = SessionStartOutcome.accept();
    SessionStartOutcome refuse = SessionStartOutcome.refuse("nope");
    for (var o : new SessionStartOutcome[] {accept, refuse}) {
      var tag =
          switch (o) {
            case SessionStartOutcome.Accept a -> "accept";
            case SessionStartOutcome.Refuse r -> "refuse:" + r.reason();
          };
      assertEquals(tag.startsWith("accept") || tag.startsWith("refuse:"), true);
    }
  }
}
