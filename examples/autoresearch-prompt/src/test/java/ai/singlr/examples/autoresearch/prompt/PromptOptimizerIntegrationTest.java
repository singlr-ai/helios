/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.eval.Metric;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class PromptOptimizerIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);
  }

  @Test
  void optimizesPromptAgainstTinyDataset() {
    var subjectConfig =
        AgentConfig.newBuilder()
            .withName("yes-no-subject")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .withMaxIterations(1)
            .build();

    Metric<String> lowerCaseExactMatch =
        (expected, actual, trace) -> {
          if (actual == null) {
            return 0.0;
          }
          var normalized = actual.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "");
          return normalized.equals(expected) ? 1.0 : 0.0;
        };

    var dataset =
        List.of(
            Example.of("Is the sky blue?", "yes"),
            Example.of("Is water wet?", "yes"),
            Example.of("Do cats bark?", "no"));

    var log = new InMemoryExperimentLog();
    var optimizer =
        PromptOptimizer.newBuilder()
            .withSubjectConfig(subjectConfig)
            .withCoachModel(model)
            .withDataset(dataset)
            .withMetric(lowerCaseExactMatch)
            .withInitialPrompt("Answer the user's question.")
            .withTask(
                "Optimize the downstream system prompt so the subject answers yes/no questions"
                    + " with a single lower-case word: yes or no. Run at least 3 candidates.")
            .withLog(log)
            .withMaxIterations(10)
            .withEvalParallelism(3)
            .build();

    var outcome = optimizer.run();

    assertNotNull(outcome);
    assertFalse(log.entries().isEmpty(), "coach should have logged at least one attempt");
    assertTrue(
        log.entries().stream().anyMatch(e -> e.status().equals("keep")),
        "at least one candidate should have been kept");
  }
}
