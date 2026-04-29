/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.rlm.jpms;

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
 * Verifies {@link RlmHarness} works when the consuming module declares its dependencies via {@code
 * module-info.java} (JPMS mode). The classpath-mode counterpart in {@code rlm-demo} does not
 * exercise this — surefire runs that module's tests on the unnamed module, so the sandbox
 * subprocess inherits {@code -cp} and JShell's compiler resolves Jackson via classpath.
 *
 * <p>In JPMS mode the parent process is launched with {@code --module-path}, the sandbox subprocess
 * inherits that, and JShell's internal javac must be told how to resolve modulepath modules —
 * otherwise pre-eval snippets that reference {@code tools.jackson.databind.*} fail to compile and
 * {@link RlmHarness#run} returns {@code FAILED} with "package tools.jackson.databind.json does not
 * exist". This test is the regression lock.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
public class RlmHarnessJpmsIntegrationTest {

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
  void harnessRunsUnderJpms() {
    var rlm =
        RlmHarness.builder(StatsInput.class, StatsOutput.class)
            .model(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Compute the requested operation on the numbers. The 'operation' field will be"
                    + " one of 'sum', 'max', or 'min'. Write JShell code in execute_code to"
                    + " perform the computation, then submit the result.")
            .maxIterations(5)
            .maxLlmCalls(3)
            .build();

    var result = rlm.run(new StatsInput(List.of(1, 5, 3, 2, 4), "sum"));

    assertTrue(
        result.success(),
        "Expected success under JPMS. status=" + result.status() + ", error=" + result.error());
    assertNotNull(result.output());
    assertEquals(15, result.output().result());
  }
}
