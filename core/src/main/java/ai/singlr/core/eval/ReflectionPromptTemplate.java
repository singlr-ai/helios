/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import ai.singlr.core.common.Strings;
import java.util.List;

/**
 * Assembles the reflection prompt for {@link LlmReflectiveMutator}. Pure string functions; no model
 * calls, no side effects — kept separate from the orchestration class so the prompt is the
 * load-bearing tuning surface, not a buried private method.
 */
final class ReflectionPromptTemplate {

  static final String DEFAULT_INSTRUCTIONS =
      """
      You are improving a system prompt. The current prompt is shown below, followed by a sample of \
      evaluation traces (inputs, model output, expected output, score, and feedback from the metric).

      Read the traces. Identify failure patterns. Propose a revised prompt that addresses them while \
      preserving what already works. Return ONLY the revised prompt — no preamble, no explanation, \
      no code fences.""";

  private ReflectionPromptTemplate() {}

  /**
   * Build the user-message text the reflection LM receives.
   *
   * @param instructions header instructions; defaults to {@link #DEFAULT_INSTRUCTIONS} when blank
   * @param parentPrompt the parent prompt being revised
   * @param traces the sampled traces to show the LM
   * @param maxFeedbackChars total budget across all trace-text rendering; older traces are dropped
   *     from the tail of the visible sample once the budget is exhausted
   * @return the assembled prompt text
   */
  static String build(
      String instructions, String parentPrompt, List<TraceFeedback> traces, int maxFeedbackChars) {
    var header = Strings.isBlank(instructions) ? DEFAULT_INSTRUCTIONS : instructions.strip();
    var sb = new StringBuilder();
    sb.append(header)
        .append("\n\n## Current prompt\n")
        .append(parentPrompt)
        .append("\n\n## Traces\n");
    if (traces == null || traces.isEmpty()) {
      sb.append("(no traces available)\n");
      return sb.toString();
    }
    var rendered = new StringBuilder();
    var trimmedTraces = 0;
    for (var t : traces) {
      var entry = renderTrace(t);
      if (maxFeedbackChars > 0 && rendered.length() + entry.length() > maxFeedbackChars) {
        trimmedTraces = traces.size() - traces.indexOf(t);
        break;
      }
      rendered.append(entry);
    }
    sb.append(rendered);
    if (trimmedTraces > 0) {
      sb.append("\n(")
          .append(trimmedTraces)
          .append(" additional trace(s) omitted to stay within the feedback budget)\n");
    }
    return sb.toString();
  }

  private static String renderTrace(TraceFeedback t) {
    var sb = new StringBuilder();
    sb.append("- input: ").append(safe(t.exampleInput())).append('\n');
    if (t.exampleExpected() != null) {
      sb.append("  expected: ").append(safe(t.exampleExpected())).append('\n');
    }
    sb.append("  actual: ").append(safe(t.actualOutput())).append('\n');
    sb.append("  score: ").append(t.score()).append('\n');
    if (!Strings.isBlank(t.feedback())) {
      sb.append("  feedback: ").append(escape(t.feedback())).append('\n');
    }
    return sb.toString();
  }

  private static String safe(Object o) {
    if (o == null) {
      return "(null)";
    }
    return escape(o.toString());
  }

  /**
   * Strip newlines and triple quotes from a single trace field so the assembled prompt stays parser
   * and human-friendly when the LM echoes structure back. Compaction beats round-trip fidelity here
   * — the reflection LM doesn't need the original formatting, it needs the gist.
   */
  private static String escape(String s) {
    return s.replace("\"\"\"", "\\\"\\\"\\\"").replace("\n", " ").replace("\r", " ");
  }
}
