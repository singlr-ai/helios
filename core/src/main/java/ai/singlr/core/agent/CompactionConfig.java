/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

/**
 * Tuning knobs for {@link DefaultContextCompactor}. Defaults track Hermes Agent production values
 * (50% threshold for the cheap prune pass, 85% threshold to summarize, 20% of the threshold token
 * budget reserved as protected tail, first three messages protected verbatim, and tool results over
 * 200 chars get cleared during the prune pass).
 *
 * @param earlyPruneThreshold fraction of {@code model.contextWindow()} above which Phase 1 (cheap
 *     tool-result pruning) runs. Default {@value #DEFAULT_EARLY_PRUNE_THRESHOLD}.
 * @param summaryThreshold fraction above which Phase 3 (structured summary) also runs. Default
 *     {@value #DEFAULT_SUMMARY_THRESHOLD}.
 * @param protectFirstN messages at the head always preserved verbatim (system prompt + initial user
 *     turn). Default {@value #DEFAULT_PROTECT_FIRST_N}.
 * @param protectLastN minimum messages at the tail always preserved verbatim, even when the {@code
 *     targetTailRatio} token budget would preserve fewer. Default {@value #DEFAULT_PROTECT_LAST_N}.
 * @param targetTailRatio fraction of the threshold token budget reserved as protected tail (recent
 *     messages). Walking backward from the end, the compactor accumulates tokens until {@code
 *     threshold_tokens × targetTailRatio} is exhausted. Default {@value
 *     #DEFAULT_TARGET_TAIL_RATIO}.
 * @param toolResultPruneSize tool result content longer than this character count gets replaced in
 *     Phase 1 with a "cleared to save context space" stub. Default {@value
 *     #DEFAULT_TOOL_RESULT_PRUNE_SIZE}.
 * @param maxFailures consecutive failures of the summary model call before falling back to prune
 *     only until success. Default {@value #DEFAULT_MAX_FAILURES}.
 */
public record CompactionConfig(
    double earlyPruneThreshold,
    double summaryThreshold,
    int protectFirstN,
    int protectLastN,
    double targetTailRatio,
    int toolResultPruneSize,
    int maxFailures) {

  /** Default early-prune threshold (50% of context window). */
  public static final double DEFAULT_EARLY_PRUNE_THRESHOLD = 0.50;

  /** Default summary threshold (85% of context window). */
  public static final double DEFAULT_SUMMARY_THRESHOLD = 0.85;

  /** Default head messages protected (system prompt + initial user turn). */
  public static final int DEFAULT_PROTECT_FIRST_N = 3;

  /** Default minimum tail messages protected. */
  public static final int DEFAULT_PROTECT_LAST_N = 20;

  /** Default target tail ratio (20% of threshold tokens). */
  public static final double DEFAULT_TARGET_TAIL_RATIO = 0.20;

  /** Default tool result prune size (chars). */
  public static final int DEFAULT_TOOL_RESULT_PRUNE_SIZE = 200;

  /** Default max consecutive summary-call failures before falling back to prune-only. */
  public static final int DEFAULT_MAX_FAILURES = 3;

  /** A {@link CompactionConfig} pre-populated with the Hermes-style production defaults. */
  public static CompactionConfig defaults() {
    return new CompactionConfig(
        DEFAULT_EARLY_PRUNE_THRESHOLD,
        DEFAULT_SUMMARY_THRESHOLD,
        DEFAULT_PROTECT_FIRST_N,
        DEFAULT_PROTECT_LAST_N,
        DEFAULT_TARGET_TAIL_RATIO,
        DEFAULT_TOOL_RESULT_PRUNE_SIZE,
        DEFAULT_MAX_FAILURES);
  }

  /** Compact validation — fires on construction. */
  public CompactionConfig {
    if (earlyPruneThreshold <= 0 || earlyPruneThreshold > 1) {
      throw new IllegalArgumentException(
          "earlyPruneThreshold must be in (0,1]: " + earlyPruneThreshold);
    }
    if (summaryThreshold <= 0 || summaryThreshold > 1) {
      throw new IllegalArgumentException("summaryThreshold must be in (0,1]: " + summaryThreshold);
    }
    if (summaryThreshold < earlyPruneThreshold) {
      throw new IllegalArgumentException(
          "summaryThreshold must be >= earlyPruneThreshold: "
              + summaryThreshold
              + " < "
              + earlyPruneThreshold);
    }
    if (protectFirstN < 0) {
      throw new IllegalArgumentException("protectFirstN must be >= 0: " + protectFirstN);
    }
    if (protectLastN < 0) {
      throw new IllegalArgumentException("protectLastN must be >= 0: " + protectLastN);
    }
    if (targetTailRatio < 0 || targetTailRatio > 1) {
      throw new IllegalArgumentException("targetTailRatio must be in [0,1]: " + targetTailRatio);
    }
    if (toolResultPruneSize < 0) {
      throw new IllegalArgumentException(
          "toolResultPruneSize must be >= 0: " + toolResultPruneSize);
    }
    if (maxFailures < 0) {
      throw new IllegalArgumentException("maxFailures must be >= 0: " + maxFailures);
    }
  }
}
