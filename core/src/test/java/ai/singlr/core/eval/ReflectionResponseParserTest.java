/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReflectionResponseParserTest {

  @Test
  void cleanFreeTextStripsHereIsTheRevisedPromptPreamble() {
    var raw = "Here is the revised prompt:\nDo the thing carefully.";
    assertEquals("Do the thing carefully.", ReflectionResponseParser.cleanFreeText(raw));
  }

  @Test
  void cleanFreeTextStripsRevisedPromptColonPreamble() {
    var raw = "Revised prompt:\nDo the thing carefully.";
    assertEquals("Do the thing carefully.", ReflectionResponseParser.cleanFreeText(raw));
  }

  @Test
  void cleanFreeTextStripsCodeFences() {
    var raw = "```\nDo the thing.\n```";
    assertEquals("Do the thing.", ReflectionResponseParser.cleanFreeText(raw));
  }

  @Test
  void cleanFreeTextStripsLanguageTaggedCodeFences() {
    var raw = "```markdown\nDo the thing.\n```";
    assertEquals("Do the thing.", ReflectionResponseParser.cleanFreeText(raw));
  }

  @Test
  void cleanFreeTextHandlesBlankInput() {
    assertEquals("", ReflectionResponseParser.cleanFreeText(""));
    assertEquals("", ReflectionResponseParser.cleanFreeText(null));
    assertEquals("", ReflectionResponseParser.cleanFreeText("   "));
  }

  @Test
  void cleanFreeTextLeavesBareContentAlone() {
    var raw = "Do the thing.";
    assertEquals("Do the thing.", ReflectionResponseParser.cleanFreeText(raw));
  }

  @Test
  void isAcceptableRejectsBlank() {
    assertFalse(ReflectionResponseParser.isAcceptable("", "parent", 0.25));
    assertFalse(ReflectionResponseParser.isAcceptable(null, "parent", 0.25));
  }

  @Test
  void isAcceptableRejectsShortResponse() {
    var parent = "x".repeat(100);
    var cleaned = "x".repeat(10); // 10% of parent
    assertFalse(ReflectionResponseParser.isAcceptable(cleaned, parent, 0.25));
  }

  @Test
  void isAcceptableAcceptsLongEnoughResponse() {
    var parent = "x".repeat(100);
    var cleaned = "x".repeat(50);
    assertTrue(ReflectionResponseParser.isAcceptable(cleaned, parent, 0.25));
  }

  @Test
  void isAcceptableAcceptsWhenMinFractionZero() {
    var parent = "x".repeat(100);
    var cleaned = "x";
    assertTrue(ReflectionResponseParser.isAcceptable(cleaned, parent, 0));
  }

  @Test
  void isAcceptableAcceptsAnyWhenParentBlank() {
    assertTrue(ReflectionResponseParser.isAcceptable("non-blank", "", 0.25));
    assertTrue(ReflectionResponseParser.isAcceptable("non-blank", null, 0.25));
  }
}
