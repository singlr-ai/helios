/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

/**
 * "Dreaming" pass over a finished session: an offline analyzer that reads recent history + current
 * memory state and proposes consolidated block updates. Typically scheduled via {@code
 * io.helidon.scheduling} between sessions, or fired manually after {@link MemoryEvent.SessionEnd}.
 *
 * <p>The contract is read-mostly: the consolidator inspects {@link ConsolidationContext} but does
 * not mutate {@link Memory} directly. It returns a {@link ConsolidationReport} that the caller
 * applies via {@link ConsolidationReport#apply(Memory, ApplyMode)} — three application modes let
 * deployers choose between auto-apply, suggest-only, and quarantine.
 *
 * <p>Reference implementation: {@link LlmMemoryConsolidator} uses a Model with structured output to
 * produce {@link ConsolidationReport.BlockUpdate}s. Deployers can plug in vector-store-based
 * consolidators, rules-based extractors, or hybrid strategies via this same interface.
 */
public interface MemoryConsolidator {

  /**
   * Run a consolidation pass over the supplied context. The returned report is purely advisory
   * until {@link ConsolidationReport#apply} is called.
   */
  ConsolidationReport consolidate(ConsolidationContext context);

  /** How aggressively to apply a {@link ConsolidationReport} to the memory store. */
  enum ApplyMode {
    /**
     * Write every suggested block update directly. Use when the consolidator's quality has been
     * validated and operator approval is not required.
     */
    AUTO_APPLY,

    /**
     * Do not write anything. The caller surfaces the report to a user/operator for manual review.
     * Recommended default.
     */
    SUGGEST_ONLY,

    /**
     * Merge every suggestion into a single quarantine block named {@code pending_consolidation} so
     * a future session can review and selectively apply. The quarantine block is created if it
     * doesn't exist.
     */
    QUARANTINE
  }
}
