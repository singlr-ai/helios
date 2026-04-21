/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.prompt;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.eval.Evaluator;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.ExperimentEntry;
import ai.singlr.core.eval.ExperimentLog;
import ai.singlr.core.eval.InMemoryCheckpoint;
import ai.singlr.core.eval.Metric;
import ai.singlr.core.eval.Objective;
import ai.singlr.core.eval.Score;
import ai.singlr.core.model.Model;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reference example: iteratively optimize a system prompt against a labeled dataset.
 *
 * <p>A <b>coach</b> agent proposes candidate prompts; a <b>subject</b> agent (configured by the
 * coach) answers the dataset under each candidate; the {@link Evaluator} scores the subject against
 * a user-supplied {@link Metric}; improvements are kept, regressions discarded, and every iteration
 * is appended to an {@link ExperimentLog} with the coach's free-form ASI.
 *
 * <p>The loop owns the optimization; the coach owns the search strategy. This example validates the
 * core/eval primitives on a non-code domain — if they compose cleanly here, they'll compose cleanly
 * for other domains too.
 */
public final class PromptOptimizer {

  private final Config config;
  private final Objective<String> objective;
  private final InMemoryCheckpoint<String> best;
  private final AtomicReference<Double> bestScore;
  private final ExperimentLog log;

  private PromptOptimizer(Config config) {
    this.config = config;
    this.best = new InMemoryCheckpoint<>(config.initialPrompt);
    this.bestScore = new AtomicReference<>(null);
    this.log = config.log;
    this.objective = candidatePrompt -> new Score(runSubject(candidatePrompt), Map.of(), Map.of());
  }

  /**
   * Run the coach agent up to {@code maxIterations} times. Returns the final best prompt and its
   * score.
   *
   * @return the final best candidate and its score
   */
  public Outcome run() {
    var tools = PromptCoachTools.create(objective, best, bestScore, log, config.higherIsBetter);
    var coachConfig =
        AgentConfig.newBuilder()
            .withName("prompt-coach")
            .withModel(config.coachModel)
            .withSystemPrompt(coachSystemPrompt())
            .withTool(tools.tryPrompt())
            .withTool(tools.showBest())
            .withTool(tools.showLog())
            .withMaxIterations(config.maxIterations)
            .withIncludeMemoryTools(false)
            .build();
    var coach = new Agent(coachConfig);
    var outcome = coach.run(config.task);
    String lastMessage =
        switch (outcome) {
          case Result.Success<?> s when s.value() instanceof ai.singlr.core.model.Response<?> r ->
              r.content();
          default -> null;
        };
    return new Outcome(best.current(), bestScore.get(), lastMessage, List.copyOf(log.entries()));
  }

  /**
   * Get the current best prompt. Useful during long runs or after interruption.
   *
   * @return the best prompt observed so far
   */
  public String bestPrompt() {
    return best.current();
  }

  /**
   * Get the current best score. {@code null} if no candidate has been evaluated yet.
   *
   * @return the best score so far, or {@code null}
   */
  public Double bestScore() {
    return bestScore.get();
  }

  private double runSubject(String candidatePrompt) {
    var subjectConfig =
        AgentConfig.newBuilder(config.subjectConfig).withSystemPrompt(candidatePrompt).build();
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(subjectConfig)
            .withInputMapper(SessionContext::of)
            .withDataset(config.dataset)
            .withMetric(config.metric)
            .withParallelism(config.evalParallelism)
            .build();
    return evaluator.run().meanScore();
  }

