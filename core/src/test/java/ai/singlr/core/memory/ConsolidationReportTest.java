/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Confidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsolidationReportTest {

  private static ConsolidationReport sampleReport(boolean replaceWhole) {
    return new ConsolidationReport(
        List.of(
            new ConsolidationReport.BlockUpdate(
                MemoryBlocks.USER_PROFILE,
                Map.of("name", "Alice", "tz", "PT"),
                "Stated directly by user",
                replaceWhole)),
        List.of("redundant fact about timezone in two turns"),
        List.of("scheduling"),
        Confidence.MEDIUM,
        "User mentioned name and timezone explicitly across two turns.");
  }

  // --- Constructor & normalization --------------------------------------------------------------

  @Test
  void nullListsBecomeEmpty() {
    var report = new ConsolidationReport(null, null, null, null, "");
    assertTrue(report.suggestedBlockUpdates().isEmpty());
    assertTrue(report.droppedRedundancies().isEmpty());
    assertTrue(report.identifiedThemes().isEmpty());
    assertEquals(Confidence.LOW, report.confidence());
  }

  @Test
  void blockUpdateRejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsolidationReport.BlockUpdate("", Map.of(), "rationale", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsolidationReport.BlockUpdate(null, Map.of(), "rationale", false));
  }

  @Test
  void blockUpdateNullDataBecomesEmptyMap() {
    var update = new ConsolidationReport.BlockUpdate("name", null, "r", false);
    assertTrue(update.data().isEmpty());
  }

  // --- Apply: SUGGEST_ONLY ----------------------------------------------------------------------

  @Test
  void applySuggestOnlyMakesNoWrites() {
    var memory = new InMemoryMemory();
    var report = sampleReport(false);

    var writes = report.apply(memory, MemoryConsolidator.ApplyMode.SUGGEST_ONLY);

    assertEquals(0, writes);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  // --- Apply: AUTO_APPLY ------------------------------------------------------------------------

  @Test
  void applyAutoCreatesMissingBlocks() {
    var memory = new InMemoryMemory();
    var report = sampleReport(false);

    var writes = report.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    assertEquals(1, writes);
    var block = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", block.value("name"));
    assertEquals("PT", block.value("tz"));
  }

  @Test
  void applyAutoMergesIntoExistingBlock() {
    var memory = InMemoryMemory.withDefaults();
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "Old");
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "color", "blue");
    var report = sampleReport(false);

    report.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    var block = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", block.value("name"), "merged keys must overwrite");
    assertEquals("blue", block.value("color"), "untouched keys must remain");
  }

  @Test
  void applyAutoReplacesWholeWhenFlagged() {
    var memory = InMemoryMemory.withDefaults();
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "Old");
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "color", "blue");
    var report = sampleReport(true);

    report.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    var block = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", block.value("name"));
    assertEquals("PT", block.value("tz"));
    assertTrue(block.data().get("color") == null, "old keys must be removed by replaceWhole");
  }

  // --- Apply: QUARANTINE ------------------------------------------------------------------------

  @Test
  void applyQuarantineCreatesPendingBlock() {
    var memory = new InMemoryMemory();
    var report = sampleReport(false);

    var writes = report.apply(memory, MemoryConsolidator.ApplyMode.QUARANTINE);

    assertEquals(1, writes);
    var pending = memory.block(ConsolidationReport.QUARANTINE_BLOCK).orElseThrow();
    assertTrue(pending.data().containsKey(MemoryBlocks.USER_PROFILE));
  }

  @Test
  void applyQuarantineReusesExistingPendingBlock() {
    var memory = new InMemoryMemory();
    memory.putBlock(
        MemoryBlock.newBuilder()
            .withName(ConsolidationReport.QUARANTINE_BLOCK)
            .withValue("prior", Map.of("k", "v"))
            .build());
    var report = sampleReport(false);

    report.apply(memory, MemoryConsolidator.ApplyMode.QUARANTINE);

    var pending = memory.block(ConsolidationReport.QUARANTINE_BLOCK).orElseThrow();
    assertEquals(2, pending.data().size(), "prior key must be preserved alongside the new one");
  }

  // --- Apply: null handling ---------------------------------------------------------------------

  @Test
  void applyRejectsNullMemory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> sampleReport(false).apply(null, MemoryConsolidator.ApplyMode.SUGGEST_ONLY));
  }

  @Test
  void applyRejectsNullMode() {
    var memory = new InMemoryMemory();
    assertThrows(IllegalArgumentException.class, () -> sampleReport(false).apply(memory, null));
  }

  @Test
  void applyEmptyReportIsNoOp() {
    var memory = InMemoryMemory.withDefaults();
    var beforeBlocks = memory.coreBlocks().size();
    var emptyReport = new ConsolidationReport(List.of(), List.of(), List.of(), Confidence.HIGH, "");

    emptyReport.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    assertEquals(beforeBlocks, memory.coreBlocks().size());
  }

  @Test
  void applyAutoRefusesReplaceWholeWithEmptyDataOnExistingBlock() {
    var memory = InMemoryMemory.withDefaults();
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "Alice");
    var dangerousReport =
        new ConsolidationReport(
            List.of(
                new ConsolidationReport.BlockUpdate(
                    MemoryBlocks.USER_PROFILE, Map.of(), "wipe attempt", true)),
            List.of(),
            List.of(),
            Confidence.LOW,
            "");

    var writes = dangerousReport.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    assertEquals(0, writes, "empty-data wipe must not count as a write");
    assertEquals(
        "Alice",
        memory.block(MemoryBlocks.USER_PROFILE).orElseThrow().value("name"),
        "existing block content must survive an empty-data replaceWhole proposal");
  }

  @Test
  void applyAutoSkipsEmptyDataUpdate() {
    var memory = InMemoryMemory.withDefaults();
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "Alice");
    var report =
        new ConsolidationReport(
            List.of(
                new ConsolidationReport.BlockUpdate(
                    MemoryBlocks.USER_PROFILE, Map.of(), "nothing to merge", false)),
            List.of(),
            List.of(),
            Confidence.LOW,
            "");

    var writes = report.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    assertEquals(0, writes, "empty-data update is a no-op and must not be counted");
    assertEquals("Alice", memory.block(MemoryBlocks.USER_PROFILE).orElseThrow().value("name"));
  }

  @Test
  void applyAutoSkipsEmptyDataOnMissingBlock() {
    var memory = new InMemoryMemory();
    var report =
        new ConsolidationReport(
            List.of(new ConsolidationReport.BlockUpdate("new_block", Map.of(), "skip me", false)),
            List.of(),
            List.of(),
            Confidence.LOW,
            "");

    var writes = report.apply(memory, MemoryConsolidator.ApplyMode.AUTO_APPLY);

    assertEquals(0, writes);
    assertTrue(memory.block("new_block").isEmpty(), "no block should be created for empty data");
  }
}
