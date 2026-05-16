/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.CostCalculator.Pricing;
import ai.singlr.core.model.Response.Usage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CostCalculatorTest {

  // ── ZERO sentinel ────────────────────────────────────────────────────────

  @Test
  void zeroSentinelAlwaysReturnsZeroCost() {
    assertSame(CostEstimate.zero(), CostCalculator.ZERO.cost("any-model", Usage.of(1_000, 500)));
    assertSame(CostEstimate.zero(), CostCalculator.ZERO.cost("other", Usage.of(0, 0)));
  }

  // ── Pricing record ───────────────────────────────────────────────────────

  @Test
  void pricingComputesInputPlusOutputDividedByMillion() {
    // Opus-style: $3 input ($15 input)/M, $15 output/M.
    var p = new Pricing(3_000_000L, 15_000_000L);
    // 1 000 input * 3 microUSD = 3 000 microUSD;  500 output * 15 = 7 500;  total 10 500 microUSD.
    assertEquals(10_500L, p.cost(Usage.of(1_000, 500)).microUsd());
  }

  @Test
  void pricingZeroUsageProducesZeroCost() {
    var p = new Pricing(3_000_000L, 15_000_000L);
    assertSame(CostEstimate.zero(), p.cost(Usage.of(0, 0)));
  }

  @Test
  void pricingTruncatesSubMicroUsdPerToken() {
    // Haiku-ish: $0.80 input/M = 800 000 microUSD/M = 0.8 microUSD/token.
    // 1 token alone truncates to 0; 10 tokens = 8 microUSD; 1000 tokens = 800 microUSD.
    var p = new Pricing(800_000L, 0L);
    assertEquals(0L, p.cost(Usage.of(1, 0)).microUsd());
    assertEquals(8L, p.cost(Usage.of(10, 0)).microUsd());
    assertEquals(800L, p.cost(Usage.of(1_000, 0)).microUsd());
  }

  @Test
  void pricingOfFactoryRoundsHalfUp() {
    var p = Pricing.ofUsdPerMillion(3.0, 15.0);
    assertEquals(3_000_000L, p.inputMicroUsdPerMillion());
    assertEquals(15_000_000L, p.outputMicroUsdPerMillion());
  }

  @Test
  void pricingRejectsNegativeInputRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(-1L, 0L));
    assertEquals("inputMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingRejectsNegativeOutputRate() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Pricing(0L, -1L));
    assertEquals("outputMicroUsdPerMillion must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void pricingRejectsNullUsage() {
    var p = Pricing.ofUsdPerMillion(1.0, 1.0);
    var ex = assertThrows(NullPointerException.class, () -> p.cost(null));
    assertEquals("usage must not be null", ex.getMessage());
  }

  @Test
  void pricingOverflowOnExtremeRate() {
    // Rate at near-Long.MAX_VALUE microUSD/M with non-trivial token count overflows.
    var p = new Pricing(Long.MAX_VALUE, 0L);
    assertThrows(ArithmeticException.class, () -> p.cost(Usage.of(2, 0)));
  }

  // ── staticTable factory ──────────────────────────────────────────────────

  @Test
  void staticTableEmptyMapReturnsZeroForAnyModel() {
    var calc = CostCalculator.staticTable(Map.of());
    assertSame(CostEstimate.zero(), calc.cost("any-model", Usage.of(1_000, 1_000)));
  }

  @Test
  void staticTableLookupAppliesPricingWhenModelMatches() {
    var calc =
        CostCalculator.staticTable(
            Map.of(
                "model-A", Pricing.ofUsdPerMillion(3.0, 15.0),
                "model-B", Pricing.ofUsdPerMillion(0.3, 2.5)));
    // 1 M input * $3/M = $3 = 3_000_000 microUSD.
    assertEquals(3_000_000L, calc.cost("model-A", Usage.of(1_000_000, 0)).microUsd());
    // 1 M output * $2.5/M = $2.5 = 2_500_000 microUSD.
    assertEquals(2_500_000L, calc.cost("model-B", Usage.of(0, 1_000_000)).microUsd());
  }

  @Test
  void staticTableMissingModelReturnsZero() {
    var calc = CostCalculator.staticTable(Map.of("known", Pricing.ofUsdPerMillion(1.0, 1.0)));
    assertSame(CostEstimate.zero(), calc.cost("unknown", Usage.of(1_000_000, 1_000_000)));
  }

  @Test
  void staticTableDefensivelyCopiesPricing() {
    var mutable = new HashMap<String, Pricing>();
    mutable.put("m", Pricing.ofUsdPerMillion(3.0, 15.0));
    var calc = CostCalculator.staticTable(mutable);
    mutable.clear();
    // Calculator still sees the original entry — proves the snapshot was taken.
    assertEquals(3_000_000L, calc.cost("m", Usage.of(1_000_000, 0)).microUsd());
  }

  @Test
  void staticTableRejectsNullPricingMap() {
    var ex = assertThrows(NullPointerException.class, () -> CostCalculator.staticTable(null));
    assertEquals("pricing must not be null", ex.getMessage());
  }

  @Test
  void staticTableLookupRejectsNullModelId() {
    var calc = CostCalculator.staticTable(Map.of());
    var ex = assertThrows(NullPointerException.class, () -> calc.cost(null, Usage.of(1, 1)));
    assertEquals("modelId must not be null", ex.getMessage());
  }

  @Test
  void staticTableLookupRejectsNullUsage() {
    var calc = CostCalculator.staticTable(Map.of());
    var ex = assertThrows(NullPointerException.class, () -> calc.cost("m", null));
    assertEquals("usage must not be null", ex.getMessage());
  }
}
