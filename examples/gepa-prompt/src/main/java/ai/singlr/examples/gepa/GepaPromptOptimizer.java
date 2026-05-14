/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.core.eval.EvalResult;
import ai.singlr.core.eval.Evaluator;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.ExperimentEntry;
import ai.singlr.core.eval.ExperimentLog;
import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.eval.FeedbackMetric;
import ai.singlr.core.eval.LlmReflectiveMutator;
import ai.singlr.core.eval.ParetoFrontier;
import ai.singlr.core.eval.ReflectionFailedException;
import ai.singlr.core.eval.ReflectiveMutator;
import ai.singlr.core.eval.TraceSampler;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reference GEPA-shaped prompt optimizer composed from {@link Evaluator}, {@link ParetoFrontier},
 * {@link ReflectiveMutator}, and {@link ExperimentLog}.
 *
 * <p>Optimizes the system prompt on a student {@link AgentConfig} against a labelled validation
 * set. Each iteration: sample a parent candidate by coverage-weighted Pareto sampling → evaluate
 * the parent on a training minibatch (collecting feedback traces) → reflect via {@link
 * LlmReflectiveMutator} to propose a child → score the child on the validation set → add to the
 * frontier and persist to the experiment log.
 *
 * <p>The driver is intentionally small (~300 LoC) and lives in this example module rather than core
 * — users with different sampling strategies, stopping criteria, or multi-predictor pipelines copy
 * this file and adapt. The framework ships the primitives; this is the worked composition.
 *
 * <p>Emits lifecycle events through the configured {@link EventSink}:
 *
 * <ul>
 *   <li>{@link HeliosEvent.Custom} with kind {@code "optimizer.started"} / {@code
 *       "optimizer.completed"} at run boundaries.
 *   <li>{@link HeliosEvent.OptimizerCandidateProposed} when a candidate enters the frontier.
 *   <li>{@link HeliosEvent.OptimizerCandidateScored} after each evaluation.
 * </ul>
 *
 * @param <I> agent input type
 * @param <O> agent output type
 */
public final class GepaPromptOptimizer<I, O> {

  private static final String SOURCE_SEED = "seed";
  private static final String SOURCE_REFLECTIVE = "reflective";

  private final AgentConfig student;
  private final List<Example<I, O>> trainSet;
  private final List<Example<I, O>> valSet;
  private final FeedbackMetric<O, O> metric;
  private final ReflectiveMutator<String> mutator;
  private final ExperimentLog experimentLog;
  private final EventSink eventSink;
  private final Function<I, SessionContext> inputMapper;
  private final OutputSchema<O> outputSchema;
  private final int maxIterations;
  private final int maxMetricCalls;
  private final int minibatchSize;
  private final int parallelism;
  private final long seed;

  private GepaPromptOptimizer(Builder<I, O> b) {
    this.student = b.student;
    this.trainSet = List.copyOf(b.trainSet);
    this.valSet = List.copyOf(b.valSet);
    this.metric = b.metric;
    this.mutator = b.mutator;
    this.experimentLog = b.experimentLog;
    this.eventSink = b.eventSink == null ? event -> {} : b.eventSink;
    this.inputMapper = b.inputMapper;
    this.outputSchema = b.outputSchema;
    this.maxIterations = b.resolvedMaxIterations();
    this.maxMetricCalls = b.resolvedMaxMetricCalls();
    this.minibatchSize = b.minibatchSize;
    this.parallelism = b.parallelism;
    this.seed = b.seed;
  }

  public static <I, O> Builder<I, O> builder() {
    return new Builder<>();
  }

