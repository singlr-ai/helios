/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import ai.singlr.core.model.Model;
import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs one fixture against one model some number of times and emits {@link Metrics} per attempt.
 *
 * <p>Stateless. The runner doesn't own the model — the suite-level orchestrator constructs one
 * model per provider and shares it across fixtures.
 */
public final class FixtureRunner {

  private FixtureRunner() {}

  /**
   * Run {@code fixture} against {@code model} {@code reps} times and collect per-attempt metrics.
   * Each repetition gets its own {@link FixtureOutcome}; the model is the only shared resource.
   */
  public static List<Metrics> run(Fixture fixture, Model model, int reps) {
    if (reps <= 0) {
      throw new IllegalArgumentException("reps must be >= 1; got " + reps);
    }
    var out = new ArrayList<Metrics>(reps);
    for (int i = 1; i <= reps; i++) {
      out.add(runOnce(fixture, model, i));
    }
    return List.copyOf(out);
  }

  private static Metrics runOnce(Fixture fixture, Model model, int attempt) {
    var startNanos = System.nanoTime();
    FixtureOutcome outcome;
    var notes = new ArrayList<String>();
    try {
      outcome = fixture.run(model);
    } catch (RuntimeException e) {
      var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      notes.add("threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      return new Metrics(
          fixture.name(), modelId(model), attempt, false, 0, 0, 0, 0, elapsedMs, notes);
    }
    notes.addAll(outcome.notes());
    var elapsedMs = outcome.duration().toMillis();
    if (elapsedMs == 0) {
      elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    }
    var trace = outcome.trace();
    var totalTokens = trace == null ? 0 : trace.totalTokens();
    var totalIterations = outcome.totalIterations();
    if (totalIterations == 0 && trace != null) {
      totalIterations = countModelCallSpans(trace);
    }
    var setupTurns = computeSetupTurns(totalIterations, outcome.executedCodeSnippets().size());
    var recovery =
        countRecoveryIterations(outcome.executedCodeSnippets(), outcome.boundFieldNames());
    return new Metrics(
        fixture.name(),
        modelId(model),
        attempt,
        outcome.passed(),
        setupTurns,
        totalIterations,
        totalTokens,
        recovery,
        elapsedMs,
        notes);
  }

  private static String modelId(Model model) {
    return model == null ? "unknown" : model.id();
  }

  /**
   * Setup turns = iterations that produced no {@code execute_code} call. Coarse proxy for
   * cold-start orientation. Only meaningful for REPL-backed fixtures (CodeAct/RLM); for bare-Agent
   * fixtures with zero code snippets the metric collapses to 0 to signal "no code-vs-setup split
   * exists for this workload shape."
   */
  static int computeSetupTurns(int totalIterations, int codeSnippetCount) {
    if (codeSnippetCount == 0) {
      return 0;
    }
    return Math.max(0, totalIterations - codeSnippetCount);
  }

  /** Walk the trace's span tree and count spans whose name equals {@code model.chat}. */
  static int countModelCallSpans(Trace trace) {
    int n = 0;
    for (var s : trace.spans()) {
      n += countModelCallSpans(s);
    }
    return n;
  }

  private static int countModelCallSpans(Span span) {
    int n = "model.chat".equals(span.name()) ? 1 : 0;
    for (var child : span.children()) {
      n += countModelCallSpans(child);
    }
    return n;
  }

  /**
   * Count snippets whose source contains a Java cast against any bound field name. Heuristic:
   * {@code (Type) boundName}, {@code (Type<...>) boundName}, or {@code (java.x.Y) boundName}. False
   * positives are possible (model casts its own intermediates) but cheap to live with; missed cases
   * would mean the metric under-reports recovery, which biases the suite toward making bindings
   * look better than they are — the opposite of the suite's purpose, so we keep the heuristic
   * loose.
   */
  static int countRecoveryIterations(List<String> snippets, List<String> boundFieldNames) {
    if (snippets.isEmpty() || boundFieldNames.isEmpty()) {
      return 0;
    }
    var alternation =
        boundFieldNames.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow();
    var pattern = Pattern.compile("\\([\\w.<>,\\s\\[\\]]+\\)\\s*(" + alternation + ")\\b");
    int n = 0;
    for (var snippet : snippets) {
      if (snippet != null && pattern.matcher(snippet).find()) {
        n++;
      }
    }
    return n;
  }
}
