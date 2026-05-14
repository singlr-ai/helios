/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class CostEstimateTest {

  @Test
  void canonicalConstructorReadsUsdBack() {
    var cost = new CostEstimate(new BigDecimal("1.23"));
    assertEquals(new BigDecimal("1.23"), cost.usd());
  }

  @Test
  void zeroIsAllowed() {
    var cost = new CostEstimate(BigDecimal.ZERO);
    assertEquals(BigDecimal.ZERO, cost.usd());
  }

  @Test
  void zeroFactoryReturnsSharedInstance() {
    assertSame(CostEstimate.zero(), CostEstimate.zero());
    assertEquals(BigDecimal.ZERO, CostEstimate.zero().usd());
  }

  @Test
  void ofUsdFactoryConvertsDouble() {
    var cost = CostEstimate.ofUsd(0.05);
    assertEquals(BigDecimal.valueOf(0.05), cost.usd());
  }

  @Test
  void ofUsdAcceptsZero() {
    var cost = CostEstimate.ofUsd(0.0);
    assertEquals(BigDecimal.valueOf(0.0), cost.usd());
  }

  @Test
  void nullUsdThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> new CostEstimate(null));
    assertEquals("usd must not be null", ex.getMessage());
  }

  @Test
  void negativeUsdThrowsIllegalArgumentException() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new CostEstimate(new BigDecimal("-0.01")));
    assertEquals("usd must be non-negative, got -0.01", ex.getMessage());
  }

  @Test
  void ofUsdNegativeRejectedTransitively() {
    assertThrows(IllegalArgumentException.class, () -> CostEstimate.ofUsd(-1.0));
  }

  @Test
  void plusAddsTwoCosts() {
    var sum =
        new CostEstimate(new BigDecimal("1.50")).plus(new CostEstimate(new BigDecimal("2.25")));
    assertEquals(new BigDecimal("3.75"), sum.usd());
  }

  @Test
  void plusWithZeroIsIdentity() {
    var c = new CostEstimate(new BigDecimal("0.10"));
    assertEquals(c.usd(), c.plus(CostEstimate.zero()).usd());
  }

  @Test
  void plusNullThrowsNullPointerException() {
    var c = new CostEstimate(new BigDecimal("0.10"));
    var ex = assertThrows(NullPointerException.class, () -> c.plus(null));
    assertEquals("other must not be null", ex.getMessage());
  }
}
