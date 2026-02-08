/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.common.Result;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class WorkflowIntegrationTest {

  @Test
  void agentStepFollowedByConditionRouting() {
    var classifierAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Classifier")
                .withModel(new MockModel("urgent"))
                .withIncludeMemoryTools(false)
                .build());

    var responderAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Responder")
                .withModel(new MockModel("Escalating your issue immediately"))
                .withIncludeMemoryTools(false)
                .build());

    var workflow =
        Workflow.newBuilder("support-pipeline")
            .withStep(Step.agent("classify", classifierAgent))
            .withStep(
                Step.condition(
                    "route",
                    ctx -> ctx.lastResult().content().contains("urgent"),
                    Step.agent("urgent-response", responderAgent),
                    Step.function("standard", ctx -> StepResult.success("standard", "queued"))))
            .build();

    var result = workflow.run("My order hasn't arrived");

    assertTrue(result.isSuccess());
    var value = ((Result.Success<StepResult>) result).value();
    assertEquals("Escalating your issue immediately", value.content());
  }

  @Test
  void multiAgentParallelWithMergedResults() {
    var sentimentAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Sentiment")
                .withModel(new MockModel("positive"))
                .withIncludeMemoryTools(false)
                .build());

    var topicAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Topic")
                .withModel(new MockModel("billing"))
                .withIncludeMemoryTools(false)
                .build());

    var workflow =
        Workflow.newBuilder("analysis")
            .withStep(
                Step.parallel(
                    "analyze",
                    Step.agent("sentiment", sentimentAgent),
                    Step.agent("topic", topicAgent)))
            .withStep(
                Step.function(
                    "summarize",
                    ctx -> {
                      var merged = ctx.lastResult().content();
                      return StepResult.success("summarize", "Analysis: " + merged);
                    }))
            .build();

    var result = workflow.run("I love the new feature!");

    assertTrue(result.isSuccess());
  }

  @Test
  void workflowWithTracingAndAgentStep() {
    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(new MockModel("response"))
                .withIncludeMemoryTools(false)
                .build());

    var workflow =
        Workflow.newBuilder("traced-agent")
            .withStep(Step.agent("run-agent", agent))
            .withTraceListener(traces::add)
            .build();

    var result = workflow.run("test input");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    assertTrue(traces.getFirst().success());
  }

  @Test
  void fallbackBetweenAgentsAndFunction() {
    var failingAgent = agentThatThrows("primary unavailable");
    var backupAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Backup")
                .withModel(new MockModel("backup response"))
                .withIncludeMemoryTools(false)
                .build());

    var workflow =
        Workflow.newBuilder("resilient")
            .withStep(
                Step.fallback(
                    "try-agents",
                    Step.agent("primary", failingAgent),
                    Step.agent("backup", backupAgent),
                    Step.function(
                        "default", ctx -> StepResult.success("default", "static fallback"))))
            .build();

    var result = workflow.run("help");

    assertTrue(result.isSuccess());
  }

  private Agent agentThatThrows(String error) {
    var model =
        new ai.singlr.core.model.Model() {
          @Override
          public ai.singlr.core.model.Response<Void> chat(
              java.util.List<ai.singlr.core.model.Message> messages,
              java.util.List<ai.singlr.core.tool.Tool> tools) {
            throw new RuntimeException(error);
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
    return new Agent(
        AgentConfig.newBuilder()
            .withName("Failing")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build());
  }
}
