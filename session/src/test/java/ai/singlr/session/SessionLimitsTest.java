/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SessionLimitsTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var l =
        new SessionLimits(
            50,
            Optional.of(new BigDecimal("12.50")),
            Duration.ofMinutes(30),
            Duration.ofSeconds(45),
            32_000L);
    assertEquals(50, l.maxTurns());
    assertEquals(Optional.of(new BigDecimal("12.50")), l.maxBudgetUsd());
    assertEquals(Duration.ofMinutes(30), l.maxWallClock());
    assertEquals(Duration.ofSeconds(45), l.toolTimeoutDefault());
    assertEquals(32_000L, l.maxContextTokens());
  }

  @Test
  void defaultsExposesExpectedValues() {
    var d = SessionLimits.defaults();
    assertEquals(100, d.maxTurns());
    assertTrue(d.maxBudgetUsd().isEmpty());
    assertEquals(Duration.ofHours(1), d.maxWallClock());
    assertEquals(Duration.ofMinutes(2), d.toolTimeoutDefault());
    assertEquals(180_000L, d.maxContextTokens());
  }

  @Test
  void defaultsReturnsSameSingleton() {
    assertSame(SessionLimits.defaults(), SessionLimits.defaults());
  }

  // ── maxTurns ──────────────────────────────────────────────────────────────

  @Test
  void maxTurnsZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(0, Optional.empty(), oneHour(), twoMin(), 1_000L));
    assertEquals("maxTurns must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxTurnsNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(-1, Optional.empty(), oneHour(), twoMin(), 1_000L));
    assertEquals("maxTurns must be positive, got -1", ex.getMessage());
  }

  // ── maxBudgetUsd ──────────────────────────────────────────────────────────

  @Test
  void maxBudgetUsdNullOptionalRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> newLimits(1, null, oneHour(), twoMin(), 1_000L));
    assertEquals("maxBudgetUsd must not be null", ex.getMessage());
  }

  @Test
  void maxBudgetUsdZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.of(BigDecimal.ZERO), oneHour(), twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxBudgetUsd must be positive when present"));
  }

  @Test
  void maxBudgetUsdNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.of(new BigDecimal("-1.00")), oneHour(), twoMin(), 1_000L));
    assertTrue(ex.getMessage().contains("-1.00"));
  }

  @Test
  void maxBudgetUsdEmptyAccepted() {
    var l = newLimits(1, Optional.empty(), oneHour(), twoMin(), 1_000L);
    assertFalse(l.maxBudgetUsd().isPresent());
  }

  // ── maxWallClock ──────────────────────────────────────────────────────────

  @Test
  void maxWallClockNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> newLimits(1, Optional.empty(), null, twoMin(), 1_000L));
    assertEquals("maxWallClock must not be null", ex.getMessage());
  }

  @Test
  void maxWallClockZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), Duration.ZERO, twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxWallClock must be strictly positive"));
  }

  @Test
  void maxWallClockNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), Duration.ofSeconds(-1), twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxWallClock must be strictly positive"));
  }

  // ── toolTimeoutDefault ────────────────────────────────────────────────────

  @Test
  void toolTimeoutDefaultNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> newLimits(1, Optional.empty(), oneHour(), null, 1_000L));
    assertEquals("toolTimeoutDefault must not be null", ex.getMessage());
  }

  @Test
  void toolTimeoutDefaultZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), oneHour(), Duration.ZERO, 1_000L));
    assertTrue(ex.getMessage().startsWith("toolTimeoutDefault must be strictly positive"));
  }

  @Test
  void toolTimeoutDefaultNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), oneHour(), Duration.ofMillis(-1), 1_000L));
    assertTrue(ex.getMessage().startsWith("toolTimeoutDefault must be strictly positive"));
  }

  // ── maxContextTokens ──────────────────────────────────────────────────────

  @Test
  void maxContextTokensZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), oneHour(), twoMin(), 0L));
    assertEquals("maxContextTokens must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxContextTokensNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, Optional.empty(), oneHour(), twoMin(), -5L));
    assertEquals("maxContextTokens must be positive, got -5", ex.getMessage());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static SessionLimits newLimits(
      int maxTurns,
      Optional<BigDecimal> maxBudgetUsd,
      Duration maxWallClock,
      Duration toolTimeoutDefault,
      long maxContextTokens) {
    return new SessionLimits(
        maxTurns, maxBudgetUsd, maxWallClock, toolTimeoutDefault, maxContextTokens);
  }

  private static Duration oneHour() {
    return Duration.ofHours(1);
  }

  private static Duration twoMin() {
    return Duration.ofMinutes(2);
  }
}
