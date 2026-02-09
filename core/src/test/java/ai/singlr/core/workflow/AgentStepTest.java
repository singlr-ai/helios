/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStepTest {

  @Test
  void successfulAgentRun() {
    var agent = agentWithResponse("classified as urgent");
    var step = Step.agent("classify", agent);

    var result = step.execute(StepContext.of("help me"));

    assertTrue(result.success());
    assertEquals("classify", result.name());
    assertEquals("classified as urgent", result.content());
  }

  @Test
  void failedAgentRun() {
    var agent = agentThatThrows("Model error");
    var step = Step.agent("classify", agent);

    var result = step.execute(StepContext.of("help me"));

    assertFalse(result.success());
    assertEquals("classify", result.name());
    assertTrue(result.error().contains("Agent step failed"));
  }

  @Test
  void defaultInputMapperUsesContextInput() {
    var model = new MockModel("ok");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var step = Step.agent("test", agent);
    step.execute(StepContext.of("original input"));

    var lastMsg = model.lastMessages().getLast();
    assertEquals("original input", lastMsg.content());
  }

  @Test
  void customInputMapper() {
    var model = new MockModel("ok");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var step = Step.agent("test", agent, ctx -> "transformed: " + ctx.input());
    step.execute(StepContext.of("raw"));

    var lastMsg = model.lastMessages().getLast();
    assertEquals("transformed: raw", lastMsg.content());
  }

  @Test
  void inputMapperExceptionCaughtAsFailure() {
    var agent = agentWithResponse("ok");
    var step =
        Step.agent(
            "test",
            agent,
            ctx -> {
              throw new RuntimeException("mapper exploded");
            });

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertTrue(result.error().contains("mapper exploded"));
  }

  @Test
  void agentStepViaFactory() {
    var agent = agentWithResponse("response");
    var step = Step.agent("test", agent);
    assertEquals("test", step.name());
    assertEquals(agent, step.agent());
  }

  @Test
  void sessionPropagatedToAgent() {
    var memory = InMemoryMemory.withDefaults();
    var model = new MockModel("session response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var session = SessionContext.create();
    var step = Step.agent("test", agent);
    var result = step.execute(StepContext.of("hello", session));

    assertTrue(result.success());
    assertEquals("session response", result.content());
    assertFalse(memory.history(session.sessionId()).isEmpty());
  }

  @Test
  void noSessionCallsStatelessRun() {
    var model = new MockModel("stateless response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var step = Step.agent("test", agent);
    var result = step.execute(StepContext.of("hello"));

    assertTrue(result.success());
    assertEquals("stateless response", result.content());
  }

  private Agent agentWithResponse(String content) {
    return new Agent(
        AgentConfig.newBuilder()
            .withName("Agent")
            .withModel(new MockModel(content))
            .withIncludeMemoryTools(false)
            .build());
  }

  private Agent agentThatThrows(String error) {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
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
            .withName("Agent")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build());
  }
}