  /** Run the optimization loop and return the best discovered candidate plus the full frontier. */
  public GepaResult optimize() {
    var runId = Ids.newId();
    var rng = new Random(seed);
    var frontier = new ParetoFrontier<String>(valSet.size());
    var lineage = new CandidateLineage();
    var promptToId = new HashMap<String, UUID>();
    emitCustom(runId, "optimizer.started", Map.of("maxIterations", maxIterations));

    var seedScores = seedFrontier(frontier, lineage, promptToId, runId);
    var totalMetricCalls = seedScores;
    var totalReflectionLmCalls = 0;
    var iter = 0;

    while (iter < maxIterations && totalMetricCalls < maxMetricCalls) {
      var stepResult = runIteration(frontier, lineage, promptToId, rng, iter, runId);
      totalMetricCalls += stepResult.metricCallsConsumed();
      totalReflectionLmCalls += stepResult.reflectionLmCallsConsumed();
      iter++;
    }

    var best = frontier.bestSingle().orElseThrow();
    var bestAggregate = frontier.aggregateScore(best);
    emitCustom(
        runId,
        "optimizer.completed",
        Map.of(
            "iterationsRun", iter,
            "bestAggregate", bestAggregate,
            "totalMetricCalls", totalMetricCalls));
    return new GepaResult(
        best,
        bestAggregate,
        frontier,
        lineage,
        iter,
        totalMetricCalls,
        totalReflectionLmCalls,
        Ids.now());
  }

  private record IterationOutcome(int metricCallsConsumed, int reflectionLmCallsConsumed) {}

  private int seedFrontier(
      ParetoFrontier<String> frontier,
      CandidateLineage lineage,
      Map<String, UUID> promptToId,
      UUID runId) {
    var seedPrompt = student.systemPrompt();
    var seedId = Ids.newId();
    promptToId.put(seedPrompt, seedId);
    lineage.record(seedId, null, seedPrompt);
    emitProposed(runId, seedId, null, SOURCE_SEED);
    var seedEval = evaluate(seedPrompt, valSet);
    frontier.add(seedPrompt, seedEval.perExampleScores());
    emitScored(runId, seedId, frontier.aggregateScore(seedPrompt), seedEval.perExampleScores());
    appendExperimentEntry(0, seedId, frontier.aggregateScore(seedPrompt), seedPrompt, SOURCE_SEED);
    return valSet.size();
  }

  private IterationOutcome runIteration(
      ParetoFrontier<String> frontier,
      CandidateLineage lineage,
      Map<String, UUID> promptToId,
      Random rng,
      int iter,
      UUID runId) {
    var parent = frontier.sampleByCoverage(rng);
    var parentId = promptToId.get(parent);
    var minibatch = sampleMinibatch(rng);
    var parentEval = evaluate(parent, minibatch);
    var minibatchCalls = minibatch.size();

    String child;
    try {
      child = mutator.propose(parent, parentEval.feedback());
    } catch (ReflectionFailedException rfe) {
      appendExperimentEntry(
          iter + 1,
          Ids.newId(),
          0.0,
          "reflection failed: " + rfe.getMessage(),
          "reflection-failed");
      return new IterationOutcome(minibatchCalls, 1);
    }
    var childId = Ids.newId();
    lineage.record(childId, parentId, child);
    promptToId.putIfAbsent(child, childId);
    emitProposed(runId, childId, parentId, SOURCE_REFLECTIVE);

    var childEval = evaluate(child, valSet);
    var childScores = childEval.perExampleScores();
    frontier.add(child, childScores);
    var childAggregate = frontier.aggregateScore(child);
    emitScored(runId, childId, childAggregate, childScores);
    appendExperimentEntry(iter + 1, childId, childAggregate, child, SOURCE_REFLECTIVE);
    return new IterationOutcome(minibatchCalls + valSet.size(), 1);
  }

  private EvalResult<I, O> evaluate(String prompt, List<Example<I, O>> examples) {
    var config = AgentConfig.newBuilder(student).withSystemPrompt(prompt).build();
    var builder =
        Evaluator.<I, O>newBuilder()
            .withAgentConfig(config)
            .withDataset(examples)
            .withFeedbackMetric(metric)
            .withInputMapper(inputMapper)
            .withParallelism(parallelism);
    if (outputSchema != null) {
      builder.withOutputSchema(outputSchema);
    }
    return builder.build().run();
  }

