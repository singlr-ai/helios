/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.FeedbackMetric;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end run of {@link GepaPromptOptimizer} against live Gemini Flash. Skipped without {@code
 * GEMINI_API_KEY}.
 *
 * <p>The task is a deliberately weak-prompted sentiment classifier: the seed system prompt is just
 * {@code "Classify the sentence."} with no class hints, so it floats near random over three labels.
 * After a {@link AutoBudget#LIGHT} run the optimizer evolves a prompt that lifts validation
 * accuracy comfortably above the random baseline. Final accuracy ≥ 0.70 — well above the 0.33 floor
 * for three classes — is the bar; failing means the harness, not the model.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GepaPromptOptimizerIntegrationTest {

  private static Model studentModel;
  private static Model reflectionModel;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    studentModel = new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);
    reflectionModel =
        new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);
  }

  @AfterAll
  static void tearDown() {
    if (studentModel != null) {
      studentModel.close();
    }
    if (reflectionModel != null) {
      reflectionModel.close();
    }
  }

  private static List<Example<String, String>> trainExamples() {
    var t = new ArrayList<Example<String, String>>();
    t.add(Example.of("I loved every minute of it.", "positive"));
    t.add(Example.of("Best purchase I've made all year.", "positive"));
    t.add(Example.of("Absolutely brilliant performance.", "positive"));
    t.add(Example.of("Terrible — would not recommend.", "negative"));
    t.add(Example.of("This was a waste of my money.", "negative"));
    t.add(Example.of("Disappointed with the quality.", "negative"));
    t.add(Example.of("The package arrived on Tuesday.", "neutral"));
    t.add(Example.of("It is 70 degrees outside.", "neutral"));
    t.add(Example.of("The book has 320 pages.", "neutral"));
    return t;
  }

  private static List<Example<String, String>> valExamples() {
    var v = new ArrayList<Example<String, String>>();
    v.add(Example.of("Exceeded my expectations.", "positive"));
    v.add(Example.of("A truly delightful experience.", "positive"));
    v.add(Example.of("I hated the ending.", "negative"));
    v.add(Example.of("Frustrating and not worth it.", "negative"));
    v.add(Example.of("The meeting is at 3pm.", "neutral"));
    v.add(Example.of("Today is Friday.", "neutral"));
    return v;
  }

  /**
   * Metric that scores 1.0 when the model output mentions the expected label exactly (lower-cased
   * containment). Feedback names the actual content vs expected so the reflection LM has signal.
   */
  private static FeedbackMetric<String, String> sentimentMatch() {
    return (expected, actual, trace) -> {
      if (actual == null) {
        return FeedbackMetric.Result.of(0.0, "no output produced; expected '" + expected + "'");
      }
      var lower = actual.toLowerCase(Locale.ROOT);
      if (lower.contains(expected)) {
        return FeedbackMetric.Result.of(1.0, "correct: matched '" + expected + "'");
      }
      return FeedbackMetric.Result.of(
          0.0,
          "expected sentiment '"
              + expected
              + "' but output was '"
              + actual.replaceAll("\\s+", " ").trim()
              + "'. The output should explicitly contain one of: positive, negative, neutral.");
    };
  }

  @Test
  void liftsSentimentAccuracyOverWeakSeed() {
    var student =
        AgentConfig.newBuilder()
            .withName("sentiment-student")
            .withModel(studentModel)
            .withSystemPrompt("Classify the sentence.")
            .withIncludeMemoryTools(false)
            .withMaxIterations(1)
            .build();

    var log = new InMemoryExperimentLog();
    var optimizer =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(student)
            .trainSet(trainExamples())
            .valSet(valExamples())
            .metric(sentimentMatch())
            .reflectionLm(reflectionModel)
            .experimentLog(log)
            .inputMapper(SessionContext::of)
            .budget(AutoBudget.LIGHT)
            .minibatchSize(3)
            .parallelism(3)
            .seed(7L)
            .build();

    var result = optimizer.optimize();

    var accuracy = result.bestAggregateScore() / valExamples().size();
    assertNotNull(result.bestPrompt());
    assertTrue(
        accuracy >= 0.70,
        "expected >= 0.70 accuracy after optimization, got "
            + accuracy
            + " (prompt: '"
            + result.bestPrompt()
            + "')");
    assertTrue(result.iterationsRun() > 0, "optimizer must run at least one iteration");
    assertEquals(
        result.iterationsRun() + 1, log.entries().size(), "seed + per-iteration entries logged");
  }
}
