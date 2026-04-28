/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

class RlmSystemPromptTest {

  public record Input(String query, List<String> documents) {}

  public record Output(String answer, List<String> sources, int totalCount) {}

  @Test
  void requiresOutputSchema() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RlmSystemPrompt.build(
                "strategy", OutputSchema.of(Input.class), null, List.of(), 5000, 50));
  }

  @Test
  void containsLoadBearingDisciplineLines() {
    var prompt =
        RlmSystemPrompt.build(
            "answer the query",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50);

    assertTrue(prompt.contains("variables you bind on one turn are still there on the next"));
    assertTrue(
        prompt.contains("Variables live across iterations"),
        "must teach the variable persistence rule");
    assertTrue(prompt.contains("truncated to ~5000"), "must announce truncation cap accurately");
    assertTrue(prompt.contains("submit"));
    assertTrue(
        prompt.contains("Do NOT mix verification and submit"),
        "must teach the verify-then-submit-alone discipline (paper Appendix B.2)");
    assertTrue(
        prompt.contains("Submit validation failed"), "must teach the validation-retry loop syntax");
  }

  @Test
  void includesStrategyWhenProvided() {
    var prompt =
        RlmSystemPrompt.build(
            "this is the strategy text",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50);
    assertTrue(prompt.contains("this is the strategy text"));
    assertTrue(prompt.contains("Task strategy"));
  }

  @Test
  void omitsStrategySectionWhenAbsent() {
    var prompt =
        RlmSystemPrompt.build(
            null, OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 5000, 50);
    assertFalse(prompt.contains("Task strategy"));
  }

  @Test
  void enumeratesInputAndOutputFieldsByName() {
    var prompt =
        RlmSystemPrompt.build(
            "x", OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 5000, 50);
    assertTrue(prompt.contains("query"));
    assertTrue(prompt.contains("documents"));
    assertTrue(prompt.contains("answer"));
    assertTrue(prompt.contains("sources"));
    assertTrue(prompt.contains("totalCount"));
  }

  @Test
  void rendersListAndIntTypes() {
    var prompt =
        RlmSystemPrompt.build(
            "x", OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 5000, 50);
    assertTrue(prompt.contains("List<String>"));
    assertTrue(prompt.contains("int"));
  }

  @Test
  void enumeratesExtraHostFunctions() {
    var customFn = new HostFunction("query_db", "Run a SQL query against the warehouse", p -> "");
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(customFn),
            5000,
            50);
    assertTrue(prompt.contains("query_db"));
    assertTrue(prompt.contains("Run a SQL query against the warehouse"));
  }

  @Test
  void skipsPredictAndSubmitInExtras() {
    var fakePredict = new HostFunction("predict", "user-supplied predict", p -> "");
    var fakeSubmit = new HostFunction("submit", "user-supplied submit", p -> "");
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(fakePredict, fakeSubmit),
            5000,
            50);
    assertFalse(
        prompt.contains("user-supplied predict"),
        "predict must come from the canonical block, not extras");
    assertFalse(prompt.contains("user-supplied submit"));
  }

  @Test
  void omitsBudgetParagraphWhenZero() {
    var prompt =
        RlmSystemPrompt.build(
            "x", OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 5000, 0);
    assertFalse(prompt.contains("Budget"));
  }

  @Test
  void includesBudgetParagraphWhenSet() {
    var prompt =
        RlmSystemPrompt.build(
            "x", OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 5000, 7);
    assertTrue(prompt.contains("at most 7"));
    assertTrue(prompt.contains("SandboxBudgetExceededException"));
  }

  @Test
  void appliesActualTruncationCapToPrompt() {
    var prompt =
        RlmSystemPrompt.build(
            "x", OutputSchema.of(Input.class), OutputSchema.of(Output.class), List.of(), 2500, 50);
    assertTrue(prompt.contains("truncated to ~2500"));
  }

  @Test
  void worksWithNullInputSchema() {
    var prompt =
        RlmSystemPrompt.build("x", null, OutputSchema.of(Output.class), List.of(), 5000, 50);
    assertTrue(prompt.contains("answer"));
  }
}
