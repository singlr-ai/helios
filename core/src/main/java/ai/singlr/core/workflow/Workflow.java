/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.Durability;
import ai.singlr.core.runtime.DurabilityCoordinator;
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.TraceBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *
 * <h2>Durable workflows</h2>
 *
 * <p>Wire a {@link Durability} bundle and run with an explicit {@code runId} to make the workflow
 * crash-safe — each step is checkpointed to the {@code RunStore} and journaled around its
 * execution. On JVM restart, {@code resume(runId)} reconstitutes the workflow state from the
 * journal and continues from the step after the last one that completed.
 *
 * <pre>{@code
 * var workflow = Workflow.newBuilder("pipeline")
 *     .withStep(...)
 *     .withDurability(Durability.inMemory())   // or PgDurability.of(pgConfig)
 *     .build();
 *
 * var runId = Ids.newId();
 * workflow.run("input", runId);
 * // ... JVM crashes ...
 * workflow.resume(runId);
 * }</pre>
 *
 * <p>The original input is persisted in a synthetic {@code "@input"} journal entry; each step's
 * output is captured in its own journal entry so a resumed workflow can reconstitute the {@link
 * StepContext} chain without the caller re-supplying anything.
 */
public class Workflow {

  /** Special tool name used by durable workflows to seed-journal the original input. */
  static final String INPUT_SEED_TOOL_NAME = "@input";

  private final String name;
  private final List<Step> steps;
  private final List<EventSink> eventSinks;
  private final Durability durability;
  private final DurabilityCoordinator durabilityCoordinator;

  private Workflow(
      String name, List<Step> steps, List<EventSink> eventSinks, Durability durability) {
    this.name = name;
    this.steps = List.copyOf(steps);
    this.eventSinks = List.copyOf(eventSinks);
    this.durability = durability;
    this.durabilityCoordinator =
        durability != null ? new DurabilityCoordinator(durability, "workflow." + name) : null;
  }

  /**
   * Runs this workflow with the given input.
   *
   * @param input the input to the first step
   * @return the result of the last step, or a failure
   */
  public Result<StepResult> run(String input) {
    return executeStepsFrom(StepContext.of(input), 0, null, null);
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
    return executeStepsFrom(StepContext.of(input, session), 0, null, session);
  }

  /**
   * Runs this workflow durably under the given {@code runId}. Requires a {@link Durability} bundle
   * configured via {@link Builder#withDurability(Durability)}.
   *
   * <p>Each step boundary is checkpointed; after a JVM crash {@link #resume(UUID)} continues from
   * the step after the last one that completed.
   */
  public Result<StepResult> run(String input, UUID runId) {
    requireDurability("run(input, runId)");
    return startDurable(input, null, runId);
  }

  /** Durable run with a session context for agent steps. */
  public Result<StepResult> run(String input, SessionContext session, UUID runId) {
    requireDurability("run(input, session, runId)");
    return startDurable(input, session, runId);
  }

  /**
   * Resumes a previously-started durable workflow run by id alone. Reconstitutes the original input
   * and all completed step outputs from the journal.
   *
   * @return the result of the last step, or a failure
   */
  public Result<StepResult> resume(UUID runId) {
    requireDurability("resume");
    if (runId == null) {
      return Result.failure("runId must not be null");
    }
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isEmpty()) {
      return Result.failure("No workflow run found for id: " + runId);
    }
    var run = existing.get();
    if (run.agentId() != null && !run.agentId().equals("workflow." + name)) {
      return Result.failure(
          "Run "
              + runId
              + " belongs to '"
              + run.agentId()
              + "'; cannot be resumed by workflow '"
              + name
              + "'");
    }
    if (isTerminal(run.status())) {
      return Result.failure(
          "Workflow run is already terminal: " + run.status() + " (" + runId + ")");
    }

    var entries = durability.toolCallJournal().all(runId);
    String originalInput = null;
    var stepOutputs = new java.util.LinkedHashMap<String, String>();
    for (var entry : entries) {
      if (INPUT_SEED_TOOL_NAME.equals(entry.toolName())
          && entry.status() == ToolCallStatus.SUCCEEDED) {
        originalInput = entry.output();
        continue;
      }
      if (entry.status() == ToolCallStatus.SUCCEEDED) {
        stepOutputs.put(entry.toolName(), entry.output());
      }
    }
    if (originalInput == null) {
      return Result.failure(
          "Workflow run " + runId + " has no recorded input; cannot resume safely");
    }

    var resumeIndex = stepOutputs.size();
    if (resumeIndex >= steps.size()) {
      return Result.failure(
          "Workflow run "
              + runId
              + " has more journaled steps ("
              + resumeIndex
              + ") than this workflow defines ("
              + steps.size()
              + ")");
    }

