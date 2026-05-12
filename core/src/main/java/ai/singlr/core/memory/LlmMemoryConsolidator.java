/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Role;
import ai.singlr.core.schema.OutputSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reference {@link MemoryConsolidator} that delegates the analysis to a Model via structured
 * output. The Model reads the session history + a snapshot of existing memory blocks, then emits a
 * {@link LlmOutput} matching the configured {@link OutputSchema}. The result is translated to a
 * {@link ConsolidationReport} the caller can apply.
 *
 * <p>The consolidator is stateless — safe to reuse across sessions.
 *
 * <p>System prompt is intentionally explicit about safety constraints: do not invent facts not
 * supported by the history, prefer merging into existing blocks over creating new ones, and surface
 * uncertainty as {@link Confidence#LOW}. The prompt also names the canonical block surfaces ({@link
 * MemoryBlocks#IDENTITY}, {@link MemoryBlocks#USER_PROFILE}, {@link MemoryBlocks#WORKING_MEMORY})
 * so the model writes there by default.
 */
public final class LlmMemoryConsolidator implements MemoryConsolidator {

  private final Model model;
  private final int maxHistoryChars;

  public LlmMemoryConsolidator(Model model) {
    this(model, 20_000);
  }

  public LlmMemoryConsolidator(Model model, int maxHistoryChars) {
    if (model == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    if (maxHistoryChars <= 0) {
      throw new IllegalArgumentException("maxHistoryChars must be > 0");
    }
    this.model = model;
    this.maxHistoryChars = maxHistoryChars;
  }

  @Override
  public ConsolidationReport consolidate(ConsolidationContext context) {
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    var history = renderHistory(context.recentHistory());
    var existingBlocks = renderExistingBlocks(context.memory());
    var prompt = buildPrompt(context.agentId(), context.userId(), existingBlocks, history);

    var schema = OutputSchema.of(LlmOutput.class);
    var response = model.chat(List.of(Message.user(prompt)), List.of(), schema);
    var parsed = response.parsed();
    if (parsed == null) {
      return new ConsolidationReport(
          List.of(),
          List.of(),
          List.of(),
          Confidence.LOW,
          "Consolidator model returned no parsed output.");
    }
    return toReport(parsed);
  }

  private static String renderHistory(List<Message> history) {
    var sb = new StringBuilder();
    sb.append("CONVERSATION HISTORY (recent):\n");
    for (var msg : history) {
      var role =
          msg.role() == Role.TOOL
              ? "TOOL[" + (msg.toolName() == null ? "?" : msg.toolName()) + "]"
              : msg.role().name();
      sb.append("<turn role=\"").append(role).append("\">\n");
      sb.append(msg.content() == null ? "" : msg.content()).append("\n");
      sb.append("</turn>\n");
    }
    return sb.toString();
  }

  private static String renderExistingBlocks(Memory memory) {
    var blocks = memory.coreBlocks();
    if (blocks.isEmpty()) {
      return "EXISTING MEMORY BLOCKS: (none)\n";
    }
    var sb = new StringBuilder("EXISTING MEMORY BLOCKS:\n");
    for (var block : blocks) {
      sb.append(block.render());
    }
    return sb.toString();
  }

  private String buildPrompt(String agentId, String userId, String existingBlocks, String history) {
    var sb = new StringBuilder();
    sb.append("You are a memory consolidator.")
        .append(" Your job is to look at a recent conversation and the agent's existing persistent")
        .append(" memory blocks, then propose updates that compress and clarify what the agent")
        .append(" should remember going forward.\n\n");
    sb.append("AGENT ID: ").append(agentId == null ? "(unset)" : agentId).append("\n");
    sb.append("USER ID: ").append(userId == null ? "(unset)" : userId).append("\n\n");
    sb.append(existingBlocks).append('\n');

    if (history.length() > maxHistoryChars) {
      sb.append("[History truncated to ")
          .append(maxHistoryChars)
          .append(" chars]\n")
          .append(history, 0, maxHistoryChars)
          .append("\n");
    } else {
      sb.append(history).append('\n');
    }

    sb.append(
        """

        RULES (follow exactly):
        1. Do NOT invent facts. Every proposed block update must be supported by the conversation.
        2. Prefer merging into existing blocks over creating new ones. Canonical blocks the
           framework recognizes:
             - 'identity' — agent persona / role / tone (stable across deployments)
             - 'user_profile' — stable facts about the human (name, preferences, role, timezone)
             - 'working_memory' — current task state, open questions, recent decisions
        3. Use replaceWhole=true sparingly — only when the entire block content is being replaced
           with a curated snapshot. Default to incremental key updates (replaceWhole=false).
        4. List redundancies and themes you noticed (these are informational; the framework will
           NOT apply them automatically — operators read them).
        5. Mark overallConfidence honestly: LOW when you're uncertain, MEDIUM when you have
           consistent signal from a few turns, HIGH only when the user explicitly stated a fact.
        6. Write a short narrative explaining the reasoning behind the suggestions.

        Produce the structured JSON output now.
        """);
    return sb.toString();
  }

  private static ConsolidationReport toReport(LlmOutput output) {
    var updates = new ArrayList<ConsolidationReport.BlockUpdate>();
    if (output.blockUpdates() != null) {
      for (var proposal : output.blockUpdates()) {
        if (proposal == null || proposal.blockName() == null || proposal.blockName().isBlank()) {
          continue;
        }
        var data = new LinkedHashMap<String, Object>();
        if (proposal.data() != null) {
          for (var kv : proposal.data()) {
            if (kv != null && kv.key() != null && !kv.key().isBlank()) {
              data.put(kv.key(), kv.value());
            }
          }
        }
        updates.add(
            new ConsolidationReport.BlockUpdate(
                proposal.blockName(),
                data,
                proposal.rationale() == null ? "" : proposal.rationale(),
                Boolean.TRUE.equals(proposal.replaceWhole())));
      }
    }
    Confidence confidence;
    try {
      confidence =
          output.overallConfidence() == null
              ? Confidence.LOW
              : Confidence.fromWire(output.overallConfidence());
    } catch (RuntimeException e) {
      confidence = Confidence.LOW;
    }
    return new ConsolidationReport(
        updates,
        output.redundancies() == null ? List.of() : List.copyOf(output.redundancies()),
        output.themes() == null ? List.of() : List.copyOf(output.themes()),
        confidence,
        output.narrative() == null ? "" : output.narrative());
  }

  /**
   * Internal output shape — schema-generated and translated into {@link ConsolidationReport}.
   *
   * @param blockUpdates proposed block changes the model suggests
   * @param redundancies overlapping facts the model identified across blocks
   * @param themes recurring themes the model surfaced from the recent history
   * @param overallConfidence one of {@code LOW}, {@code MEDIUM}, {@code HIGH}
   * @param narrative free-form summary of what the model did and why
   */
  public record LlmOutput(
      List<BlockUpdateProposal> blockUpdates,
      List<String> redundancies,
      List<String> themes,
      String overallConfidence,
      String narrative) {}

  /**
   * Schema-friendly representation of one suggested block update.
   *
   * @param blockName target block (must be one of the canonical blocks or a known custom one)
   * @param data new key/value pairs to write
   * @param rationale why the model is proposing this change
   * @param replaceWhole {@code true} to replace the block's data outright; {@code false} or {@code
   *     null} for a merge update
   */
  public record BlockUpdateProposal(
      String blockName, List<KeyValue> data, String rationale, Boolean replaceWhole) {}

  /**
   * Schema-friendly representation of a single block key/value pair.
   *
   * @param key the block-data key
   * @param value the block-data value (model outputs are always strings)
   */
  public record KeyValue(String key, String value) {}

  /** Static converter exposed for tests so they can build an {@link LlmOutput} via the schema. */
  static ConsolidationReport reportFromLlmOutput(LlmOutput output) {
    return toReport(output);
  }

  /** Used in tests/diagnostics — returns the configured max history char budget. */
  public int maxHistoryChars() {
    return maxHistoryChars;
  }

  /** Used in tests/diagnostics — returns the configured model. */
  public Model model() {
    return model;
  }
}
