/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalResultTest {

  @Test
  void defaultsEmptyList() {
    var r = new EvalResult<>(0.0, null);
    assertTrue(r.perExample().isEmpty());
  }

  @Test
  void copiesList() {
    var mutable = new ArrayList<ExampleResult<String, String>>();
    mutable.add(new ExampleResult<>(Example.of("i", "o"), "o", 1.0, null, Result.success(null)));
    var r = new EvalResult<>(1.0, mutable);
    assertThrows(UnsupportedOperationException.class, () -> r.perExample().add(null));
  }

  @Test
  void meanPropagated() {
    var r = new EvalResult<String, String>(0.75, List.of());
    assertEquals(0.75, r.meanScore());
  }
}
