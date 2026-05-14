/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FixtureRunnerTest {

  @Test
  void setupTurnsIsZeroWhenFixtureHasNoCodeSnippets() {
    assertEquals(0, FixtureRunner.computeSetupTurns(3, 0));
  }

  @Test
  void setupTurnsIsTotalMinusCodeCalls() {
    assertEquals(1, FixtureRunner.computeSetupTurns(4, 3));
  }

  @Test
  void setupTurnsClampsToZeroWhenCallsExceedIterations() {
    assertEquals(0, FixtureRunner.computeSetupTurns(2, 5));
  }

  @Test
  void recoveryHeuristicCountsCastsAgainstBoundFieldNames() {
    var snippets =
        List.of(
            "var first = (Map<String,Object>) files.get(0);",
            "System.out.println(inferences.size());",
            "for (var i : (List<Object>) inferences) {}");
    var bound = List.of("files", "inferences");
    assertEquals(2, FixtureRunner.countRecoveryIterations(snippets, bound));
  }

  @Test
  void recoveryHeuristicReturnsZeroForBareAgentFixtures() {
    assertEquals(0, FixtureRunner.countRecoveryIterations(List.of(), List.of()));
    assertEquals(0, FixtureRunner.countRecoveryIterations(List.of("println(x);"), List.of()));
  }

  @Test
  void recoveryHeuristicIgnoresCastsOnUnrelatedNames() {
    var snippets = List.of("var s = (String) someOther;");
    assertEquals(0, FixtureRunner.countRecoveryIterations(snippets, List.of("files")));
  }
}
