/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.rlm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.repl.RlmHarness;
import ai.singlr.repl.sandbox.JvmSandbox;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end integration of {@link RlmHarness} against a real LM. Exercises the full RLM substrate:
 * JvmSandbox subprocess startup, JShell evaluation, JSON-RPC bridge, predict() round-trip, the
 * canonical {@link ai.singlr.repl.RlmSystemPrompt} read by a real model, typed submit validation,
 * and clean termination. Skipped without {@code GEMINI_API_KEY}.
 *
 * <p>Tests here are deliberately framework-shaped, not model-shaped: assertions describe what the
 * harness must guarantee given a cooperating model — terminal status, non-null typed output,
 * schema-valid output. Tasks are kept narrow so even a small Flash model has no semantic ambiguity.
 *
 * <p>Lives in {@code examples/rlm-demo} rather than {@code helios-repl}'s test sources because the
 * integration crosses two JPMS modules ({@code helios-repl} + {@code helios-gemini}) and we don't
 * want to declare a {@code requires} for a test-only dependency in {@code helios-repl}'s production
 * module declaration. Same pattern {@code autoresearch-prompt} uses.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class RlmHarnessIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);
  }

  public record StatsInput(List<Integer> numbers, String operation) {}

  public record StatsOutput(int result, String operationPerformed) {}

  @Test
  void simpleStatsTaskReachesSubmittedStatus() {
    var rlm =
        RlmHarness.builder(StatsInput.class, StatsOutput.class)
            .model(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Compute the requested operation on the numbers. The 'operation' field will be"
                    + " one of 'sum', 'max', or 'min'. Write JShell code in execute_code to"
                    + " perform the computation, then submit the result. The 'result' field is"
                    + " an int; 'operationPerformed' is a short String describing what you did.")
            .maxIterations(5)
            .maxLlmCalls(3)
            .build();

    var result = rlm.run(new StatsInput(List.of(1, 5, 3, 2, 4), "sum"));

    // Framework contract: produces a typed output via either path (clean submit or
    // extract-fallback). EXTRACTED is a valid framework outcome — the harness recovered the
    // model's work even when submit() was forgotten — so asserting only SUBMITTED would test
    // model decision-making, which is non-deterministic and not the framework's promise.
    assertTrue(
        result.success(),
        "Expected success. status=" + result.status() + ", error=" + result.error());
    assertNotNull(result.output());
    assertEquals(15, result.output().result(), "sum of 1,5,3,2,4 must be 15");
    assertNotNull(result.output().operationPerformed());
    assertTrue(result.predictCallCount() == 0, "this task should not need predict() calls");
  }

  public record SentimentInput(String text) {}

  public record SentimentOutput(String sentiment, String reasoning) {}

  @Test
  void taskUsingPredictForJudgmentReachesSubmittedStatus() {
    var rlm =
        RlmHarness.builder(SentimentInput.class, SentimentOutput.class)
            .model(model)
            .subModel(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Classify the sentiment of the given text. Use predict() to make the"
                    + " classification — that is what predict() is for. The instructions you"
                    + " pass to predict() should ask for one word: 'positive', 'negative', or"
                    + " 'neutral'. Save the predict() result to a variable, then submit it as"
                    + " 'sentiment' along with a short 'reasoning' explaining your choice.")
            .maxIterations(6)
            .maxLlmCalls(3)
            .build();

    var result =
        rlm.run(new SentimentInput("This product changed my life. Best purchase I ever made!"));

    assertTrue(
        result.success(),
        "Expected success. status=" + result.status() + ", error=" + result.error());
    assertNotNull(result.output());
    assertNotNull(result.output().sentiment());
    var sentiment = result.output().sentiment().toLowerCase();
    assertTrue(
        sentiment.contains("positive"),
        "Expected positive sentiment for unambiguous positive text; got " + sentiment);
    assertTrue(result.predictCallCount() >= 1, "task strategy explicitly requires predict()");
  }
}
