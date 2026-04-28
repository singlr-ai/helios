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
