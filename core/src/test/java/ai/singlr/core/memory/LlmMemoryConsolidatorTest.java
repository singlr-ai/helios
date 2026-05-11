/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LlmMemoryConsolidatorTest {

  /** Model that returns a fixed structured-output payload for the consolidator schema. */
  private static final class FakeModel implements Model {
    private final Function<OutputSchema<?>, Object> parsedFactory;
    final List<String> capturedPrompts = new ArrayList<>();

    FakeModel(Function<OutputSchema<?>, Object> parsedFactory) {
      this.parsedFactory = parsedFactory;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Response<T> chat(
        List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
      capturedPrompts.add(messages.getFirst().content());
      var parsed = (T) parsedFactory.apply(outputSchema);
      return Response.<T>newBuilder(outputSchema.type())
          .withParsed(parsed)
          .withFinishReason(FinishReason.STOP)
          .build();
    }

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void rejectsNullModel() {
    assertThrows(IllegalArgumentException.class, () -> new LlmMemoryConsolidator(null));
  }

  @Test
  void rejectsNonPositiveHistoryBudget() {
    var model = new FakeModel(s -> null);
    assertThrows(IllegalArgumentException.class, () -> new LlmMemoryConsolidator(model, 0));
    assertThrows(IllegalArgumentException.class, () -> new LlmMemoryConsolidator(model, -1));
  }

  @Test
  void exposesModelAndHistoryBudget() {
    var model = new FakeModel(s -> null);
    var consolidator = new LlmMemoryConsolidator(model, 1234);
    assertEquals(model, consolidator.model());
    assertEquals(1234, consolidator.maxHistoryChars());
  }

  @Test
  void rejectsNullContext() {
    var consolidator = new LlmMemoryConsolidator(new FakeModel(s -> null));
    assertThrows(IllegalArgumentException.class, () -> consolidator.consolidate(null));
  }

  @Test
  void parsedNullProducesEmptyReportWithLowConfidence() {
    var consolidator = new LlmMemoryConsolidator(new FakeModel(s -> null));
    var ctx =
        new ConsolidationContext(
            "agent", "user", InMemoryMemory.withDefaults(), List.of(Message.user("hi")));

    var report = consolidator.consolidate(ctx);

    assertEquals(0, report.suggestedBlockUpdates().size());
    assertEquals(Confidence.LOW, report.confidence());
  }

  @Test
  void translatesLlmOutputIntoReport() {
    var fake =
        new FakeModel(
            schema ->
                new LlmMemoryConsolidator.LlmOutput(
                    List.of(
                        new LlmMemoryConsolidator.BlockUpdateProposal(
                            MemoryBlocks.USER_PROFILE,
                            List.of(
                                new LlmMemoryConsolidator.KeyValue("name", "Alice"),
                                new LlmMemoryConsolidator.KeyValue("tz", "PT")),
                            "User said so",
                            false)),
                    List.of("dup-1"),
                    List.of("theme-1"),
                    "MEDIUM",
                    "Reasoning here"));
    var consolidator = new LlmMemoryConsolidator(fake);
    var ctx =
        new ConsolidationContext(
            "agent",
            "user",
            InMemoryMemory.withDefaults(),
            List.of(Message.user("call me Alice; I'm in PT")));

    var report = consolidator.consolidate(ctx);

    assertEquals(1, report.suggestedBlockUpdates().size());
    var update = report.suggestedBlockUpdates().getFirst();
    assertEquals(MemoryBlocks.USER_PROFILE, update.blockName());
    assertEquals("Alice", update.data().get("name"));
    assertEquals("PT", update.data().get("tz"));
    assertEquals(false, update.replaceWhole());
    assertEquals(Confidence.MEDIUM, report.confidence());
    assertEquals(1, report.droppedRedundancies().size());
    assertEquals(1, report.identifiedThemes().size());
  }

  @Test
  void skipsProposalsWithBlankBlockName() {
    var fake =
        new FakeModel(
            schema ->
                new LlmMemoryConsolidator.LlmOutput(
                    List.of(
                        new LlmMemoryConsolidator.BlockUpdateProposal(
                            "",
                            List.of(new LlmMemoryConsolidator.KeyValue("k", "v")),
                            "skip",
                            false),
                        new LlmMemoryConsolidator.BlockUpdateProposal(
                            MemoryBlocks.USER_PROFILE, List.of(), "valid", false)),
                    null,
                    null,
                    "LOW",
                    ""));
    var consolidator = new LlmMemoryConsolidator(fake);

    var report =
        consolidator.consolidate(
            new ConsolidationContext("a", "u", new InMemoryMemory(), List.of(Message.user("x"))));

    assertEquals(1, report.suggestedBlockUpdates().size());
    assertEquals(MemoryBlocks.USER_PROFILE, report.suggestedBlockUpdates().getFirst().blockName());
  }

  @Test
  void unknownConfidenceFallsBackToLow() {
    var fake =
        new FakeModel(
            schema ->
                new LlmMemoryConsolidator.LlmOutput(List.of(), null, null, "??BAD-VALUE??", ""));
    var consolidator = new LlmMemoryConsolidator(fake);

    var report =
        consolidator.consolidate(
            new ConsolidationContext("a", "u", new InMemoryMemory(), List.of(Message.user("x"))));

    assertEquals(Confidence.LOW, report.confidence());
  }

  @Test
  void promptIncludesExistingBlocksAndHistory() {
    var fake =
        new FakeModel(
            schema -> new LlmMemoryConsolidator.LlmOutput(List.of(), null, null, "LOW", ""));
    var consolidator = new LlmMemoryConsolidator(fake);
    var memory = InMemoryMemory.withDefaults();
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "PriorAlice");

    consolidator.consolidate(
        new ConsolidationContext(
            "agent42", "user42", memory, List.of(Message.user("user said something"))));

    var prompt = fake.capturedPrompts.getFirst();
    assertTrue(prompt.contains("PriorAlice"), "prompt must surface existing block content");
    assertTrue(prompt.contains("user said something"), "prompt must include the user message");
    assertTrue(prompt.contains("agent42"));
    assertTrue(prompt.contains("user42"));
  }

  @Test
  void longHistoryGetsTruncatedToBudget() {
    var fake =
        new FakeModel(
            schema -> new LlmMemoryConsolidator.LlmOutput(List.of(), null, null, "LOW", ""));
    var consolidator = new LlmMemoryConsolidator(fake, 200);
    var big = "x".repeat(2000);

    consolidator.consolidate(
        new ConsolidationContext("a", "u", new InMemoryMemory(), List.of(Message.user(big))));

    var prompt = fake.capturedPrompts.getFirst();
    assertTrue(prompt.contains("History truncated to 200 chars"));
  }

  @Test
  void emptyMemoryRendersAsNoneInPrompt() {
    var fake =
        new FakeModel(
            schema -> new LlmMemoryConsolidator.LlmOutput(List.of(), null, null, "LOW", ""));
    var consolidator = new LlmMemoryConsolidator(fake);

    consolidator.consolidate(
        new ConsolidationContext("a", "u", new InMemoryMemory(), List.of(Message.user("hi"))));

    assertTrue(fake.capturedPrompts.getFirst().contains("EXISTING MEMORY BLOCKS: (none)"));
  }

  @Test
  void reportFromLlmOutputIsExposedForTests() {
    var output =
        new LlmMemoryConsolidator.LlmOutput(
            List.of(
                new LlmMemoryConsolidator.BlockUpdateProposal(
                    "k", List.of(new LlmMemoryConsolidator.KeyValue("foo", "bar")), "x", true)),
            List.of(),
            List.of(),
            "HIGH",
            "n");
    var report = LlmMemoryConsolidator.reportFromLlmOutput(output);
    assertNotNull(report);
    assertEquals(Confidence.HIGH, report.confidence());
    assertEquals(true, report.suggestedBlockUpdates().getFirst().replaceWhole());
  }
}