    var context = StepContext.of(originalInput);
    StepResult lastResult = null;
    for (int i = 0; i < resumeIndex; i++) {
      var stepName = steps.get(i).name();
      var output = stepOutputs.get(stepName);
      if (output == null) {
        return Result.failure(
            "Workflow run "
                + runId
                + " journal missing output for step '"
                + stepName
                + "' at index "
                + i);
      }
      lastResult = StepResult.success(stepName, output);
      context = context.withResult(lastResult);
    }
    return executeStepsFrom(context, resumeIndex, runId, null);
  }

  /**
   * If a durable run with the given id exists and is not terminal, resume it; otherwise start a
   * fresh durable run with that id.
   */
  public Result<StepResult> runOrResume(UUID runId, String input) {
    requireDurability("runOrResume");
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isPresent() && !isTerminal(existing.get().status())) {
      return resume(runId);
    }
    return run(input, runId);
  }

  /**
   * If a durable run with the given id exists and is not terminal, resume it; otherwise start a
   * fresh durable run with that id and session.
   */
  public Result<StepResult> runOrResume(UUID runId, String input, SessionContext session) {
    requireDurability("runOrResume");
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isPresent() && !isTerminal(existing.get().status())) {
      return resume(runId);
    }
    return run(input, session, runId);
  }

  private Result<StepResult> startDurable(String input, SessionContext session, UUID runId) {
    if (runId == null) {
      return Result.failure("runId must not be null");
    }
    var existing = durabilityCoordinator.findRun(runId);
    if (existing.isPresent() && isTerminal(existing.get().status())) {
      return Result.failure(
          "Run id already used by terminal run: " + existing.get().status() + " (" + runId + ")");
    }
    var sessionId = session != null ? session.sessionId() : null;
    var userId = session != null ? session.userId() : null;
    durabilityCoordinator.initialize(runId, sessionId, userId, 0);
    var seedCallId = INPUT_SEED_TOOL_NAME + "@" + Ids.newId().toString();
    if (durabilityCoordinator.journalStart(runId, 0, seedCallId, INPUT_SEED_TOOL_NAME, Map.of())) {
      durabilityCoordinator.journalTerminal(runId, seedCallId, ToolResult.success(input));
    }
    return executeStepsFrom(StepContext.of(input, session), 0, runId, session);
  }

  private Result<StepResult> executeStepsFrom(
      StepContext initialContext, int startIndex, UUID runId, SessionContext session) {
    // Use a synthetic runId for event correlation when not in a durable run; events need one.
    var eventRunId = runId != null ? runId : Ids.newId();
    var traceBuilder =
        eventSinks.isEmpty()
            ? null
            : TraceBuilder.start("workflow." + name, eventRunId, eventSinks);

    try {
      StepResult lastResult = null;
      var context = initialContext;
      var sessionId = session != null ? session.sessionId() : null;
      var userId = session != null ? session.userId() : null;

      for (int i = startIndex; i < steps.size(); i++) {
        var step = steps.get(i);
        if (durabilityCoordinator != null && runId != null) {
          durabilityCoordinator.checkpoint(runId, sessionId, userId, i);
        }

        SpanBuilder span =
            traceBuilder != null
                ? traceBuilder.span("step." + step.name(), SpanKind.WORKFLOW)
                : null;

        // Each step attempt gets a unique toolCallId so that retries-on-resume don't collide with
        // a prior FAILED entry. The toolName stays step.name() so resume can correlate by step.
        var stepCallId = step.name() + "@" + Ids.newId().toString();
        var journaled =
            durabilityCoordinator != null
                && runId != null
                && durabilityCoordinator.journalStart(
                    runId, i, stepCallId, step.name(), Map.of("input", context.input()));

        try {
          lastResult = step.execute(context);
          context = context.withResult(lastResult);
        } catch (RuntimeException e) {
          if (span != null) {
            span.fail(e.getMessage());
          }
          if (journaled) {
            durabilityCoordinator.journalTerminalFailure(runId, stepCallId, e.getMessage());
          }
          // Deliberately do NOT mark the run FAILED — a thrown exception is treated as
          // a recoverable interruption (matching JVM-crash semantics), leaving the run
          // RUNNING so a subsequent resume() can pick it up. Callers that want a hard
          // terminal failure should return StepResult.failure(...) instead.
          if (traceBuilder != null) {
            var trace = traceBuilder.fail(e.getMessage());
            emitRunFailed(eventRunId, e.getMessage(), trace);
          }
          return Result.failure(
              "Workflow '%s' failed at step '%s': %s".formatted(name, step.name(), e.getMessage()),
              e);
        }

        if (span != null) {
          if (lastResult.success()) {
            span.end();
          } else {
            span.fail(lastResult.error());
          }
        }
        if (journaled) {
          durabilityCoordinator.journalTerminal(runId, stepCallId, toToolResult(lastResult));
        }

        if (!lastResult.success()) {
          if (traceBuilder != null) {
            var trace = traceBuilder.fail(lastResult.error());
            emitRunFailed(eventRunId, lastResult.error(), trace);
          }
          // Leave the run RUNNING so a subsequent resume() can retry. Step failures are treated
          // as recoverable — the caller sees Result.failure but the durability layer keeps the
          // run in a resumable state. To permanently terminate a run, the caller should not
          // call resume(); routine retention via RunStore.purgeOlderThan handles cleanup.
          return Result.failure(lastResult.error());
        }
      }

      if (traceBuilder != null) {
        var trace = traceBuilder.end();
        emitRunCompleted(eventRunId, trace);
      }
      if (durabilityCoordinator != null && runId != null) {
        durabilityCoordinator.complete(runId, sessionId, userId, steps.size());
      }
      return Result.success(lastResult);
    } catch (Exception e) {
      if (traceBuilder != null) {
        var trace = traceBuilder.fail(e.getMessage());
        emitRunFailed(eventRunId, e.getMessage(), trace);
      }
      return Result.failure("Workflow '%s' failed: %s".formatted(name, e.getMessage()), e);
    }
  }

  private void emitRunCompleted(UUID runId, ai.singlr.core.trace.Trace trace) {
    if (eventSinks.isEmpty() || runId == null || trace == null) {
      return;
    }
    var event =
        new ai.singlr.core.events.HeliosEvent.RunCompleted(
            java.time.Instant.now(), runId, java.util.Optional.empty(), trace);
    for (var sink : eventSinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException ignored) {
        // sink exceptions never abort a workflow
      }
    }
  }

  private void emitRunFailed(UUID runId, String error, ai.singlr.core.trace.Trace trace) {
    if (eventSinks.isEmpty() || runId == null || trace == null) {
      return;
    }
    var event =
        new ai.singlr.core.events.HeliosEvent.RunFailed(
            java.time.Instant.now(),
            runId,
            java.util.Optional.empty(),
            Strings.isBlank(error) ? "unknown" : error,
            trace);
    for (var sink : eventSinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException ignored) {
        // sink exceptions never abort a workflow
      }
    }
  }

  private static ToolResult toToolResult(StepResult stepResult) {
    return stepResult.success()
        ? ToolResult.success(stepResult.content() != null ? stepResult.content() : "")
        : ToolResult.failure(stepResult.error());
  }

  private static boolean isTerminal(AgentRunStatus status) {
    return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED;
  }

  private void requireDurability(String method) {
    if (durabilityCoordinator == null) {
      throw new IllegalStateException(
          method + " requires Workflow.Builder.withDurability(...) to be configured");
    }
  }

  /** Returns all journal entries for a run — useful for forensics and tests. */
  List<ToolCallRecord> journalFor(UUID runId) {
    return durability == null ? List.of() : durability.toolCallJournal().all(runId);
  }

  public String name() {
    return name;
  }

  public List<Step> steps() {
    return steps;
  }

  public Durability durability() {
    return durability;
  }

  public boolean durabilityEnabled() {
    return durability != null;
  }

  public static Builder newBuilder(String name) {
    return new Builder(name);
  }

  public static class Builder {

    private final String name;
    private final List<Step> steps = new ArrayList<>();
    private final List<EventSink> eventSinks = new ArrayList<>();
    private Durability durability;

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

    /**
     * Registers an {@link EventSink} that receives {@link
     * ai.singlr.core.events.HeliosEvent.SpanOpened} / {@link
     * ai.singlr.core.events.HeliosEvent.SpanClosed} for every span in this workflow's trace. The
     * single observability SPI for Workflow runs.
     */
    public Builder withEventSink(EventSink sink) {
      this.eventSinks.add(sink);
      return this;
    }

    /** Bulk variant of {@link #withEventSink}. */
    public Builder withEventSinks(List<EventSink> sinks) {
      this.eventSinks.addAll(sinks);
      return this;
    }

    /**
     * Opt the workflow into crash-safe execution. Pass {@link Durability#inMemory()} for tests,
     * {@code PgDurability.of(pgConfig)} for production. Pass {@code null} to disable.
     */
    public Builder withDurability(Durability durability) {
      this.durability = durability;
      return this;
    }

    public Workflow build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("Workflow name is required");
      }
      if (steps.isEmpty()) {
        throw new IllegalStateException("Workflow must have at least one step");
      }
      var seenNames = new java.util.HashSet<String>();
      for (var step : steps) {
        if (step.name() == null || step.name().isBlank()) {
          throw new IllegalStateException("Step names must be non-blank");
        }
        if (INPUT_SEED_TOOL_NAME.equals(step.name())) {
          throw new IllegalStateException(
              "Step name '" + INPUT_SEED_TOOL_NAME + "' is reserved by the durability journal");
        }
        if (!seenNames.add(step.name())) {
          throw new IllegalStateException(
              "Duplicate step name '"
                  + step.name()
                  + "'; durable workflows require unique step names so journal entries can be"
                  + " correlated on resume");
        }
      }
      return new Workflow(name, steps, eventSinks, durability);
    }
  }
}
