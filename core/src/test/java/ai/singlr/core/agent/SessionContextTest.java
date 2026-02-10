/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContextTest {

  @Test
  void ofStringGeneratesUniqueIds() {
    var s1 = SessionContext.of("hello");
    var s2 = SessionContext.of("hello");

    assertNotNull(s1.sessionId());
    assertNotNull(s2.sessionId());
    assertNotEquals(s1.sessionId(), s2.sessionId());
    assertEquals("hello", s1.userInput());
    assertNull(s1.userId());
    assertTrue(s1.promptVars().isEmpty());
    assertTrue(s1.metadata().isEmpty());
  }

  @Test
  void ofWithPromptVars() {
    var session = SessionContext.of("hello", Map.of("mode", "debug"));

    assertNotNull(session.sessionId());
    assertEquals("hello", session.userInput());
    assertEquals("debug", session.promptVars().get("mode"));
    assertTrue(session.metadata().isEmpty());
  }

  @Test
  void ofWithExistingSessionId() {
    var id = Ids.newId();
    var session = SessionContext.of(id, "hello");

    assertEquals(id, session.sessionId());
    assertEquals("hello", session.userInput());
    assertTrue(session.promptVars().isEmpty());
    assertTrue(session.metadata().isEmpty());
  }

  @Test
  void builderWithAllFields() {
    var id = Ids.newId();
    var session =
        SessionContext.newBuilder()
            .withSessionId(id)
            .withUserInput("hello")
            .withPromptVars(Map.of("mode", "debug"))
            .withMetadata(Map.of("env", "test"))
            .build();

    assertEquals(id, session.sessionId());
    assertEquals("hello", session.userInput());
    assertEquals("debug", session.promptVars().get("mode"));
    assertEquals("test", session.metadata().get("env"));
  }

  @Test
  void builderGeneratesIdWhenOmitted() {
    var session = SessionContext.newBuilder().withUserInput("hello").build();

    assertNotNull(session.sessionId());
    assertEquals("hello", session.userInput());
    assertTrue(session.promptVars().isEmpty());
    assertTrue(session.metadata().isEmpty());
  }

  @Test
  void builderWithGroupId() {
    var session = SessionContext.newBuilder().withGroupId("eval-batch-42").build();

    assertEquals("eval-batch-42", session.metadata().get("groupId"));
    assertNotNull(session.sessionId());
  }

  @Test
  void builderWithNullMetadata() {
    var session = SessionContext.newBuilder().withMetadata(null).build();

    assertNotNull(session.metadata());
    assertTrue(session.metadata().isEmpty());
  }

  @Test
  void builderWithNullPromptVars() {
    var session = SessionContext.newBuilder().withPromptVars(null).build();

    assertNotNull(session.promptVars());
    assertTrue(session.promptVars().isEmpty());
  }

  @Test
  void builderWithoutUserInput() {
    var session = SessionContext.newBuilder().build();

    assertNotNull(session.sessionId());
    assertNull(session.userInput());
  }

  @Test
  void builderWithUserId() {
    var session = SessionContext.newBuilder().withUserId("user-42").withUserInput("hello").build();

    assertEquals("user-42", session.userId());
    assertNotNull(session.sessionId());
    assertEquals("hello", session.userInput());
  }

  @Test
  void factoryMethodsHaveNullUserId() {
    assertNull(SessionContext.of("hello").userId());
    assertNull(SessionContext.of(Ids.newId(), "hello").userId());
    assertNull(SessionContext.of("hello", Map.of("k", "v")).userId());
  }

  @Test
  void metadataIsImmutable() {
    var session = SessionContext.of("hello");

    assertThrows(UnsupportedOperationException.class, () -> session.metadata().put("key", "val"));
  }

  @Test
  void promptVarsIsImmutable() {
    var session = SessionContext.of("hello", Map.of("key", "val"));

    assertThrows(UnsupportedOperationException.class, () -> session.promptVars().put("new", "val"));
  }

  @Test
  void metadataFromBuilderIsImmutable() {
    var session =
        SessionContext.newBuilder().withMetadata(Map.of("key", "val")).withGroupId("g1").build();

    assertThrows(UnsupportedOperationException.class, () -> session.metadata().put("new", "val"));
    assertEquals("val", session.metadata().get("key"));
    assertEquals("g1", session.metadata().get("groupId"));
  }
}
