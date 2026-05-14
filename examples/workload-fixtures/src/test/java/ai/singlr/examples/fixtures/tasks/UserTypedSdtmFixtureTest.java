/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.examples.fixtures.tasks.UserTypedSdtmFixture.FieldInference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserTypedSdtmFixtureTest {

  @Test
  void expectedGroupingMergesInferencesByTarget() {
    var grouping =
        UserTypedSdtmFixture.expectedGrouping(
            List.of(
                new FieldInference("a1", "TARGET_A"),
                new FieldInference("a2", "TARGET_A"),
                new FieldInference("b1", "TARGET_B")));
    assertEquals(List.of("a1", "a2"), grouping.get("TARGET_A"));
    assertEquals(List.of("b1"), grouping.get("TARGET_B"));
  }

  @Test
  void matchesIsTrueForReorderedEquivalents() {
    var expected = Map.of("X", List.of("a", "b"));
    var actual = Map.of("X", List.of("b", "a"));
    assertTrue(UserTypedSdtmFixture.matches(actual, expected));
  }

  @Test
  void matchesIsFalseOnMissingKey() {
    var expected = Map.of("X", List.of("a"));
    var actual = Map.<String, List<String>>of();
    assertFalse(UserTypedSdtmFixture.matches(actual, expected));
  }

  @Test
  void matchesIsFalseOnNullActual() {
    assertFalse(UserTypedSdtmFixture.matches(null, Map.of("X", List.of("a"))));
  }

  @Test
  void matchesIsFalseOnDifferentValues() {
    var expected = Map.of("X", List.of("a", "b"));
    var actual = Map.of("X", List.of("a", "c"));
    assertFalse(UserTypedSdtmFixture.matches(actual, expected));
  }
}
