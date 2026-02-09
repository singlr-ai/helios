/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContextTest {

  @Test
  void createGeneratesUniqueId() {
    var s1 = SessionContext.create();
    var s2 = SessionContext.create();

    assertNotNull(s1.sessionId());
    assertNotNull(s2.sessionId());
    assertNotEquals(s1.sessionId(), s2.sessionId());
    assertTrue(s1.metadata().isEmpty());
  }

  @Test
  void ofWrapsExistingId() {
    var id = Ids.newId();
    var session = SessionContext.of(id);

    assertEquals(id, session.sessionId());
    assertTrue(session.metadata().isEmpty());
  }

  @Test
  void builderWithAllFields() {
    var id = Ids.newId();
    var session =
        SessionContext.newBuilder().withSessionId(id).withMetadata(Map.of("env", "test")).build();

    assertEquals(id, session.sessionId());
    assertEquals("test", session.metadata().get("env"));
  }

  @Test
  void builderGeneratesIdWhenOmitted() {
    var session = SessionContext.newBuilder().build();

    assertNotNull(session.sessionId());
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
  void metadataIsImmutable() {
    var session = SessionContext.create();

    assertThrows(UnsupportedOperationException.class, () -> session.metadata().put("key", "val"));
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