  private String coachSystemPrompt() {
    var direction = config.higherIsBetter ? "higher is better" : "lower is better";
    return """
        You are a prompt optimizer. Your job is to iteratively propose system prompts
        for a downstream task and keep the candidate that scores best on a labeled
        dataset.

        Task description: %s

        Metric: %s

        Tools:
          - try_prompt(candidate, description, asi): evaluate a candidate prompt.
            The tool compares its score to the current best and automatically
            records the result as "keep" (new best) or "discard" (regression)
            in the experiment log. Always supply a short 'description' of what
            the candidate does differently, and an 'asi' map with free-form
            diagnostics for your future self after a context reset.
          - show_best(): reveal the current best prompt and its score.
          - show_log(limit): show the most recent experiment entries.

        Loop discipline:
          1. Start by calling show_best() to see the baseline.
          2. Propose a candidate prompt that varies the baseline in one specific way.
          3. Call try_prompt(candidate, description, asi).
          4. Read the returned score and decision. If kept, explore variations of
             the new best. If discarded, try a structurally different change.
          5. When stuck, call show_log() to review your own ASI from prior runs.
          6. Never stop — continue proposing until the harness ends the session.
        """
        .formatted(config.task, direction);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Final result of a {@link PromptOptimizer#run()} call. */
  public record Outcome(
      String bestPrompt, Double bestScore, String coachFinalMessage, List<ExperimentEntry> log) {}

  /** Immutable configuration record, populated by {@link Builder}. */
  record Config(
      AgentConfig subjectConfig,
      Model coachModel,
      List<Example<String, String>> dataset,
      Metric<String, String> metric,
      String initialPrompt,
      String task,
      ExperimentLog log,
      int maxIterations,
      int evalParallelism,
      boolean higherIsBetter) {}

  /** Builder for {@link PromptOptimizer}. */
  public static final class Builder {

    private AgentConfig subjectConfig;
    private Model coachModel;
    private final List<Example<String, String>> dataset = new ArrayList<>();
    private Metric<String, String> metric;
    private String initialPrompt;
    private String task;
    private ExperimentLog log;
    private int maxIterations = 25;
    private int evalParallelism = 1;
    private boolean higherIsBetter = true;

    private Builder() {}

    public Builder withSubjectConfig(AgentConfig subjectConfig) {
      this.subjectConfig = subjectConfig;
      return this;
    }

    public Builder withCoachModel(Model coachModel) {
      this.coachModel = coachModel;
      return this;
    }

    public Builder withDataset(List<Example<String, String>> dataset) {
      this.dataset.clear();
      this.dataset.addAll(dataset);
      return this;
    }

    public Builder withMetric(Metric<String, String> metric) {
      this.metric = metric;
      return this;
    }

    public Builder withInitialPrompt(String initialPrompt) {
      this.initialPrompt = initialPrompt;
      return this;
    }

    public Builder withTask(String task) {
      this.task = task;
      return this;
    }

    public Builder withLog(ExperimentLog log) {
      this.log = log;
      return this;
    }

    public Builder withMaxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    public Builder withEvalParallelism(int parallelism) {
      this.evalParallelism = parallelism;
      return this;
    }

    public Builder withHigherIsBetter(boolean higherIsBetter) {
      this.higherIsBetter = higherIsBetter;
      return this;
    }

    public PromptOptimizer build() {
      if (subjectConfig == null) {
        throw new IllegalStateException("subjectConfig must not be null");
      }
      if (coachModel == null) {
        throw new IllegalStateException("coachModel must not be null");
      }
      if (metric == null) {
        throw new IllegalStateException("metric must not be null");
      }
      if (initialPrompt == null || initialPrompt.isBlank()) {
        throw new IllegalStateException("initialPrompt must not be blank");
      }
      if (task == null || task.isBlank()) {
        throw new IllegalStateException("task must not be blank");
      }
      if (log == null) {
        throw new IllegalStateException("log must not be null");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      if (evalParallelism < 1) {
        throw new IllegalStateException("evalParallelism must be >= 1");
      }
      return new PromptOptimizer(
          new Config(
              subjectConfig,
              coachModel,
              List.copyOf(dataset),
              metric,
              initialPrompt,
              task,
              log,
              maxIterations,
              evalParallelism,
              higherIsBetter));
    }
  }
}
