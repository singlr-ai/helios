/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.trace.Trace;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runs an {@link Agent} over a labeled dataset and collects metric scores. The core batch
 * evaluation primitive — used directly for offline quality measurement, and as the objective for
 * prompt or agent-strategy optimization in autoresearch loops.
 *
 * <p>For each {@link Example}, the evaluator constructs a fresh {@link Agent} from a base {@link
 * AgentConfig} (agents are never shared across threads), attaches a per-run trace listener,
 * dispatches a {@link SessionContext}, and feeds the final response through the configured {@link
 * Metric}.
 *
 * <p>Examples are evaluated concurrently on virtual threads; configure {@code parallelism} to bound
 * the number of in-flight requests. A value of {@code 1} forces serial execution.
 *
 * @param <I> input type of the examples
 * @param <O> expected output type of the examples
 */
public final class Evaluator<I, O> {

  private final AgentConfig baseConfig;
  private final List<Example<I, O>> dataset;
  private final Metric<O, O> metric;
  private final int parallelism;
  private final OutputSchema<O> outputSchema;
  private final Function<I, SessionContext> inputMapper;

  private Evaluator(Builder<I, O> b) {
    this.baseConfig = b.baseConfig;
    this.dataset = List.copyOf(b.dataset);
    this.metric = b.metric;
    this.parallelism = b.parallelism;
    this.outputSchema = b.outputSchema;
    this.inputMapper = b.inputMapper;
  }

  /**
   * Run the evaluation and return the aggregated result.
   *
   * @return per-example results plus the mean score
   * @throws EvalException if any worker throws unrecoverably or the evaluator is interrupted
   */
  public EvalResult<I, O> run() {
    if (dataset.isEmpty()) {
      return new EvalResult<>(0.0, List.of());
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new Semaphore(parallelism);
      var futures = new ArrayList<CompletableFuture<ExampleResult<I, O>>>(dataset.size());
      for (var example : dataset) {
        futures.add(submitExample(example, executor, gate));
      }
      var results = new ArrayList<ExampleResult<I, O>>(dataset.size());
      try {
        for (var f : futures) {
          results.add(f.join());
        }
      } catch (CompletionException e) {
        throw new EvalException("evaluator task failed", e.getCause());
      }
      double mean = 0.0;
      for (var r : results) {
        mean += r.score();
      }
      return new EvalResult<>(mean / results.size(), results);
    }
  }

  private CompletableFuture<ExampleResult<I, O>> submitExample(
      Example<I, O> example, Executor executor, Semaphore gate) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            gate.acquire();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalException("evaluator interrupted", e);
          }
          try {
            return evaluateOne(example);
          } finally {
            gate.release();
          }
        },
        executor);
  }

  private ExampleResult<I, O> evaluateOne(Example<I, O> example) {
    var traceHolder = new AtomicReference<Trace>();
    TraceListener capture = traceHolder::set;
    var runConfig = AgentConfig.newBuilder(baseConfig).withTraceListener(capture).build();
    var agent = new Agent(runConfig);
    var session = inputMapper.apply(example.input());

    if (outputSchema != null) {
      var outcome = agent.run(session, outputSchema);
      return toResult(example, outcome, traceHolder.get());
    }
    var outcome = castUntypedOutcome(agent.run(session));
    return toResult(example, outcome, traceHolder.get());
  }

  /**
   * When no {@link OutputSchema} is attached, the agent returns {@code Result<Response>} with an
   * erased type parameter. Evaluator's untyped path only ever reads {@code response.content()},
   * which is always {@link String} regardless of {@code O} — callers that want a typed {@code O}
   * must supply an {@link OutputSchema}. The raw-typed cast here surfaces nothing to the metric
   * beyond the string content, so erasure makes it safe in practice.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private Result<Response<O>> castUntypedOutcome(Result<Response> raw) {
    return (Result) raw;
  }

  private ExampleResult<I, O> toResult(
      Example<I, O> example, Result<Response<O>> outcome, Trace trace) {
    return switch (outcome) {
      case Result.Success<Response<O>> s -> {
        O actual = extractOutput(s.value());
        double score = metric.score(example.expected(), actual, trace);
        yield new ExampleResult<>(example, actual, score, trace, outcome);
      }
      case Result.Failure<Response<O>> f -> new ExampleResult<>(example, null, 0.0, trace, outcome);
    };
  }

  @SuppressWarnings("unchecked")
  private O extractOutput(Response<O> response) {
    if (outputSchema != null) {
      return response.parsed();
    }
    return (O) response.content();
  }

  public static <I, O> Builder<I, O> newBuilder() {
    return new Builder<>();
  }

  /**
   * Builder for {@link Evaluator}.
   *
   * @param <I> input type
   * @param <O> expected output type
   */
  public static final class Builder<I, O> {

    private AgentConfig baseConfig;
    private final List<Example<I, O>> dataset = new ArrayList<>();
    private Metric<O, O> metric;
    private int parallelism = 1;
    private OutputSchema<O> outputSchema;
    private Function<I, SessionContext> inputMapper;

    private Builder() {}

    public Builder<I, O> withAgentConfig(AgentConfig config) {
      this.baseConfig = config;
      return this;
    }

    public Builder<I, O> withDataset(List<Example<I, O>> examples) {
      this.dataset.clear();
      this.dataset.addAll(examples);
      return this;
    }

    public Builder<I, O> withExample(Example<I, O> example) {
      this.dataset.add(example);
      return this;
    }

    public Builder<I, O> withMetric(Metric<O, O> metric) {
      this.metric = metric;
      return this;
    }

    public Builder<I, O> withParallelism(int parallelism) {
      this.parallelism = parallelism;
      return this;
    }

    public Builder<I, O> withOutputSchema(OutputSchema<O> outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    public Builder<I, O> withInputMapper(Function<I, SessionContext> inputMapper) {
      this.inputMapper = inputMapper;
      return this;
    }

    public Evaluator<I, O> build() {
      if (baseConfig == null) {
        throw new IllegalStateException("agent config must not be null");
      }
      if (metric == null) {
        throw new IllegalStateException("metric must not be null");
      }
      if (parallelism < 1) {
        throw new IllegalStateException("parallelism must be >= 1");
      }
      if (inputMapper == null) {
        throw new IllegalStateException(
            "inputMapper must be configured via withInputMapper(...). For String inputs pass"
                + " SessionContext::of directly.");
      }
      return new Evaluator<>(this);
    }
  }
}