  private List<Example<I, O>> sampleMinibatch(Random rng) {
    if (trainSet.size() <= minibatchSize) {
      return trainSet;
    }
    var indices = new ArrayList<Integer>(trainSet.size());
    for (var i = 0; i < trainSet.size(); i++) {
      indices.add(i);
    }
    var picked = new ArrayList<Example<I, O>>(minibatchSize);
    for (var i = 0; i < minibatchSize; i++) {
      var idx = rng.nextInt(indices.size());
      picked.add(trainSet.get(indices.remove(idx)));
    }
    return List.copyOf(picked);
  }

  private void appendExperimentEntry(
      int segment, UUID candidateId, double aggregate, String prompt, String source) {
    var entry =
        ExperimentEntry.newBuilder()
            .withId(candidateId)
            .withSegment(segment)
            .withStatus(ExperimentStatus.KEEP)
            .withPrimaryMetric(aggregate)
            .withDescription(source)
            .withAsi(Map.of("prompt", prompt))
            .withTimestamp(Instant.now())
            .build();
    experimentLog.append(entry);
  }

  private void emitCustom(UUID runId, String kind, Map<String, Object> data) {
    eventSink.onEvent(new HeliosEvent.Custom(Instant.now(), runId, Optional.empty(), kind, data));
  }

  private void emitProposed(UUID runId, UUID candidateId, UUID parentId, String source) {
    eventSink.onEvent(
        new HeliosEvent.OptimizerCandidateProposed(
            Instant.now(),
            runId,
            Optional.empty(),
            candidateId,
            Optional.ofNullable(parentId),
            source));
  }

  private void emitScored(UUID runId, UUID candidateId, double aggregate, double[] perInstance) {
    eventSink.onEvent(
        new HeliosEvent.OptimizerCandidateScored(
            Instant.now(), runId, Optional.empty(), candidateId, aggregate, perInstance.clone()));
  }

  /** Internal helper exposed for tests that need to verify the budget math without optimizing. */
  int resolvedMaxIterations() {
    return maxIterations;
  }

  /** Internal helper exposed for tests. */
  int resolvedMaxMetricCalls() {
    return maxMetricCalls;
  }

  public static final class Builder<I, O> {
    private AgentConfig student;
    private final List<Example<I, O>> trainSet = new ArrayList<>();
    private final List<Example<I, O>> valSet = new ArrayList<>();
    private FeedbackMetric<O, O> metric;
    private Model reflectionLm;
    private ReflectiveMutator<String> mutator;
    private TraceSampler traceSampler;
    private AutoBudget budget;
    private Integer explicitMaxIterations;
    private int minibatchSize = 5;
    private int parallelism = 4;
    private ExperimentLog experimentLog;
    private EventSink eventSink;
    private Function<I, SessionContext> inputMapper;
    private OutputSchema<O> outputSchema;
    private long seed = 42L;

    private Builder() {}

    public Builder<I, O> studentConfig(AgentConfig student) {
      this.student = student;
      return this;
    }

    public Builder<I, O> trainSet(List<Example<I, O>> examples) {
      this.trainSet.clear();
      this.trainSet.addAll(examples);
      return this;
    }

    public Builder<I, O> valSet(List<Example<I, O>> examples) {
      this.valSet.clear();
      this.valSet.addAll(examples);
      return this;
    }

    public Builder<I, O> metric(FeedbackMetric<O, O> metric) {
      this.metric = metric;
      return this;
    }

    public Builder<I, O> reflectionLm(Model model) {
      this.reflectionLm = model;
      return this;
    }

