/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ObjectiveTest {

  @Test
  void lambdaObjective() throws Exception {
    Objective<Integer> obj = i -> Score.of(i * 2.0);
    assertEquals(6.0, obj.evaluate(3).value());
  }

  @Test
  void exceptionsPropagate() {
    Objective<String> throwing =
        c -> {
          throw new IllegalStateException("boom");
        };
    assertThrows(IllegalStateException.class, () -> throwing.evaluate("x"));
  }
}
