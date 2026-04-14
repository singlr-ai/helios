/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SubmitFunctionTest {

  @Test
  void nullHolderThrows() {
    assertThrows(IllegalArgumentException.class, () -> SubmitFunction.create(null));
  }

  @Test
  void createReturnsHostFunction() {
    var fn = SubmitFunction.create(new AtomicReference<>());
    assertEquals("submit", fn.name());
    assertNotNull(fn.description());
    assertNotNull(fn.handler());
  }

  @Test
  @SuppressWarnings("unchecked")
  void submitStoresValue() throws Exception {
    var holder = new AtomicReference<>();
    var fn = SubmitFunction.create(holder);

    var result = (Map<String, Object>) fn.handler().handle(Map.of("output", "final answer"));

    assertEquals("final answer", holder.get());
    assertEquals("accepted", result.get("status"));
  }

  @Test
  void submitNullOutputThrows() {
    var fn = SubmitFunction.create(new AtomicReference<>());
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of()));
  }

  @Test
  void doubleSubmitThrows() throws Exception {
    var holder = new AtomicReference<>();
    var fn = SubmitFunction.create(holder);

    fn.handler().handle(Map.of("output", "first"));
    assertThrows(
        IllegalStateException.class, () -> fn.handler().handle(Map.of("output", "second")));

    assertEquals("first", holder.get());
  }
}