    /**
     * Override the {@link ReflectiveMutator}. Use when you want a non-LLM mutator (e.g. for tests)
     * or a custom prompt template. When unset, the optimizer builds a default {@link
     * LlmReflectiveMutator} from {@link #reflectionLm}.
     */
    public Builder<I, O> mutator(ReflectiveMutator<String> mutator) {
      this.mutator = mutator;
      return this;
    }

    /**
     * Forward a {@link TraceSampler} to the default {@link LlmReflectiveMutator}. Ignored when a
     * custom {@link #mutator} is supplied.
     */
    public Builder<I, O> traceSampler(TraceSampler sampler) {
      this.traceSampler = sampler;
      return this;
    }

    public Builder<I, O> budget(AutoBudget budget) {
      this.budget = budget;
      return this;
    }

    public Builder<I, O> maxIterations(int n) {
      this.explicitMaxIterations = n;
      return this;
    }

    public Builder<I, O> minibatchSize(int n) {
      this.minibatchSize = n;
      return this;
    }

    public Builder<I, O> parallelism(int n) {
      this.parallelism = n;
      return this;
    }

    public Builder<I, O> experimentLog(ExperimentLog log) {
      this.experimentLog = log;
      return this;
    }

    public Builder<I, O> eventSink(EventSink sink) {
      this.eventSink = sink;
      return this;
    }

    public Builder<I, O> inputMapper(Function<I, SessionContext> mapper) {
      this.inputMapper = mapper;
      return this;
    }

    public Builder<I, O> outputSchema(OutputSchema<O> schema) {
      this.outputSchema = schema;
      return this;
    }

    public Builder<I, O> seed(long seed) {
      this.seed = seed;
      return this;
    }

    int resolvedMaxIterations() {
      if (explicitMaxIterations != null) {
        return explicitMaxIterations;
      }
      var b = budget == null ? AutoBudget.MEDIUM : budget;
      return b.maxIterations(valSet.size(), 1);
    }

    int resolvedMaxMetricCalls() {
      if (explicitMaxIterations != null) {
        // No autobudget multiplier when the caller specifies iterations explicitly.
        return Integer.MAX_VALUE;
      }
      var b = budget == null ? AutoBudget.MEDIUM : budget;
      return b.maxMetricCalls(valSet.size(), 1);
    }

    public GepaPromptOptimizer<I, O> build() {
      if (student == null) {
        throw new IllegalStateException("studentConfig is required");
      }
      if (Strings.isBlank(student.systemPrompt())) {
        throw new IllegalStateException(
            "studentConfig.systemPrompt must be non-blank — that's what we're optimizing");
      }
      if (trainSet.isEmpty()) {
        throw new IllegalStateException("trainSet must not be empty");
      }
      if (valSet.isEmpty()) {
        throw new IllegalStateException("valSet must not be empty");
      }
      if (metric == null) {
        throw new IllegalStateException("metric is required");
      }
      if (mutator == null && reflectionLm == null) {
        throw new IllegalStateException("either reflectionLm or a custom mutator is required");
      }
      if (experimentLog == null) {
        throw new IllegalStateException(
            "experimentLog is required — use InMemoryExperimentLog for in-process runs");
      }
      if (inputMapper == null) {
        throw new IllegalStateException(
            "inputMapper is required — provide a Function<I, SessionContext>");
      }
      if (minibatchSize < 1) {
        throw new IllegalStateException("minibatchSize must be >= 1");
      }
      if (parallelism < 1) {
        throw new IllegalStateException("parallelism must be >= 1");
      }
      if (budget != null && explicitMaxIterations != null) {
        throw new IllegalStateException("budget and maxIterations are mutually exclusive");
      }
      if (mutator == null) {
        var b = LlmReflectiveMutator.builder(reflectionLm);
        if (traceSampler != null) {
          b.traceSampler(traceSampler);
        }
        this.mutator = b.build();
      }
      return new GepaPromptOptimizer<>(this);
    }
  }
}
