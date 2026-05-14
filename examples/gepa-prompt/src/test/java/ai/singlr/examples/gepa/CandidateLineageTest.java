/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateLineageTest {

  @Test
  void seedHasNullParent() {
    var l = new CandidateLineage();
    var id = UUID.randomUUID();
    l.record(id, null, "seed-prompt");
    var entry = l.get(id).orElseThrow();
    assertNull(entry.parentId());
    assertEquals("seed-prompt", entry.candidate());
  }

  @Test
  void childPointsAtParent() {
    var l = new CandidateLineage();
    var seed = UUID.randomUUID();
    var child = UUID.randomUUID();
    l.record(seed, null, "p0");
    l.record(child, seed, "p1");
    assertEquals(seed, l.get(child).orElseThrow().parentId());
  }

  @Test
  void preservesInsertionOrder() {
    var l = new CandidateLineage();
    var ids = new UUID[5];
    for (var i = 0; i < 5; i++) {
      ids[i] = UUID.randomUUID();
      l.record(ids[i], i == 0 ? null : ids[i - 1], "p" + i);
    }
    var iter = l.entries().keySet().iterator();
    for (var i = 0; i < 5; i++) {
      assertEquals(ids[i], iter.next());
    }
  }

  @Test
  void duplicateRecordThrows() {
    var l = new CandidateLineage();
    var id = UUID.randomUUID();
    l.record(id, null, "p");
    assertThrows(IllegalArgumentException.class, () -> l.record(id, null, "p"));
  }

  @Test
  void nullCandidateIdThrows() {
    var l = new CandidateLineage();
    assertThrows(IllegalArgumentException.class, () -> l.record(null, null, "p"));
  }

  @Test
  void unknownCandidateReturnsEmpty() {
    var l = new CandidateLineage();
    assertFalse(l.get(UUID.randomUUID()).isPresent());
  }

  @Test
  void entriesMapIsUnmodifiable() {
    var l = new CandidateLineage();
    l.record(UUID.randomUUID(), null, "p");
    assertThrows(UnsupportedOperationException.class, () -> l.entries().clear());
    assertTrue(l.size() == 1);
  }
}
