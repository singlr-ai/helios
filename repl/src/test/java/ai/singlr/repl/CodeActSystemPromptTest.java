/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeActSystemPromptTest {

  public record Input(String query, int limit) {}

  public record Output(String answer, double confidence) {}

  private static final OutputSchema<Input> IN = OutputSchema.of(Input.class);
  private static final OutputSchema<Output> OUT = OutputSchema.of(Output.class);

  @Test
  void outputSchemaIsRequired() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CodeActSystemPrompt.build("strat", IN, null, List.of(), 5000, List.of("query")));
  }

  @Test
  void boundFieldsTriggersJShellVariablesParagraph() {
    var prompt =
        CodeActSystemPrompt.build(
            "be careful", IN, OUT, List.of(), 5000, List.of("query", "limit"));
    assertTrue(prompt.contains("already bound as JShell variables"));
    assertTrue(prompt.contains("query"));
    assertTrue(prompt.contains("limit"));
    assertTrue(prompt.contains("answer"));
    assertTrue(prompt.contains("confidence"));
  }

  @Test
  void unboundFieldsTriggersReadFromUserMessageParagraph() {
    var prompt = CodeActSystemPrompt.build("plan", IN, OUT, List.of(), 5000, List.of());
    assertTrue(prompt.contains("read each one as a literal from the user"));
  }

  @Test
  void blankStrategyOmitsStrategyHeader() {
    var prompt = CodeActSystemPrompt.build("   ", IN, OUT, List.of(), 5000, List.of("query"));
    assertFalse(prompt.contains("## Task strategy"));
  }

  @Test
  void nonBlankStrategyIncludesStrategyHeader() {
    var prompt =
        CodeActSystemPrompt.build("look both ways", IN, OUT, List.of(), 5000, List.of("query"));
    assertTrue(prompt.contains("## Task strategy"));
    assertTrue(prompt.contains("look both ways"));
  }

  @Test
  void customHostFunctionsAppearUnderToolsBlock() {
    var fn =
        new HostFunction(
            "kb_grep",
            "search the index",
            List.of(HostParameter.required("query", ParameterType.STRING, "search term")),
            params -> "");
    var prompt = CodeActSystemPrompt.build(null, IN, OUT, List.of(fn), 5000, List.of("query"));
    assertTrue(prompt.contains("## Your tools"));
    assertTrue(prompt.contains("kb_grep"));
    assertTrue(prompt.contains("search the index"));
  }

  @Test
  void promptOmitsSubmitMentionAndPredictTeachingAndBudget() {
    var prompt = CodeActSystemPrompt.build("plan", IN, OUT, List.of(), 5000, List.of("query"));
    assertFalse(
        prompt.toLowerCase().contains("submit"),
        "CodeAct prompt must not mention submit at all — even to negate it. Telling the model"
            + " 'do not call submit' primes it to reach for a function we never registered.");
    assertFalse(
        prompt.contains("predict(instructions, input)"),
        "CodeAct prompt must not document the predict() host function");
    assertFalse(prompt.contains("Budget:"), "CodeAct has no LLM-call budget paragraph");
  }

  @Test
  void promptPositivelyDescribesSandboxConstraints() {
    var prompt = CodeActSystemPrompt.build("plan", IN, OUT, List.of(), 5000, List.of("query"));
    assertTrue(
        prompt.contains("JDK 25"),
        "Prompt must positively state the runtime — JDK 25 JShell — rather than relying on the"
            + " model to guess");
    assertTrue(
        prompt.contains("No third-party libraries"),
        "Prompt must positively constrain the sandbox to the JDK standard library plus host"
            + " functions, with no third-party libs available");
  }

  @Test
  void promptInstructsFinalMessageIsTheAnswer() {
    var prompt = CodeActSystemPrompt.build("plan", IN, OUT, List.of(), 5000, List.of("query"));
    assertTrue(prompt.contains("your assistant message IS the answer"));
  }

  @Test
  void truncationCapIsRendered() {
    var prompt = CodeActSystemPrompt.build("plan", IN, OUT, List.of(), 1234, List.of("query"));
    assertTrue(prompt.contains("~1234"));
  }

  @Test
  void boundFieldsButNullInputSchemaRendersHeaderOnly() {
    var prompt = CodeActSystemPrompt.build("plan", null, OUT, List.of(), 5000, List.of("query"));
    assertTrue(prompt.contains("already bound as JShell variables"));
  }
}
