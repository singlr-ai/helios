/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.eval.Metric;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PromptOptimizerTest {

  private static Model alwaysAnswers(String answer) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(answer)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "subject-mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static AgentConfig subjectConfig() {
    return AgentConfig.newBuilder()
        .withName("subject")
        .withModel(alwaysAnswers("world"))
        .withIncludeMemoryTools(false)
        .build();
  }

  @Test
  void builderValidatesSubjectConfig() {
    var b =
        PromptOptimizer.newBuilder()
            .withCoachModel(alwaysAnswers("hi"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withTask("t")
            .withLog(new InMemoryExperimentLog());
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesCoachModel() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withTask("t")
            .withLog(new InMemoryExperimentLog());
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesMetric() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withInitialPrompt("x")
            .withTask("t")
            .withLog(new InMemoryExperimentLog());
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesInitialPrompt() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withMetric(Metric.exactMatch())
            .withTask("t")
            .withLog(new InMemoryExperimentLog())
            .withInitialPrompt("  ");
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesTask() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withLog(new InMemoryExperimentLog());
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesLog() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withTask("t");
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesMaxIterations() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withTask("t")
            .withLog(new InMemoryExperimentLog())
            .withMaxIterations(0);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderValidatesEvalParallelism() {
    var b =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("c"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("x")
            .withTask("t")
            .withLog(new InMemoryExperimentLog())
            .withEvalParallelism(0);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void runInvokesCoachWithTools() {
    var turns = new AtomicInteger();
    var coach =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            int t = turns.getAndIncrement();
            if (t == 0) {
              return Response.newBuilder()
                  .withContent("")
                  .withToolCalls(
                      List.of(
                          new ToolCall(
                              "c1",
                              "try_prompt",
                              Map.of(
                                  "candidate",
                                  "You are terse. Answer exactly: world.",
                                  "description",
                                  "force exact answer"))))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("optimization complete")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "coach-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var log = new InMemoryExperimentLog();
    var optimizer =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(coach)
            .withDataset(List.of(Example.of("q", "world")))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("baseline")
            .withTask("Optimize for exact-match.")
            .withLog(log)
            .withMaxIterations(5)
            .build();

    var outcome = optimizer.run();

    assertNotNull(outcome.bestPrompt());
    assertEquals(1, log.entries().size());
    assertEquals("keep", log.entries().get(0).status());
    assertEquals(1.0, optimizer.bestScore());
    assertEquals("You are terse. Answer exactly: world.", optimizer.bestPrompt());
    assertEquals("optimization complete", outcome.coachFinalMessage());
  }

  @Test
  void bestPromptStartsAsInitial() {
    var optimizer =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig())
            .withCoachModel(alwaysAnswers("done"))
            .withMetric(Metric.exactMatch())
            .withInitialPrompt("seed prompt")
            .withTask("x")
            .withLog(new InMemoryExperimentLog())
            .build();
    assertEquals("seed prompt", optimizer.bestPrompt());
    assertTrue(optimizer.bestScore() == null);
  }
}
