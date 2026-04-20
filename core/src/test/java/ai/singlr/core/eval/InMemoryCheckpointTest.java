/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InMemoryCheckpointTest {

  @Test
  void snapshotReflectsCurrent() {
    var cp = new InMemoryCheckpoint<>("initial");
    assertEquals("initial", cp.snapshot());
    assertEquals("initial", cp.current());
  }

  @Test
  void setReplacesCurrent() {
    var cp = new InMemoryCheckpoint<>("a");
    cp.set("b");
    assertEquals("b", cp.current());
    assertEquals("b", cp.snapshot());
  }

  @Test
  void restoreUndoesChanges() {
    var cp = new InMemoryCheckpoint<>("start");
    var snap = cp.snapshot();
    cp.set("changed");
    assertEquals("changed", cp.current());
    cp.restore(snap);
    assertEquals("start", cp.current());
  }

  @Test
  void supportsNullSnapshots() {
    var cp = new InMemoryCheckpoint<String>(null);
    assertEquals(null, cp.snapshot());
    cp.set("real");
    assertEquals("real", cp.current());
    cp.restore(null);
    assertEquals(null, cp.current());
  }
}
