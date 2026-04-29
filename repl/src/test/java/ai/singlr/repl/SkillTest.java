/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillTest {

  private static HostFunction fn(String name) {
    return new HostFunction(name, "desc-" + name, params -> "ok");
  }

  @Test
  void rejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () -> new Skill("", "instructions", List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Skill(null, "instructions", List.of()));
  }

  @Test
  void normalizesNullInstructionsAndTools() {
    var skill = new Skill("name", null, null);
    assertEquals("", skill.instructions());
    assertTrue(skill.tools().isEmpty());
  }

  @Test
  void rejectsReservedToolNames() {
    assertThrows(
        IllegalArgumentException.class, () -> new Skill("rogue", "x", List.of(fn("predict"))));
    assertThrows(
        IllegalArgumentException.class, () -> new Skill("rogue", "x", List.of(fn("submit"))));
  }

  @Test
  void mergeEmptyReturnsEmptyMergedSkill() {
    var merged = Skill.merge(List.of());
    assertEquals("merged", merged.name());
    assertEquals("", merged.instructions());
    assertTrue(merged.tools().isEmpty());

    var alsoEmpty = Skill.merge(null);
    assertEquals("merged", alsoEmpty.name());
  }

  @Test
  void mergeConcatenatesInstructionsUnderNamedHeaders() {
    var pdf = new Skill("pdf", "Use parsePdf to read PDFs.", List.of(fn("parsePdf")));
    var sheet =
        new Skill(
            "spreadsheet",
            "Use evalFormula to evaluate spreadsheet formulas.",
            List.of(fn("evalFormula")));
    var merged = Skill.merge(List.of(pdf, sheet));

    assertTrue(merged.instructions().contains("## Skill: pdf"));
    assertTrue(merged.instructions().contains("## Skill: spreadsheet"));
    assertTrue(merged.instructions().contains("Use parsePdf"));
    assertTrue(merged.instructions().contains("Use evalFormula"));
  }

  @Test
  void mergeAccumulatesAllTools() {
    var a = new Skill("a", "do a", List.of(fn("alpha"), fn("alpha2")));
    var b = new Skill("b", "do b", List.of(fn("beta")));
    var merged = Skill.merge(List.of(a, b));
    assertEquals(3, merged.tools().size());
  }

  @Test
  void mergeSkipsBlankInstructions() {
    var quiet = new Skill("quiet", "", List.of(fn("alpha")));
    var loud = new Skill("loud", "loud instructions", List.of(fn("beta")));
    var merged = Skill.merge(List.of(quiet, loud));
    assertTrue(merged.instructions().contains("## Skill: loud"));
    assertTrue(
        !merged.instructions().contains("## Skill: quiet"),
        "skill with blank instructions doesn't get a header");
  }

  @Test
  void envTipsDefaultEmptyInThreeArgConstructor() {
    var skill = new Skill("name", "instructions", List.of());
    assertEquals("", skill.envTips());
  }

  @Test
  void envTipsPreservedInFourArgConstructor() {
    var skill =
        new Skill("name", "instructions", "1. Do this first.\n2. Then this.", List.of(fn("alpha")));
    assertEquals("1. Do this first.\n2. Then this.", skill.envTips());
  }

  @Test
  void envTipsNullNormalizedToEmpty() {
    var skill = new Skill("name", "instructions", null, List.of());
    assertEquals("", skill.envTips());
  }

  @Test
  void mergeRendersEnvTipsUnderStrategyHeader() {
    var skill =
        new Skill(
            "kubera",
            "Specialist signatures for analysis.",
            "1. Run macro first.\n2. Then geo.\n3. Then DA.",
            List.of(fn("market_quote")));
    var merged = Skill.merge(List.of(skill));
    assertTrue(
        merged.instructions().contains("## Skill: kubera"),
        "skill body still rendered under Skill header, got:\n" + merged.instructions());
    assertTrue(
        merged.instructions().contains("## Strategy: kubera"),
        "envTips rendered under Strategy header, got:\n" + merged.instructions());
    assertTrue(merged.instructions().contains("Run macro first"));
    assertTrue(merged.instructions().contains("Then DA"));
  }

  @Test
  void mergeKeepsSkillBodyWhenEnvTipsBlank() {
    var skill = new Skill("foo", "Just instructions, no tips.", "", List.of(fn("foo_op")));
    var merged = Skill.merge(List.of(skill));
    assertTrue(merged.instructions().contains("## Skill: foo"));
    assertTrue(
        !merged.instructions().contains("## Strategy: foo"),
        "no Strategy header when envTips is blank");
  }

  @Test
  void mergeRendersOnlyEnvTipsWhenInstructionsBlank() {
    var skill = new Skill("foo", "", "1. Do X.\n2. Do Y.", List.of(fn("foo_op")));
    var merged = Skill.merge(List.of(skill));
    assertTrue(
        !merged.instructions().contains("## Skill: foo"), "no Skill header when body is blank");
    assertTrue(merged.instructions().contains("## Strategy: foo"));
    assertTrue(merged.instructions().contains("Do X"));
  }

  @Test
  void mergePreservesPerSkillSectionOrder() {
    // For each skill, instructions section appears before its strategy section. Across skills,
    // declaration order is preserved.
    var a = new Skill("a", "A's instructions.", "A's tips.", List.of(fn("a_op")));
    var b = new Skill("b", "B's instructions.", "B's tips.", List.of(fn("b_op")));
    var merged = Skill.merge(List.of(a, b));
    var combined = merged.instructions();
    var aSkill = combined.indexOf("## Skill: a");
    var aStrategy = combined.indexOf("## Strategy: a");
    var bSkill = combined.indexOf("## Skill: b");
    var bStrategy = combined.indexOf("## Strategy: b");
    assertTrue(aSkill < aStrategy, "A's body before A's strategy");
    assertTrue(aStrategy < bSkill, "A's sections before B's");
    assertTrue(bSkill < bStrategy, "B's body before B's strategy");
  }

  @Test
  void mergedEnvTipsFieldIsBlankBecauseFoldedIntoInstructions() {
    // The merged Skill carries everything in instructions; envTips on the merged record is empty
    // by design — that's what makes the "flatten merged.instructions() into harness strategy"
    // wire-up trivial.
    var skill = new Skill("a", "body", "tips", List.of(fn("a_op")));
    var merged = Skill.merge(List.of(skill));
    assertEquals("", merged.envTips());
  }

  @Test
  void mergeRaisesOnToolNameConflict() {
    var a = new Skill("a", "do a", List.of(fn("shared")));
    var b = new Skill("b", "do b", List.of(fn("shared")));
    var error = assertThrows(IllegalArgumentException.class, () -> Skill.merge(List.of(a, b)));
    assertTrue(error.getMessage().contains("conflict"));
    assertTrue(error.getMessage().contains("shared"));
    assertTrue(error.getMessage().contains("'a'"));
    assertTrue(error.getMessage().contains("'b'"));
  }
}
