/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectionPromptTemplateTest {

  @Test
  void blankInstructionsUseDefaultHeader() {
    var prompt = ReflectionPromptTemplate.build("", "parent", List.of(), 0);
    assertTrue(prompt.contains("You are improving a system prompt"));
  }

  @Test
  void customInstructionsOverrideDefault() {
    var prompt = ReflectionPromptTemplate.build("BE LACONIC", "parent", List.of(), 0);
    assertTrue(prompt.startsWith("BE LACONIC"));
    assertFalse(prompt.contains("You are improving a system prompt"));
  }

  @Test
  void noTracesYieldsExplicitMessage() {
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(), 0);
    assertTrue(prompt.contains("(no traces available)"));
  }

  @Test
  void renderingIncludesAllTraceFields() {
    var trace = new TraceFeedback("ask me", "the answer", "wrong", 0.0, "missed the mark", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertTrue(prompt.contains("input: ask me"));
    assertTrue(prompt.contains("expected: the answer"));
    assertTrue(prompt.contains("actual: wrong"));
    assertTrue(prompt.contains("score: 0.0"));
    assertTrue(prompt.contains("feedback: missed the mark"));
  }

  @Test
  void nullExampleExpectedOmitsExpectedLine() {
    var trace = new TraceFeedback("ask me", null, "wrong", 0.0, "", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertFalse(prompt.contains("expected:"));
  }

  @Test
  void nullExampleInputRendersAsLiteralNull() {
    var trace = new TraceFeedback(null, "exp", "act", 0.0, "", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertTrue(prompt.contains("input: (null)"));
  }

  @Test
  void blankFeedbackOmitsFeedbackLine() {
    var trace = new TraceFeedback("in", "exp", "act", 0.0, "", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertFalse(prompt.contains("feedback:"));
  }

  @Test
  void tripleQuotesInFeedbackEscaped() {
    var trace = new TraceFeedback("in", "exp", "act", 0.0, "weird \"\"\" stuff", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertFalse(prompt.contains("weird \"\"\" stuff"));
    assertTrue(prompt.contains("\\\"\\\"\\\""));
  }

  @Test
  void newlinesInTraceFieldsCollapseToSpace() {
    var trace = new TraceFeedback("line1\nline2", "exp", "act", 0.0, "fb1\r\nfb2", null);
    var prompt = ReflectionPromptTemplate.build(null, "parent", List.of(trace), 0);
    assertTrue(prompt.contains("input: line1 line2"));
    assertTrue(prompt.contains("feedback: fb1  fb2"));
  }

  @Test
  void maxFeedbackCharsDropsTailTraces() {
    var traces =
        List.of(
            new TraceFeedback("a", "e", "ac", 0.0, "", null),
            new TraceFeedback("b", "e", "ac", 0.0, "", null),
            new TraceFeedback("c", "e", "ac", 0.0, "", null));
    var prompt = ReflectionPromptTemplate.build(null, "parent", traces, 1);
    assertTrue(prompt.contains("additional trace(s) omitted"));
  }
}
