/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IterationContextTest {

  @Test
  void defensiveCopyOfSetsAndList() {
    var required = new HashSet<String>();
    required.add("a");
    var called = new HashSet<String>();
    called.add("a");
    var messages = new ArrayList<Message>();
    messages.add(Message.user("hi"));
    var response =
        Response.newBuilder().withContent("r").withFinishReason(FinishReason.STOP).build();

    var ctx = new IterationContext(1, 10, 0, required, called, 1, response, messages);

    required.add("b");
    called.add("c");
    messages.add(Message.user("later"));

    assertEquals(Set.of("a"), ctx.requiredTools());
    assertEquals(Set.of("a"), ctx.toolsCalledSoFar());
    assertEquals(1, ctx.messages().size());
  }

  @Test
  void accessorsReturnExpectedValues() {
    var response =
        Response.newBuilder().withContent("ok").withFinishReason(FinishReason.STOP).build();
    var messages = List.<Message>of(Message.user("hi"));
    var ctx =
        new IterationContext(
            3, 10, 2, Set.of("search"), Set.of("search", "lookup"), 5, response, messages);

    assertEquals(3, ctx.iteration());
    assertEquals(10, ctx.maxIterations());
    assertEquals(2, ctx.minIterations());
    assertEquals(Set.of("search"), ctx.requiredTools());
    assertEquals(Set.of("search", "lookup"), ctx.toolsCalledSoFar());
    assertEquals(5, ctx.totalToolCallCount());
    assertEquals(response, ctx.lastResponse());
    assertEquals(messages, ctx.messages());
  }

  @Test
  void messagesSnapshotIsImmutable() {
    var response = Response.newBuilder().withContent("r").build();
    var ctx =
        new IterationContext(
            1, 10, 0, Set.of(), Set.of(), 0, response, List.of(Message.user("hi")));

    assertThrows(UnsupportedOperationException.class, () -> ctx.messages().add(Message.user("x")));
  }

  @Test
  void requiredToolsSnapshotIsImmutable() {
    var response = Response.newBuilder().withContent("r").build();
    var ctx = new IterationContext(1, 10, 0, Set.of("a"), Set.of("a"), 1, response, List.of());

    assertThrows(UnsupportedOperationException.class, () -> ctx.requiredTools().add("b"));
    assertThrows(UnsupportedOperationException.class, () -> ctx.toolsCalledSoFar().add("b"));
  }

  @Test
  void allowAndStopAreDistinctRecords() {
    IterationAction allow = IterationAction.allow();
    IterationAction stop = IterationAction.stop();

    assertTrue(allow instanceof IterationAction.Allow);
    assertTrue(stop instanceof IterationAction.Stop);
  }

  @Test
  void injectRequiresNonBlankMessage() {
    assertThrows(IllegalArgumentException.class, () -> IterationAction.inject(null));
    assertThrows(IllegalArgumentException.class, () -> IterationAction.inject(""));
    assertThrows(IllegalArgumentException.class, () -> IterationAction.inject("   "));
  }

  @Test
  void injectHoldsMessage() {
    var inject = IterationAction.inject("keep going");
    assertEquals("keep going", inject.message());
  }
}
