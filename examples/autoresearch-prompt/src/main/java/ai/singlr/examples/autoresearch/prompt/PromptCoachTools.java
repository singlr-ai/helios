/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.prompt;

import ai.singlr.core.eval.ConfidenceScorer;
import ai.singlr.core.eval.ExperimentEntry;
import ai.singlr.core.eval.ExperimentLog;
import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.eval.InMemoryCheckpoint;
import ai.singlr.core.eval.Objective;
import ai.singlr.core.eval.Score;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory for the three coach tools: {@code try_prompt}, {@code show_best}, {@code show_log}.
 *
 * <p>The tools deliberately keep the contract mechanical: {@code try_prompt} evaluates a candidate,
 * compares to the current best, applies the keep/discard decision itself, and logs the entry. The
 * coach's job is strategic (what to try and why), not bookkeeping.
 */
public final class PromptCoachTools {

  private final Tool tryPrompt;
  private final Tool showBest;
  private final Tool showLog;

  private PromptCoachTools(Tool tryPrompt, Tool showBest, Tool showLog) {
    this.tryPrompt = tryPrompt;
    this.showBest = showBest;
    this.showLog = showLog;
  }

  public Tool tryPrompt() {
    return tryPrompt;
  }

  public Tool showBest() {
    return showBest;
  }

  public Tool showLog() {
    return showLog;
  }

  /**
   * Build the tool triplet.
   *
   * @param objective how to score a candidate prompt
   * @param best checkpoint holding the current best prompt
   * @param bestScore the current best score (may be {@code null} if nothing evaluated yet)
   * @param log the experiment log to append every attempt to
   * @param higherIsBetter whether higher scores are improvements
   * @return the constructed tool triplet
   */
  public static PromptCoachTools create(
      Objective<String> objective,
      InMemoryCheckpoint<String> best,
      AtomicReference<Double> bestScore,
      ExperimentLog log,
      boolean higherIsBetter) {
    return new PromptCoachTools(
        tryPromptTool(objective, best, bestScore, log, higherIsBetter),
        showBestTool(best, bestScore),
        showLogTool(log));
  }

  private static Tool tryPromptTool(
      Objective<String> objective,
      InMemoryCheckpoint<String> best,
      AtomicReference<Double> bestScore,
      ExperimentLog log,
      boolean higherIsBetter) {
    return Tool.newBuilder()
        .withName("try_prompt")
        .withDescription(
            """
            Evaluate a candidate system prompt. Compares the resulting score against the \
            current best and automatically commits the candidate (keep) or reverts it \
            (discard), appending an entry to the experiment log in either case.""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("candidate")
                .withType(ParameterType.STRING)
                .withDescription("The candidate system prompt to evaluate")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("description")
                .withType(ParameterType.STRING)
                .withDescription("Short summary of what this candidate changes")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("asi")
                .withType(ParameterType.OBJECT)
                .withDescription(
                    "Actionable Side Information — free-form string-to-string diagnostics")
                .withRequired(false)
                .build())
        .withExecutor(
            args -> {
              var candidate = asString(args, "candidate");
              if (candidate == null || candidate.isBlank()) {
                return ToolResult.failure("candidate must be a non-blank string");
              }
              var description = asString(args, "description");
              if (description == null) {
                description = "";
              }
              var asi = asStringMap(args.get("asi"));

              Score score;
              try {
                score = objective.evaluate(candidate);
              } catch (Exception e) {
                appendEntry(
                    log,
                    ExperimentStatus.CRASH,
                    0.0,
                    Map.of(),
                    description,
                    augmentAsi(asi, "error", e.getMessage()));
                return ToolResult.success("crash: " + e.getMessage());
              }

              var current = bestScore.get();
              boolean improved = isImprovement(score.value(), current, higherIsBetter);

              if (improved) {
                best.set(candidate);
                bestScore.set(score.value());
              }
              var status = improved ? ExperimentStatus.KEEP : ExperimentStatus.DISCARD;
              appendEntry(log, status, score.value(), score.secondary(), description, asi);

              var confidence = ConfidenceScorer.score(log);
              return ToolResult.success(
                  ("score=%.6f decision=%s best=%s"
                          + (confidence == null ? "" : " confidence=%.2f"))
                      .formatted(
                          score.value(),
                          status.wire(),
                          best.current().length() > 80
                              ? best.current().substring(0, 80) + "..."
                              : best.current(),
                          confidence == null ? 0.0 : confidence));
            })
        .build();
  }

  private static Tool showBestTool(
      InMemoryCheckpoint<String> best, AtomicReference<Double> bestScore) {
    return Tool.newBuilder()
        .withName("show_best")
        .withDescription("Return the current best system prompt and its score.")
        .withExecutor(
            args -> {
              var score = bestScore.get();
              return ToolResult.success(
                  "best_score=" + (score == null ? "n/a" : score) + "\n---\n" + best.current());
            })
        .build();
  }

  private static Tool showLogTool(ExperimentLog log) {
    return Tool.newBuilder()
        .withName("show_log")
        .withDescription(
            "Return the last N experiment log entries — your notes to yourself from earlier.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("limit")
                .withType(ParameterType.INTEGER)
                .withDescription("How many recent entries to return (default 10)")
                .withRequired(false)
                .build())
        .withExecutor(
            args -> {
              int limit = 10;
              if (args.get("limit") instanceof Number n) {
                limit = Math.max(1, n.intValue());
              }
              var entries = log.entries();
              var start = Math.max(0, entries.size() - limit);
              var slice = entries.subList(start, entries.size());
              return ToolResult.success(render(slice));
            })
        .build();
  }

  private static String render(List<ExperimentEntry> entries) {
    if (entries.isEmpty()) {
      return "(log empty)";
    }
    var sb = new StringBuilder();
    for (var e : entries) {
      sb.append('[')
          .append(e.status().wire())
          .append("] score=")
          .append(e.primaryMetric())
          .append(" desc=")
          .append(e.description());
      if (!e.asi().isEmpty()) {
        sb.append(" asi=").append(e.asi());
      }
      if (e.confidence() != null) {
        sb.append(" confidence=").append(e.confidence());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private static boolean isImprovement(
      double candidate, Double currentBest, boolean higherIsBetter) {
    if (currentBest == null) {
      return true;
    }
    return higherIsBetter ? candidate > currentBest : candidate < currentBest;
  }

  private static void appendEntry(
      ExperimentLog log,
      ExperimentStatus status,
      double primary,
      Map<String, Double> secondary,
      String description,
      Map<String, String> asi) {
    var confidence = ConfidenceScorer.score(log);
    log.append(
        ExperimentEntry.newBuilder()
            .withSegment(log.currentSegment())
            .withStatus(status)
            .withPrimaryMetric(primary)
            .withSecondaryMetrics(secondary)
            .withDescription(description)
            .withAsi(asi)
            .withConfidence(confidence)
            .build());
  }

  private static Map<String, String> augmentAsi(Map<String, String> asi, String key, String value) {
    var m = new HashMap<>(asi);
    m.put(key, value == null ? "" : value);
    return Map.copyOf(m);
  }

  private static String asString(Map<String, Object> args, String key) {
    return args.get(key) instanceof String s ? s : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> asStringMap(Object raw) {
    if (raw instanceof Map<?, ?> map) {
      var out = new HashMap<String, String>();
      for (var entry : map.entrySet()) {
        if (entry.getKey() instanceof String key) {
          out.put(key, String.valueOf(entry.getValue()));
        }
      }
      return Map.copyOf(out);
    }
    return Map.of();
  }
}
