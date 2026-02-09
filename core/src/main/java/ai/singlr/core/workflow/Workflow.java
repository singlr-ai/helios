/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.TraceBuilder;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.List;

/**
 * A workflow that executes a sequence of steps with optional tracing.
 *
 * <p>Each top-level step runs sequentially. Use composite steps (Sequential, Parallel, Condition,
 * Loop, Fallback) for complex orchestration.
 *
 * <pre>{@code
 * var workflow = Workflow.newBuilder("pipeline")
 *     .withStep(Step.agent("classify", agent))
 *     .withStep(Step.condition("route", predicate, ifStep, elseStep))
 *     .build();
 *
 * Result<StepResult> result = workflow.run("input");
 * }</pre>
 */
public class Workflow {

  private final String name;
  private final List<Step> steps;
  private final List<TraceListener> traceListeners;

  private Workflow(String name, List<Step> steps, List<TraceListener> traceListeners) {
    this.name = name;
    this.steps = List.copyOf(steps);
    this.traceListeners = List.copyOf(traceListeners);
  }

  /**
   * Runs this workflow with the given input.
   *
   * @param input the input to the first step
   * @return the result of the last step, or a failure
   */
  public Result<StepResult> run(String input) {
    return runWithContext(StepContext.of(input));
  }

  /**
   * Runs this workflow with the given input and session context. Agent steps within the workflow
   * will use the session for memory-scoped conversations.
   *
   * @param input the input to the first step
   * @param session the session context for agent steps
   * @return the result of the last step, or a failure
   */
  public Result<StepResult> run(String input, SessionContext session) {
    return runWithContext(StepContext.of(input, session));
  }

  private Result<StepResult> runWithContext(StepContext context) {
    var traceBuilder =
        traceListeners.isEmpty() ? null : TraceBuilder.start("workflow." + name, traceListeners);

    try {
      StepResult lastResult = null;
      for (var step : steps) {
        SpanBuilder span =
            traceBuilder != null
                ? traceBuilder.span("step." + step.name(), SpanKind.WORKFLOW)
                : null;

        lastResult = step.execute(context);
        context = context.withResult(lastResult);

        if (span != null) {
          if (lastResult.success()) {
            span.end();
          } else {
            span.fail(lastResult.error());
          }
        }

        if (!lastResult.success()) {
          if (traceBuilder != null) {
            traceBuilder.fail(lastResult.error());
          }
          return Result.failure(lastResult.error());
        }
      }

      if (traceBuilder != null) {
        traceBuilder.end();
      }
      return Result.success(lastResult);
    } catch (Exception e) {
      if (traceBuilder != null) {
        traceBuilder.fail(e.getMessage());
      }
      return Result.failure("Workflow '%s' failed: %s".formatted(name, e.getMessage()), e);
    }
  }

  public String name() {
    return name;
  }

  public List<Step> steps() {
    return steps;
  }

  public static Builder newBuilder(String name) {
    return new Builder(name);
  }

  public static class Builder {

    private final String name;
    private final List<Step> steps = new ArrayList<>();
    private final List<TraceListener> traceListeners = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
    }

    public Builder withStep(Step step) {
      this.steps.add(step);
      return this;
    }

    public Builder withSteps(List<Step> steps) {
      this.steps.addAll(steps);
      return this;
    }

    public Builder withTraceListener(TraceListener listener) {
      this.traceListeners.add(listener);
      return this;
    }

    public Builder withTraceListeners(List<TraceListener> listeners) {
      this.traceListeners.addAll(listeners);
      return this;
    }

    public Workflow build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("Workflow name is required");
      }
      if (steps.isEmpty()) {
        throw new IllegalStateException("Workflow must have at least one step");
      }
      return new Workflow(name, steps, traceListeners);
    }
  }
}
