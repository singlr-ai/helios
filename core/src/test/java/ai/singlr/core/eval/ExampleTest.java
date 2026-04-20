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

class ExampleTest {

  @Test
  void ofInputExpected() {
    var ex = Example.of("hello", "world");
    assertEquals("hello", ex.input());
    assertEquals("world", ex.expected());
    assertTrue(ex.metadata().isEmpty());
  }

  @Test
  void ofWithMetadata() {
    var ex = Example.of("q", "a", Map.of("id", "42"));
    assertEquals("42", ex.metadata().get("id"));
  }

  @Test
  void nullMetadataBecomesEmpty() {
    var ex = new Example<>("i", "o", null);
    assertTrue(ex.metadata().isEmpty());
  }

  @Test
  void metadataIsImmutable() {
    var mutable = new HashMap<String, Object>();
    mutable.put("k", 1);
    var ex = Example.of("i", "o", mutable);
    assertThrows(UnsupportedOperationException.class, () -> ex.metadata().put("k2", 2));
  }

  @Test
  void metadataMutationDoesNotAffectExample() {
    var mutable = new HashMap<String, Object>();
    mutable.put("k", 1);
    var ex = Example.of("i", "o", mutable);
    mutable.put("k2", 2);
    assertEquals(1, ex.metadata().size());
  }

  @Test
  void nullsAllowed() {
    var ex = Example.of(null, null);
    assertEquals(null, ex.input());
    assertEquals(null, ex.expected());
  }
}
