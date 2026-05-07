/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.Durability;
import ai.singlr.core.runtime.InMemoryRunStore;
import ai.singlr.core.runtime.InMemoryToolCallJournal;
import ai.singlr.core.runtime.ToolCallStatus;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowDurabilityTest {

  private static Step counting(String name, AtomicInteger counter, String suffix) {
    return Step.function(
        name,
        ctx -> {
          counter.incrementAndGet();
          var prior = ctx.lastResult() != null ? ctx.lastResult().content() : ctx.input();
          return StepResult.success(name, prior + suffix);
        });
  }

  @Test
  void runRequiresDurabilityForRunIdOverload() {
    var workflow =
        Workflow.newBuilder("plain").withStep(counting("a", new AtomicInteger(), "-A")).build();
    assertThrows(IllegalStateException.class, () -> workflow.run("hi", Ids.newId()));
  }

  @Test
  void durableRunCheckpointsAndJournals() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var d = Durability.of(store, journal);

    var workflow =
        Workflow.newBuilder("pipeline")
            .withStep(counting("uppercase", new AtomicInteger(), "-A"))
            .withStep(counting("reverse", new AtomicInteger(), "-B"))
            .withDurability(d)
            .build();

    var runId = Ids.newId();
    var result = workflow.run("hi", runId);

    assertTrue(result.isSuccess());
    assertEquals("hi-A-B", result.getOrThrow().content());

    var run = store.find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, run.status());

    var entries = journal.all(runId);
    assertEquals(3, entries.size(), "1 input seed + 2 step entries");
    assertEquals("@input", entries.get(0).toolName());
    assertEquals(ToolCallStatus.SUCCEEDED, entries.get(0).status());
    assertEquals("hi", entries.get(0).output());
    assertEquals("uppercase", entries.get(1).toolName());
    assertEquals("reverse", entries.get(2).toolName());
  }

  @Test
  void resumeContinuesAfterLastSuccessfulStep() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var d = Durability.of(store, journal);

    var stepA = new AtomicInteger();
    var stepB = new AtomicInteger();
    var stepC = new AtomicInteger();

    // First run: simulate crash after step A — by short-circuiting at step B.
    var crashedWorkflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", stepA, "-A"))
            .withStep(
                Step.function(
                    "b",
                    ctx -> {
                      throw new RuntimeException("simulated crash");
                    }))
            .withStep(counting("c", stepC, "-C"))
            .withDurability(d)
            .build();

    var runId = Ids.newId();
    var crashedResult = crashedWorkflow.run("seed", runId);
    assertFalse(crashedResult.isSuccess());

    // Reset step A counter so we can prove it doesn't re-run
    var aSecondTime = new AtomicInteger();

    // Resume with a second workflow that has step b fixed
    var fixedWorkflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", aSecondTime, "-AGAIN"))
            .withStep(counting("b", stepB, "-B"))
            .withStep(counting("c", stepC, "-C"))
            .withDurability(d)
            .build();

    var resumed = fixedWorkflow.resume(runId);
    assertTrue(resumed.isSuccess(), "resume must succeed after step B was fixed");
    assertEquals(
        0,
        aSecondTime.get(),
        "step A must NOT have re-run on resume — its prior output was journaled");
    assertEquals(1, stepB.get(), "step B must run on resume (it failed last time)");
    assertEquals(1, stepC.get(), "step C must run after B");

    var run = store.find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, run.status());
  }

  @Test
  void resumeUnknownRunFails() {
    var workflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(Durability.inMemory())
            .build();
    var result = workflow.resume(Ids.newId());
    assertFalse(result.isSuccess());
  }

  @Test
  void resumeAlreadyCompletedRunFails() {
    var d = Durability.inMemory();
    var workflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(d)
            .build();
    var runId = Ids.newId();
    workflow.run("hi", runId);
    var second = workflow.resume(runId);
    assertFalse(second.isSuccess());
  }

  @Test
  void resumeRequiresDurability() {
    var workflow =
        Workflow.newBuilder("p").withStep(counting("a", new AtomicInteger(), "-A")).build();
    assertThrows(IllegalStateException.class, () -> workflow.resume(Ids.newId()));
  }

  @Test
  void resumeRejectsNullRunId() {
    var workflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(Durability.inMemory())
            .build();
    assertFalse(workflow.resume(null).isSuccess());
  }

  @Test
  void runOrResumeStartsFreshThenResumes() {
    var d = Durability.inMemory();
    var stepCounter = new AtomicInteger();
    var workflow =
        Workflow.newBuilder("p")
            .withStep(counting("a", stepCounter, "-A"))
            .withStep(
                Step.function(
                    "b",
                    ctx -> {
                      // First time: throw. Second time: succeed. We use a static counter to flip.
                      if (Flip.flag.getAndIncrement() == 0) {
                        throw new RuntimeException("first-time fail");
                      }
                      var prior =
                          ctx.lastResult() != null ? ctx.lastResult().content() : ctx.input();
                      return StepResult.success("b", prior + "-B");
                    }))
            .withDurability(d)
            .build();
    var runId = Ids.newId();

    var first = workflow.runOrResume(runId, "seed");
    assertFalse(first.isSuccess(), "first call should fail at step b");

    var second = workflow.runOrResume(runId, "seed");
    assertTrue(second.isSuccess(), "runOrResume must resume the existing run");
    assertEquals(1, stepCounter.get(), "step a must NOT re-run on resume");
  }

  /** Static flag holder so the lambda can flip across invocations within the test. */
  private static final class Flip {
    static final AtomicInteger flag = new AtomicInteger();
  }

  @Test
  void crossWorkflowResumeFails() {
    var d = Durability.inMemory();
    var producer =
        Workflow.newBuilder("producer")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(d)
            .build();
    var consumer =
        Workflow.newBuilder("consumer")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(d)
            .build();
    var runId = Ids.newId();
    producer.run("hi", runId);
    var result = consumer.resume(runId);
    assertFalse(result.isSuccess(), "cross-workflow resume must fail with agentId mismatch");
  }

  @Test
  void duplicateStepNamesRejected() {
    var b = Workflow.newBuilder("p");
    b.withStep(counting("a", new AtomicInteger(), "-1"));
    b.withStep(counting("a", new AtomicInteger(), "-2"));
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void reservedStepNameRejected() {
    var b = Workflow.newBuilder("p");
    b.withStep(counting("@input", new AtomicInteger(), "-1"));
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void durabilityOptIn() {
    var plain = Workflow.newBuilder("p").withStep(counting("a", new AtomicInteger(), "-A")).build();
    assertFalse(plain.durabilityEnabled());
    var durable =
        Workflow.newBuilder("p")
            .withStep(counting("a", new AtomicInteger(), "-A"))
            .withDurability(Durability.inMemory())
            .build();
    assertTrue(durable.durabilityEnabled());
    assertNotNull(durable.durability());
  }
}
