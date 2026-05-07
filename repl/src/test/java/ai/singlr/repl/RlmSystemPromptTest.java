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

  private static final List<String> NO_BINDINGS = List.of();
  private static final List<String> BINDINGS_ALL = List.of("query", "documents");

  @Test
  void requiresOutputSchema() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RlmSystemPrompt.build(
                "strategy", OutputSchema.of(Input.class), null, List.of(), 5000, 50, NO_BINDINGS));
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
            50,
            NO_BINDINGS);

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
            50,
            NO_BINDINGS);
    assertTrue(prompt.contains("this is the strategy text"));
    assertTrue(prompt.contains("Task strategy"));
  }

  @Test
  void omitsStrategySectionWhenAbsent() {
    var prompt =
        RlmSystemPrompt.build(
            null,
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            NO_BINDINGS);
    assertFalse(prompt.contains("Task strategy"));
  }

  @Test
  void enumeratesInputAndOutputFieldsByName() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            NO_BINDINGS);
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
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            NO_BINDINGS);
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
            50,
            NO_BINDINGS);
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
            50,
            NO_BINDINGS);
    assertFalse(
        prompt.contains("user-supplied predict"),
        "predict must come from the canonical block, not extras");
    assertFalse(prompt.contains("user-supplied submit"));
  }

  @Test
  void omitsBudgetParagraphWhenZero() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            0,
            NO_BINDINGS);
    assertFalse(prompt.contains("Budget"));
  }

  @Test
  void includesBudgetParagraphWhenSet() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            7,
            NO_BINDINGS);
    assertTrue(prompt.contains("at most 7"));
    assertTrue(prompt.contains("SandboxBudgetExceededException"));
  }

  @Test
  void appliesActualTruncationCapToPrompt() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            2500,
            50,
            NO_BINDINGS);
    assertTrue(prompt.contains("truncated to ~2500"));
  }

  @Test
  void worksWithNullInputSchema() {
    var prompt =
        RlmSystemPrompt.build(
            "x", null, OutputSchema.of(Output.class), List.of(), 5000, 50, NO_BINDINGS);
    assertTrue(prompt.contains("answer"));
  }

  @Test
  void boundFieldsTeachThePromptToUseVariables() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            BINDINGS_ALL);
    assertTrue(
        prompt.contains("already bound as JShell variables"),
        "must announce the bindings explicitly");
    assertTrue(prompt.contains("query"));
    assertTrue(prompt.contains("documents"));
    assertFalse(
        prompt.contains("not pre-bound"),
        "the no-binding fallback language must NOT appear when bindings are present");
  }

  @Test
  void howTheRunEndsIsLiftedOutOfNumberedList() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            NO_BINDINGS);

    assertTrue(
        prompt.contains("## How the run ends"),
        "the run-ends discipline must be its own top-level section, not buried inside"
            + " 'How to work'");
    assertFalse(
        prompt.contains("### CRITICAL"),
        "should no longer be a sub-heading inside the numbered list");

    var howToWorkIdx = prompt.indexOf("## How to work");
    var step1Idx = prompt.indexOf("1. Explore");
    var step2Idx = prompt.indexOf("2. Persist");
    var step3Idx = prompt.indexOf("3. Printed");
    var step4Idx = prompt.indexOf("4. Use predict");
    var step5Idx = prompt.indexOf("5. Budget");
    var howEndsIdx = prompt.indexOf("## How the run ends");

    assertTrue(howToWorkIdx >= 0 && howToWorkIdx < step1Idx);
    assertTrue(step1Idx < step2Idx, "steps must appear in order");
    assertTrue(step2Idx < step3Idx);
    assertTrue(step3Idx < step4Idx);
    assertTrue(step4Idx < step5Idx);
    assertTrue(step5Idx < howEndsIdx, "step 5 (budget) must precede the run-ends section");
    assertFalse(
        prompt.contains("\n7. "),
        "step numbering must be contiguous 1-5; the legacy '7. Budget' gap was the bug");
  }

  @Test
  void runEndsSectionPresentEvenWhenBudgetOmitted() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            0,
            NO_BINDINGS);
    assertTrue(
        prompt.contains("## How the run ends"),
        "run-ends discipline is independent of the budget paragraph");
    assertFalse(
        prompt.contains("5. Budget"),
        "without a budget, step 5 must not exist — the list ends at step 4");
  }

  @Test
  void noBoundFieldsKeepsTheJsonFallbackLanguage() {
    var prompt =
        RlmSystemPrompt.build(
            "x",
            OutputSchema.of(Input.class),
            OutputSchema.of(Output.class),
            List.of(),
            5000,
            50,
            NO_BINDINGS);
    assertTrue(
        prompt.contains("not pre-bound"),
        "non-record (or empty-record) inputs fall back to JSON-in-user-message; the prompt must"
            + " say so");
    assertFalse(
        prompt.contains("Jackson") || prompt.contains("tools.jackson"),
        "the fallback must NOT mention Jackson — it isn't visible to JShell under JPMS, and the"
            + " sandbox-side Jackson reference is the bug we removed in 1.1.2. Sandbox code reads"
            + " values straight from the user message instead.");
  }
}
