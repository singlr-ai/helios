/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsolidationContextTest {

  @Test
  void rejectsNullMemory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsolidationContext("agent", "user", null, List.of()));
  }

  @Test
  void rejectsNullHistory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsolidationContext("agent", "user", new InMemoryMemory(), null));
  }

  @Test
  void historyIsDefensivelyCopied() {
    var mutable = new ArrayList<Message>();
    mutable.add(Message.user("hi"));
    var ctx = new ConsolidationContext("a", "u", new InMemoryMemory(), mutable);

    mutable.add(Message.user("added later"));

    assertEquals(1, ctx.recentHistory().size());
    assertNotSame(mutable, ctx.recentHistory());
  }

  @Test
  void agentIdAndUserIdMayBeNull() {
    var ctx = new ConsolidationContext(null, null, new InMemoryMemory(), List.of());
    assertEquals(null, ctx.agentId());
    assertEquals(null, ctx.userId());
  }
}
