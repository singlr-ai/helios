/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.agent.Team;
import ai.singlr.core.common.Result;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class TeamIntegrationTest {

  private static AnthropicModel model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("ANTHROPIC_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
  }

  @Test
  void singleWorkerDelegation() {
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Calculator")
                .withModel(model)
                .withSystemPrompt(
                    "You are a calculator. Compute the answer and reply with just the number.")
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("math-team")
            .withModel(model)
            .withSystemPrompt(
                "You lead a math team. Delegate calculations to the calculator. "
                    + "Return the final answer to the user.")
            .withWorker(
                "calculator", "Computes arithmetic expressions and returns the result", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("What is 17 * 3?");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertNotNull(response.content());
    assertTrue(response.content().contains("51"));
  }

  @Test
  void multiWorkerDelegation() {
    var researcher =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(model)
                .withSystemPrompt(
                    "You are a research specialist. When asked about a topic, provide 2-3 key facts. Be concise.")
                .withIncludeMemoryTools(false)
                .build());

    var writer =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Writer")
                .withModel(model)
                .withSystemPrompt(
                    "You are a technical writer. Take the provided information and write a "
                        + "single concise paragraph. No headings, no bullet points.")
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("content-team")
            .withModel(model)
            .withSystemPrompt(
                "You lead a content creation team. First delegate research to the researcher, "
                    + "then pass the research to the writer to create a paragraph. "
                    + "Return the writer's output as your final answer.")
            .withWorker("researcher", "Finds and synthesizes key facts about a topic", researcher)
            .withWorker("writer", "Writes polished content from provided information", writer)
            .withMaxIterations(10)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Write a paragraph about Java virtual threads");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertNotNull(response.content());
    assertFalse(response.content().isBlank());
  }

  @Test
  void workerWithDirectTools() {
    var lookupTool =
        Tool.newBuilder()
            .withName("lookup_capital")
            .withDescription("Looks up the capital city of a country")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("country")
                    .withType(ParameterType.STRING)
                    .withDescription("The country name")
                    .withRequired(true)
                    .build())
            .withExecutor(
                args -> {
                  var country = (String) args.get("country");
                  return switch (country.toLowerCase()) {
                    case "france" -> ToolResult.success("Paris");
                    case "japan" -> ToolResult.success("Tokyo");
                    default -> ToolResult.success("Unknown");
                  };
                })
            .build();

    var geographyWorker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Geographer")
                .withModel(model)
                .withSystemPrompt(
                    "You are a geography expert. Use the lookup_capital tool to find capitals. "
                        + "Reply with just the capital name.")
                .withTool(lookupTool)
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("geography-team")
            .withModel(model)
            .withSystemPrompt(
                "You lead a geography team. Delegate questions to the geographer. "
                    + "Return their answer to the user.")
            .withWorker(
                "geographer", "Answers geography questions using reference tools", geographyWorker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("What is the capital of France?");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertNotNull(response.content());
    assertTrue(response.content().contains("Paris"));
  }

  public record Answer(String answer, int confidence) {}

  @Test
  void structuredOutput() {
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Analyst")
                .withModel(model)
                .withSystemPrompt("You are a data analyst. Answer questions concisely with facts.")
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("analysis-team")
            .withModel(model)
            .withSystemPrompt(
                "You lead an analysis team. Delegate fact-finding to the analyst. "
                    + "Return a structured response with the answer and a confidence score (0-100).")
            .withWorker("analyst", "Provides factual answers to questions", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result =
        team.run(
            SessionContext.of("What is the boiling point of water in Celsius?"),
            OutputSchema.of(Answer.class));

    assertTrue(result.isSuccess());
    @SuppressWarnings("unchecked")
    var response = ((Result.Success<Response<Answer>>) result).value();
    assertTrue(response.hasParsed());
    assertNotNull(response.parsed().answer());
    assertTrue(response.parsed().confidence() > 0);
  }

  @Test
  void tracingCapturesDelegation() {
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Helper")
                .withModel(model)
                .withSystemPrompt("You are a helpful assistant. Reply concisely.")
                .withIncludeMemoryTools(false)
                .build());

    var traces = new ArrayList<Trace>();
    var team =
        Team.newBuilder()
            .withName("traced-team")
            .withModel(model)
            .withSystemPrompt(
                "You lead a team. Delegate the user's question to the helper and return their answer.")
            .withWorker("helper", "A helpful assistant that answers questions", worker)
            .withTraceListener(traces::add)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("What is 2 + 2?");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var trace = traces.getFirst();
    assertEquals("traced-team", trace.name());
    assertTrue(trace.success());
    assertFalse(trace.spans().isEmpty());
  }
}
