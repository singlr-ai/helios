/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScoreTest {

  @Test
  void ofValue() {
    var s = Score.of(1.5);
    assertEquals(1.5, s.value());
    assertTrue(s.secondary().isEmpty());
    assertTrue(s.diagnostics().isEmpty());
  }

  @Test
  void ofValueAndSecondary() {
    var s = Score.of(1.0, Map.of("latencyMs", 42.0));
    assertEquals(42.0, s.secondary().get("latencyMs"));
    assertTrue(s.diagnostics().isEmpty());
  }

  @Test
  void fullConstructor() {
    var s = new Score(2.0, Map.of("a", 1.0), Map.of("note", "fast"));
    assertEquals(2.0, s.value());
    assertEquals(1.0, s.secondary().get("a"));
    assertEquals("fast", s.diagnostics().get("note"));
  }

  @Test
  void nullMapsBecomeEmpty() {
    var s = new Score(1.0, null, null);
    assertTrue(s.secondary().isEmpty());
    assertTrue(s.diagnostics().isEmpty());
  }

  @Test
  void secondaryIsImmutable() {
    var mutable = new HashMap<String, Double>();
    mutable.put("k", 1.0);
    var s = Score.of(1.0, mutable);
    assertThrows(UnsupportedOperationException.class, () -> s.secondary().put("k2", 2.0));
  }

  @Test
  void diagnosticsIsImmutable() {
    var mutable = new HashMap<String, Object>();
    mutable.put("k", "v");
    var s = new Score(1.0, Map.of(), mutable);
    assertThrows(UnsupportedOperationException.class, () -> s.diagnostics().put("k2", "v2"));
  }
}
