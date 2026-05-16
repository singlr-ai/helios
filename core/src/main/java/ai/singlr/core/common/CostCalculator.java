/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.common;

import ai.singlr.core.model.Response.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Per-model {@code Usage → CostEstimate} lookup. Plug a calculator into a session and every
 * completed model turn contributes to the session's accumulated cost; combined with {@code
 * SessionLimits.maxBudgetMicroUsd} the loop terminates when the budget is exceeded.
 *
 * <p>The framework deliberately ships no rate cards. Public model prices change too often for a
 * library release cadence to keep up with, and a stale baked-in table is worse than {@link #ZERO}
 * since it silently under-bills. Compose a {@link #staticTable(Map)} at application startup from
 * whatever pricing source the deployer trusts (vendor docs, billing API, internal catalog) and
 * refresh it on the schedule that matches the deployer's reconciliation cycle.
 *
 * <h2>Currency representation</h2>
 *
 * Rates and amounts are integer micro-USD (1 microUSD = $10⁻⁶) following the Stripe-style
 * fixed-precision pattern. See {@link CostEstimate} for the rationale.
 */
@FunctionalInterface
public interface CostCalculator {

  /**
   * No-op calculator that always returns {@link CostEstimate#zero()}. The default for sessions that
   * have not wired a calculator — cost tracking is opt-in.
   */
  CostCalculator ZERO = (modelId, usage) -> CostEstimate.zero();

  /**
   * Compute the cost of a single model turn.
   *
   * @param modelId the {@code Model.id()} that produced the turn; non-null
   * @param usage the token usage reported by the turn; non-null
   * @return the cost contribution as a non-negative {@link CostEstimate}
   */
  CostEstimate cost(String modelId, Usage usage);

  /**
   * Build a calculator from a fixed pricing table keyed by {@code Model.id()}. Models absent from
   * the table contribute {@link CostEstimate#zero()} — there is no fallback rate, since a missing
   * entry is more likely a configuration gap than something the framework should guess at.
   *
   * <p>The table is defensively copied; later mutations to {@code pricing} do not affect the
   * returned calculator.
   *
   * @param pricing model id → pricing; non-null, may be empty
   * @return a {@code CostCalculator} backed by the snapshot
   * @throws NullPointerException if {@code pricing} is null or contains a null key/value
   */
  static CostCalculator staticTable(Map<String, Pricing> pricing) {
    Objects.requireNonNull(pricing, "pricing must not be null");
    var snapshot = Map.copyOf(pricing);
    return (modelId, usage) -> {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      var rate = snapshot.get(modelId);
      return rate == null ? CostEstimate.zero() : rate.cost(usage);
    };
  }

  /**
   * Per-million-token input and output rates for a single model. Rates are expressed in micro-USD
   * per million tokens; storing per-million matches the unit every public rate card today publishes
   * (e.g., "$15 per million input tokens") and keeps {@link #cost(Usage)} as an integer
   * multiply-then-divide.
   *
   * @param inputMicroUsdPerMillion micro-USD per million input tokens; non-negative
   * @param outputMicroUsdPerMillion micro-USD per million output tokens; non-negative
   */
  record Pricing(long inputMicroUsdPerMillion, long outputMicroUsdPerMillion) {

    private static final long TOKENS_PER_MILLION = 1_000_000L;

    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if either rate is negative
     */
    public Pricing {
      if (inputMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "inputMicroUsdPerMillion must be non-negative, got " + inputMicroUsdPerMillion);
      }
      if (outputMicroUsdPerMillion < 0L) {
        throw new IllegalArgumentException(
            "outputMicroUsdPerMillion must be non-negative, got " + outputMicroUsdPerMillion);
      }
    }

    /**
     * Convenience for rates expressed in dollars-per-million-tokens. Tests and scripts; production
     * callers should prefer the canonical constructor with raw micro-USD to avoid the
     * floating-point conversion. Fractional micro-USD round half-up.
     *
     * @param inputUsdPerMillion USD per million input tokens; non-negative
     * @param outputUsdPerMillion USD per million output tokens; non-negative
     * @return a {@code Pricing}
     */
    public static Pricing ofUsdPerMillion(double inputUsdPerMillion, double outputUsdPerMillion) {
      return new Pricing(
          Math.round(inputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD),
          Math.round(outputUsdPerMillion * CostEstimate.MICRO_USD_PER_USD));
    }

    /**
     * Compute the cost of one turn's usage at this pricing. Integer math: {@code (tokens × rate) /
     * 1_000_000}. Sub-microUSD per-token amounts truncate to zero, which is exact for typical
     * turn-sized aggregates and meaningless at the per-token margin.
     *
     * @param usage the token usage; non-null
     * @return the cost as a non-negative {@link CostEstimate}
     * @throws ArithmeticException on overflow (rate × tokens exceeds {@link Long#MAX_VALUE})
     */
    public CostEstimate cost(Usage usage) {
      Objects.requireNonNull(usage, "usage must not be null");
      var inputMicro =
          Math.multiplyExact((long) usage.inputTokens(), inputMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      var outputMicro =
          Math.multiplyExact((long) usage.outputTokens(), outputMicroUsdPerMillion)
              / TOKENS_PER_MILLION;
      return CostEstimate.ofMicroUsd(Math.addExact(inputMicro, outputMicro));
    }
  }
}
