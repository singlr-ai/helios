/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStateTest {

  @Test
  void buildEmptyState() {
    var state = AgentState.newBuilder().build();

    assertTrue(state.messages().isEmpty());
    assertNull(state.lastResponse());
    assertEquals(0, state.iterations());
    assertFalse(state.isComplete());
    assertNull(state.error());
  }

  @Test
  void buildWithMessages() {
    var messages = List.of(Message.system("System"), Message.user("Hello"));
    var state = AgentState.newBuilder().withMessages(messages).build();

    assertEquals(2, state.messages().size());
    assertEquals("System", state.messages().get(0).content());
  }

  @Test
  void addMessage() {
    var state =
        AgentState.newBuilder()
            .addMessage(Message.user("First"))
            .addMessage(Message.assistant("Second"))
            .build();

    assertEquals(2, state.messages().size());
  }

  @Test
  void withLastResponse() {
    var response =
        Response.newBuilder().withContent("Response").withFinishReason(FinishReason.STOP).build();
    var state = AgentState.newBuilder().withLastResponse(response).build();

    assertEquals(response, state.lastResponse());
    assertEquals(response, state.finalResponse());
  }

  @Test
  void withIterations() {
    var state = AgentState.newBuilder().withIterations(5).build();

    assertEquals(5, state.iterations());
  }

  @Test
  void incrementIterations() {
    var state =
        AgentState.newBuilder()
            .withIterations(2)
            .incrementIterations()
            .incrementIterations()
            .build();

    assertEquals(4, state.iterations());
  }

  @Test
  void withComplete() {
    var state = AgentState.newBuilder().withComplete(true).build();

    assertTrue(state.isComplete());
  }

  @Test
  void withError() {
    var state = AgentState.newBuilder().withError("Something failed").build();

    assertEquals("Something failed", state.error());
    assertTrue(state.isComplete());
    assertTrue(state.isError());
    assertFalse(state.isSuccess());
  }

  @Test
  void isSuccessWhenCompleteWithoutError() {
    var state = AgentState.newBuilder().withComplete(true).build();

    assertTrue(state.isSuccess());
    assertFalse(state.isError());
  }

  @Test
  void isNotSuccessWhenNotComplete() {
    var state = AgentState.newBuilder().build();

    assertFalse(state.isSuccess());
    assertFalse(state.isError());
  }

  @Test
  void copyBuilder() {
    var original =
        AgentState.newBuilder()
            .addMessage(Message.user("Hello"))
            .withIterations(3)
            .withComplete(true)
            .build();

    var copy = AgentState.newBuilder(original).withIterations(5).build();

    assertEquals(3, original.iterations());
    assertEquals(5, copy.iterations());
    assertEquals(1, copy.messages().size());
    assertTrue(copy.isComplete());
  }

  @Test
  void messagesAreImmutable() {
    var state = AgentState.newBuilder().addMessage(Message.user("Test")).build();

    var messages = state.messages();
    try {
      messages.add(Message.user("Another"));
    } catch (UnsupportedOperationException e) {
      // Expected - list is immutable
    }
    assertEquals(1, state.messages().size());
  }
}
