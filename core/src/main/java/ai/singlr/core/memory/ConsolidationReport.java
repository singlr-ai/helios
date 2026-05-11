/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.common.Confidence;
import java.util.List;
import java.util.Map;

/**
 * Output of a {@link MemoryConsolidator} pass. Lists the block updates the consolidator suggests,
 * the redundancies it identified, the themes it noticed, an overall confidence rating, and a
 * free-text narrative the consolidator wrote describing its reasoning.
 *
 * <p>{@link #apply} writes the suggested updates back into the supplied {@link Memory}. The {@link
 * MemoryConsolidator.ApplyMode} parameter controls semantics:
 *
 * <ul>
 *   <li>{@link MemoryConsolidator.ApplyMode#AUTO_APPLY} — every suggested block update is written
 *       directly via {@link Memory#updateBlock} or {@link Memory#putBlock} (when the block doesn't
 *       exist yet).
 *   <li>{@link MemoryConsolidator.ApplyMode#SUGGEST_ONLY} — no writes happen. Caller surfaces the
 *       report to the user/operator for manual review.
 *   <li>{@link MemoryConsolidator.ApplyMode#QUARANTINE} — every suggestion is written to a single
 *       quarantine block named {@code pending_consolidation} so a future session can review it.
 * </ul>
 *
 * @param suggestedBlockUpdates block-level updates the consolidator wants to make
 * @param droppedRedundancies free-text descriptions of duplicates / redundancies the consolidator
 *     spotted (informational; not applied to memory automatically)
 * @param identifiedThemes free-text descriptions of recurring themes / patterns
 * @param confidence overall confidence in the suggestions
 * @param narrative free-text reasoning the consolidator wrote
 */
public record ConsolidationReport(
    List<BlockUpdate> suggestedBlockUpdates,
    List<String> droppedRedundancies,
    List<String> identifiedThemes,
    Confidence confidence,
    String narrative) {

  /** Quarantine block name used by {@link MemoryConsolidator.ApplyMode#QUARANTINE}. */
  public static final String QUARANTINE_BLOCK = "pending_consolidation";

  public ConsolidationReport {
    suggestedBlockUpdates =
        suggestedBlockUpdates == null ? List.of() : List.copyOf(suggestedBlockUpdates);
    droppedRedundancies =
        droppedRedundancies == null ? List.of() : List.copyOf(droppedRedundancies);
    identifiedThemes = identifiedThemes == null ? List.of() : List.copyOf(identifiedThemes);
    if (confidence == null) {
      confidence = Confidence.LOW;
    }
  }

  /**
   * A single suggested block update.
   *
   * @param blockName the block this update targets; {@link MemoryBlocks} constants are recommended
   * @param data the data to write — full replacement when {@code replaceWhole} is true, otherwise
   *     merged into the existing block
   * @param rationale free-text explanation of why this update was suggested; surfaced in audit
   * @param replaceWhole when {@code true} the consolidator wants {@link Memory#replaceBlock};
   *     otherwise individual {@link Memory#updateBlock} calls
   */
  public record BlockUpdate(
      String blockName, Map<String, Object> data, String rationale, boolean replaceWhole) {
    public BlockUpdate {
      if (blockName == null || blockName.isBlank()) {
        throw new IllegalArgumentException("blockName must not be blank");
      }
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  /**
   * Apply the suggested block updates to {@code memory} according to {@code mode}. Returns the
   * number of block writes performed; for {@link MemoryConsolidator.ApplyMode#SUGGEST_ONLY} this is
   * always zero.
   *
   * <p>For {@link MemoryConsolidator.ApplyMode#AUTO_APPLY}, blocks that don't yet exist are created
   * via {@link Memory#putBlock} using {@link MemoryBlocks#workingMemory} defaults. Existing blocks
   * receive either {@link Memory#replaceBlock} (when {@link BlockUpdate#replaceWhole} is true) or a
   * sequence of {@link Memory#updateBlock} calls per key.
   *
   * <p>For {@link MemoryConsolidator.ApplyMode#QUARANTINE}, all suggestions are merged into a
   * single named block {@code pending_consolidation}, created if absent.
   */
  public int apply(Memory memory, MemoryConsolidator.ApplyMode mode) {
    if (memory == null) {
      throw new IllegalArgumentException("memory must not be null");
    }
    if (mode == null) {
      throw new IllegalArgumentException("mode must not be null");
    }
    return switch (mode) {
      case SUGGEST_ONLY -> 0;
      case AUTO_APPLY -> applyAuto(memory);
      case QUARANTINE -> applyQuarantine(memory);
    };
  }

  private int applyAuto(Memory memory) {
    var writes = 0;
    for (var update : suggestedBlockUpdates) {
      // Defensive: reject the dangerous combination of replaceWhole=true + empty data on an
      // existing block. Without this guard the call would wipe the block — a model-producible
      // mistake that auto-apply would commit silently. Empty data with replaceWhole=false is
      // also a no-op (the inner loop has nothing to write), so we skip it too to keep the
      // returned write count truthful.
      if (update.data().isEmpty()) {
        continue;
      }
      if (memory.block(update.blockName()).isEmpty()) {
        memory.putBlock(
            MemoryBlock.newBuilder()
                .withName(update.blockName())
                .withDescription(update.rationale())
                .withData(update.data())
                .build());
      } else if (update.replaceWhole()) {
        memory.replaceBlock(update.blockName(), update.data());
      } else {
        for (var entry : update.data().entrySet()) {
          memory.updateBlock(update.blockName(), entry.getKey(), entry.getValue());
        }
      }
      writes++;
    }
    return writes;
  }

  private int applyQuarantine(Memory memory) {
    if (memory.block(QUARANTINE_BLOCK).isEmpty()) {
      memory.putBlock(
          MemoryBlock.newBuilder()
              .withName(QUARANTINE_BLOCK)
              .withDescription("Pending consolidation suggestions — review before applying.")
              .withMaxSize(MemoryBlocks.DEFAULT_WORKING_MEMORY_MAX_SIZE)
              .build());
    }
    var writes = 0;
    for (var update : suggestedBlockUpdates) {
      memory.updateBlock(QUARANTINE_BLOCK, update.blockName(), update.data());
      writes++;
    }
    return writes;
  }
}
