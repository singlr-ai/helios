/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import ai.singlr.core.agent.Agent;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * A unit of work within a workflow. Sealed to enable exhaustive pattern matching.
 *
 * <p>Use the static factory methods for ergonomic construction:
 *
 * <pre>{@code
 * var workflow = Workflow.newBuilder("pipeline")
 *     .withStep(Step.agent("classify", classifyAgent))
 *     .withStep(Step.condition("route",
 *         ctx -> ctx.lastResult().content().contains("urgent"),
 *         Step.agent("urgent", urgentAgent),
 *         Step.agent("normal", normalAgent)))
 *     .build();
 * }</pre>
 */
public sealed interface Step
    permits AgentStep, FunctionStep, Sequential, Parallel, Condition, Loop, Fallback {

  /** The name of this step, used for identification in results and tracing. */
  String name();

  /**
   * Executes this step with the given context.
   *
   * @param context the step context containing input and prior results
   * @return the step result
   */
  StepResult execute(StepContext context);

  /** Creates an AgentStep that uses the context input directly. */
  static AgentStep agent(String name, Agent agent) {
    return new AgentStep(name, agent);
  }

  /** Creates an AgentStep with a custom input mapper. */
  static AgentStep agent(String name, Agent agent, Function<StepContext, String> inputMapper) {
    return new AgentStep(name, agent, inputMapper);
  }

  /** Creates a FunctionStep. */
  static FunctionStep function(String name, StepFunction fn) {
    return new FunctionStep(name, fn);
  }

  /** Creates a Sequential step from the given steps. */
  static Sequential sequential(String name, Step... steps) {
    return new Sequential(name, List.of(steps));
  }

  /** Creates a Parallel step from the given steps. */
  static Parallel parallel(String name, Step... steps) {
    return new Parallel(name, List.of(steps));
  }

  /** Creates a Parallel step with a timeout. */
  static Parallel parallel(String name, Duration timeout, Step... steps) {
    return new Parallel(name, List.of(steps), timeout);
  }

  /** Creates a Condition with an if branch only. */
  static Condition condition(String name, StepPredicate predicate, Step ifStep) {
    return new Condition(name, predicate, ifStep);
  }

  /** Creates a Condition with if and else branches. */
  static Condition condition(String name, StepPredicate predicate, Step ifStep, Step elseStep) {
    return new Condition(name, predicate, ifStep, elseStep);
  }

  /** Creates a Loop with the given condition, body, and max iterations. */
  static Loop loop(String name, StepPredicate condition, Step body, int maxIterations) {
    return new Loop(name, condition, body, maxIterations);
  }

  /** Creates a Fallback from the given steps. */
  static Fallback fallback(String name, Step... steps) {
    return new Fallback(name, List.of(steps));
  }
}
