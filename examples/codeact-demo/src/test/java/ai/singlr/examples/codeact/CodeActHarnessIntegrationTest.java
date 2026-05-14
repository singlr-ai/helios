/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.repl.CodeActHarness;
import ai.singlr.repl.CodeActResult;
import ai.singlr.repl.sandbox.JvmSandbox;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end integration of {@link CodeActHarness} against a real LM. Exercises the substrate
 * without the sub-LM / submit() machinery: JvmSandbox subprocess, JShell evaluation, JSON-RPC
 * bridge, typed input binding, the canonical {@link ai.singlr.repl.CodeActSystemPrompt}, and the
 * Agent's structured-output path producing a typed final response. Skipped without {@code
 * GEMINI_API_KEY}.
 *
 * <p>Lives under {@code examples/codeact-demo} for the same reason as {@code rlm-demo}: crosses two
 * JPMS modules ({@code helios-repl} + {@code helios-gemini}) and we don't want a test-only requires
 * in {@code helios-repl}'s production module declaration.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class CodeActHarnessIntegrationTest {

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
  void simpleStatsTaskReturnsTypedOutput() {
    var harness =
        CodeActHarness.builder(StatsInput.class, StatsOutput.class)
            .model(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Compute the requested operation on the numbers. 'operation' is one of 'sum',"
                    + " 'max', or 'min'. Write Java in execute_code to perform the computation."
                    + " Inspect intermediate values via println. When done, return your structured"
                    + " final answer as your assistant message — DO NOT call any submit()"
                    + " function; the harness parses your final message against the output schema."
                    + " 'result' is the integer answer; 'operationPerformed' is a short label.")
            .maxIterations(6)
            .build();

    CodeActResult<StatsOutput> result = harness.run(new StatsInput(List.of(1, 5, 3, 2, 4), "sum"));

    assertTrue(
        result.success(),
        "Expected success. status=" + result.status() + ", error=" + result.error());
    assertNotNull(result.output().orElseThrow());
    assertEquals(15, result.output().orElseThrow().result(), "sum of 1,5,3,2,4 must be 15");
    assertNotNull(result.output().orElseThrow().operationPerformed());
    assertNotNull(result.trace(), "trace must be captured");
  }
}
