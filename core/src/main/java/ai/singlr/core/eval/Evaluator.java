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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
  private final Metric<O> metric;
  private final int parallelism;
  private final OutputSchema<O> outputSchema;
  private final Function<I, SessionContext> inputMapper;

  private Evaluator(Builder<I, O> b) {
    this.baseConfig = b.baseConfig;
    this.dataset = List.copyOf(b.dataset);
    this.metric = b.metric;
    this.parallelism = b.parallelism;
    this.outputSchema = b.outputSchema;
    this.inputMapper = b.inputMapper != null ? b.inputMapper : defaultInputMapper();
  }

  /**
   * Run the evaluation and return the aggregated result.
   *
   * @return per-example results plus the mean score
   */
  public EvalResult<I, O> run() {
    var results = new ArrayList<ExampleResult<I, O>>(dataset.size());
    for (int i = 0; i < dataset.size(); i++) {
      results.add(null);
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>(dataset.size());
      var sem = new java.util.concurrent.Semaphore(parallelism);
      for (int i = 0; i < dataset.size(); i++) {
        final int idx = i;
        final Example<I, O> example = dataset.get(i);
        futures.add(
            executor.submit(
                () -> {
                  sem.acquire();
                  try {
                    results.set(idx, evaluateOne(example));
                    return null;
                  } finally {
                    sem.release();
                  }
                }));
      }
      for (var f : futures) {
        try {
          f.get();
        } catch (ExecutionException e) {
          throw new RuntimeException("evaluator task failed", e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("evaluator interrupted", e);
        }
      }
    }
    double mean = 0.0;
    for (var r : results) {
      mean += r.score();
    }
    if (!results.isEmpty()) {
      mean /= results.size();
    }
    return new EvalResult<>(mean, results);
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    Result<Response<O>> outcome = (Result) agent.run(session);
    return toResult(example, outcome, traceHolder.get());
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

  private static <I> Function<I, SessionContext> defaultInputMapper() {
    return i -> SessionContext.of(String.valueOf(i));
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
    private Metric<O> metric;
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

    public Builder<I, O> withMetric(Metric<O> metric) {
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
        throw new IllegalArgumentException("agent config must not be null");
      }
      if (metric == null) {
        throw new IllegalArgumentException("metric must not be null");
      }
      if (parallelism < 1) {
        throw new IllegalArgumentException("parallelism must be >= 1");
      }
      return new Evaluator<>(this);
    }
  }
}
