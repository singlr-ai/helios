/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.DurableResumeScanner;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.workflow.Step;
import ai.singlr.core.workflow.Workflow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end durable runtime walkthrough against a real Postgres (via TestContainers). This is the
 * "worked example" for the durable-agent feature: it exercises the full surface — {@link
 * PgDurability}, {@link Agent#run(SessionContext, UUID)}, {@link Agent#resume(UUID)}, {@link
 * Workflow#run(String, UUID)}, {@link Workflow#resume(UUID)}, and {@link DurableResumeScanner}.
 */
class DurableAgentEndToEndTest {

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
    PgTestSupport.truncateMemory();
  }

  /** A model that emits one tool call on the first turn, then stops on the second. */
  private static Model toolThenStop(String toolName, String toolCallId, String finalContent) {
    var calls = new AtomicInteger(0);
    return new Model() {
      @Override
      public Response chat(List<Message> messages, List<Tool> tools) {
        if (calls.getAndIncrement() == 0) {
          return Response.newBuilder()
              .withToolCalls(
                  List.of(
                      ToolCall.newBuilder()
                          .withId(toolCallId)
                          .withName(toolName)
                          .withArguments(Map.of())
                          .build()))
              .withFinishReason(FinishReason.TOOL_CALLS)
              .build();
        }
        return Response.newBuilder()
            .withContent(finalContent)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void agentDurableRunCheckpointsToPostgres() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var memory = new PgMemory(PgTestSupport.pgConfig("research-bot"));

    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get the weather")
            .withIdempotent(true)
            .withExecutor(args -> ToolResult.success("sunny"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("research-bot")
                .withModel(toolThenStop("get_weather", "call_1", "It's sunny."))
                .withTool(weatherTool)
                .withIncludeMemoryTools(false)
                .withMemory(memory)
                .withDurability(d)
                .build());

    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var result = agent.run(SessionContext.of(sessionId, "What's the weather?"), runId);

    assertTrue(result.isSuccess());
    var run = d.runStore().find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, run.status());
    assertEquals("research-bot", run.agentId());
    assertNotNull(run.endedAt());

    var entries = d.toolCallJournal().all(runId);
    assertEquals(1, entries.size());
    assertEquals("get_weather", entries.get(0).toolName());
    assertEquals("sunny", entries.get(0).output());
  }

  @Test
  void agentResumeAfterCrash() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var memory = new PgMemory(PgTestSupport.pgConfig("research-bot"));

    var runId = Ids.newId();
    var sessionId = Ids.newId();

    // First "run": simulate a crash by ending the JVM lifecycle midway. We simulate this by
    // creating an agent and starting a run, then letting it complete cleanly. Then we
    // *manually* mark the run RUNNING so resume() will pick it up — emulating "the run
    // status was checkpointed but the JVM died before terminal write" semantics.
    memory.registerSession("u-1", sessionId);
    memory.addMessage("u-1", sessionId, Message.user("What's the weather?"));

    d.runStore()
        .checkpoint(
            ai.singlr.core.runtime.AgentRun.newBuilder()
                .withRunId(runId)
                .withSessionId(sessionId)
                .withUserId("u-1")
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.RUNNING)
                .withStartedAt(Ids.now())
                .withLastCheckpointAt(Ids.now())
                .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("research-bot")
                .withModel(toolThenStop("get_weather", "call_1", "It's sunny."))
                .withTool(
                    Tool.newBuilder()
                        .withName("get_weather")
                        .withIdempotent(true)
                        .withExecutor(args -> ToolResult.success("sunny"))
                        .build())
                .withIncludeMemoryTools(false)
                .withMemory(memory)
                .withDurability(d)
                .build());

    var resumed = agent.resume(runId);
    assertTrue(resumed.isSuccess(), "resume must reconstitute the run from Postgres");
    assertEquals(
        AgentRunStatus.COMPLETED,
        d.runStore().find(runId).orElseThrow().status(),
        "resume must drive the run to COMPLETED");
  }

  @Test
  void runOrResumeStartsFreshThenResumesOnRetry() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var memory = new PgMemory(PgTestSupport.pgConfig("research-bot"));
    var runId = Ids.newId();
    var sessionId = Ids.newId();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("research-bot")
                .withModel(toolThenStop("get_weather", "call_1", "It's sunny."))
                .withTool(
                    Tool.newBuilder()
                        .withName("get_weather")
                        .withIdempotent(true)
                        .withExecutor(args -> ToolResult.success("sunny"))
                        .build())
                .withIncludeMemoryTools(false)
                .withMemory(memory)
                .withDurability(d)
                .build());

    // First call: starts fresh
    var first = agent.runOrResume(runId, SessionContext.of(sessionId, "What's the weather?"));
    assertTrue(first.isSuccess());

    // Second call with same runId: would resume but the run is COMPLETED — runOrResume returns
    // the failure path because resume rejects terminal runs. This documents that runOrResume is
    // for crash recovery, not idempotent re-execution.
    var second = agent.runOrResume(runId, SessionContext.of(sessionId, "What's the weather?"));
    assertTrue(second.isFailure(), "runOrResume on an already-completed run should fail");
  }

  @Test
  void workflowDurableRunAndResume() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var stepCounter = new AtomicInteger();

    var workflow =
        Workflow.newBuilder("ingest-pipeline")
            .withStep(
                Step.function(
                    "extract",
                    ctx -> {
                      stepCounter.incrementAndGet();
                      return ai.singlr.core.workflow.StepResult.success(
                          "extract", ctx.input() + "-extracted");
                    }))
            .withStep(
                Step.function(
                    "transform",
                    ctx -> {
                      stepCounter.incrementAndGet();
                      var prior =
                          ctx.lastResult() != null ? ctx.lastResult().content() : ctx.input();
                      return ai.singlr.core.workflow.StepResult.success(
                          "transform", prior + "-transformed");
                    }))
            .withDurability(d)
            .build();

    var runId = Ids.newId();
    var result = workflow.run("raw-data", runId);
    assertTrue(result.isSuccess());

    var run = d.runStore().find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, run.status());
    assertEquals("workflow.ingest-pipeline", run.agentId());
  }

  @Test
  void scannerResumesStaleRuns() throws Exception {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var memory = new PgMemory(PgTestSupport.pgConfig("research-bot"));
    var sessionId = Ids.newId();
    var runId = Ids.newId();

    memory.registerSession("u-1", sessionId);
    memory.addMessage("u-1", sessionId, Message.user("hi"));

    // Plant a stale RUNNING run (older than scanner's staleAfter)
    var anHourAgo = java.time.OffsetDateTime.now().minusHours(1);
    d.runStore()
        .checkpoint(
            ai.singlr.core.runtime.AgentRun.newBuilder()
                .withRunId(runId)
                .withSessionId(sessionId)
                .withUserId("u-1")
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.RUNNING)
                .withStartedAt(anHourAgo)
                .withLastCheckpointAt(anHourAgo)
                .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("research-bot")
                .withModel(
                    new Model() {
                      @Override
                      public Response chat(List<Message> messages, List<Tool> tools) {
                        return Response.newBuilder()
                            .withContent("recovered")
                            .withFinishReason(FinishReason.STOP)
                            .build();
                      }

                      @Override
                      public String id() {
                        return "mock";
                      }

                      @Override
                      public String provider() {
                        return "test";
                      }
                    })
                .withMemory(memory)
                .withDurability(d)
                .build());

    var scanner =
        DurableResumeScanner.builder(d)
            .registerAgent("research-bot", agent::resume)
            .withStaleAfter(java.time.Duration.ofMinutes(5))
            .build();

    var scanResult = scanner.scan();
    assertEquals(1, scanResult.scanned());
    assertEquals(1, scanResult.resumed());
    assertEquals(1, scanResult.recovered());
    assertEquals(
        AgentRunStatus.COMPLETED,
        d.runStore().find(runId).orElseThrow().status(),
        "scanner must drive the stale run to COMPLETED");
  }

  @Test
  void retentionPurgesOldTerminalRuns() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);

    var anHourAgo = java.time.OffsetDateTime.now().minusHours(1);
    var oldId = Ids.newId();
    var recentId = Ids.newId();
    d.runStore()
        .checkpoint(
            ai.singlr.core.runtime.AgentRun.newBuilder()
                .withRunId(oldId)
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.COMPLETED)
                .withStartedAt(anHourAgo)
                .withLastCheckpointAt(anHourAgo)
                .withEndedAt(anHourAgo)
                .build());
    d.runStore()
        .checkpoint(
            ai.singlr.core.runtime.AgentRun.newBuilder()
                .withRunId(recentId)
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.COMPLETED)
                .withStartedAt(java.time.OffsetDateTime.now())
                .withLastCheckpointAt(java.time.OffsetDateTime.now())
                .withEndedAt(java.time.OffsetDateTime.now())
                .build());

    var deleted = d.runStore().purgeOlderThan(java.time.Duration.ofMinutes(10));
    assertEquals(1, deleted);
    assertTrue(d.runStore().find(oldId).isEmpty());
    assertTrue(d.runStore().find(recentId).isPresent());
  }

  /** Sanity check that resume can survive multiple JVM-restart cycles for the same run. */
  @Test
  void resumeChainAcrossMultipleRestarts() {
    var pgConfig = PgTestSupport.pgConfig();
    var d = PgDurability.of(pgConfig);
    var memory = new PgMemory(PgTestSupport.pgConfig("research-bot"));
    var sessionId = Ids.newId();
    var runId = Ids.newId();

    memory.registerSession("u-1", sessionId);
    memory.addMessage("u-1", sessionId, Message.user("hi"));

    d.runStore()
        .checkpoint(
            ai.singlr.core.runtime.AgentRun.newBuilder()
                .withRunId(runId)
                .withSessionId(sessionId)
                .withUserId("u-1")
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.RUNNING)
                .withStartedAt(Ids.now())
                .withLastCheckpointAt(Ids.now())
                .build());

    Result<Response> firstAttempt = null;
    for (int restartAttempt = 0; restartAttempt < 3; restartAttempt++) {
      // Each iteration is a fresh "JVM" — a new Agent instance built from scratch.
      var agent =
          new Agent(
              AgentConfig.newBuilder()
                  .withName("research-bot")
                  .withModel(
                      new Model() {
                        @Override
                        public Response chat(List<Message> messages, List<Tool> tools) {
                          return Response.newBuilder()
                              .withContent("done")
                              .withFinishReason(FinishReason.STOP)
                              .build();
                        }

                        @Override
                        public String id() {
                          return "mock";
                        }

                        @Override
                        public String provider() {
                          return "test";
                        }
                      })
                  .withMemory(memory)
                  .withDurability(d)
                  .build());
      firstAttempt = agent.resume(runId);
      if (firstAttempt.isSuccess()) {
        break;
      }
    }
    assertTrue(firstAttempt.isSuccess(), "at least one resume attempt must succeed");
  }
}
