/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.AgentRun;
import ai.singlr.core.runtime.AgentRunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgRunStoreTest {

  private PgRunStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
    store = new PgRunStore(PgTestSupport.pgConfig());
  }

  private static AgentRun newRun(UUID id, AgentRunStatus status) {
    return AgentRun.newBuilder()
        .withRunId(id)
        .withSessionId(Ids.newId())
        .withAgentId("research-bot")
        .withUserId("u-1")
        .withStatus(status)
        .withIteration(0)
        .withStartedAt(Ids.now())
        .withLastCheckpointAt(Ids.now())
        .build();
  }

  @Test
  void checkpointInsertsThenUpdates() {
    var id = Ids.newId();
    store.checkpoint(newRun(id, AgentRunStatus.RUNNING));
    var first = store.find(id).orElseThrow();
    assertEquals(AgentRunStatus.RUNNING, first.status());

    store.checkpoint(
        AgentRun.newBuilder(first)
            .withStatus(AgentRunStatus.COMPLETED)
            .withIteration(5)
            .withEndedAt(Ids.now())
            .build());
    var second = store.find(id).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, second.status());
    assertEquals(5, second.iteration());
    assertNotNull(second.endedAt());
  }

  @Test
  void findUnknownReturnsEmpty() {
    assertTrue(store.find(Ids.newId()).isEmpty());
  }

  @Test
  void findNullReturnsEmpty() {
    assertTrue(store.find(null).isEmpty());
  }

  @Test
  void findByStatusFiltersAndOrdersDescending() throws Exception {
    var older = Ids.newId();
    var newer = Ids.newId();
    store.checkpoint(
        AgentRun.newBuilder(newRun(older, AgentRunStatus.RUNNING))
            .withLastCheckpointAt(OffsetDateTime.now().minusMinutes(10))
            .build());
    Thread.sleep(5);
    store.checkpoint(
        AgentRun.newBuilder(newRun(newer, AgentRunStatus.RUNNING))
            .withLastCheckpointAt(OffsetDateTime.now())
            .build());
    store.checkpoint(newRun(Ids.newId(), AgentRunStatus.COMPLETED));

    var listed = store.findByStatus(AgentRunStatus.RUNNING);
    assertEquals(2, listed.size());
    assertEquals(newer, listed.get(0).runId());
    assertEquals(older, listed.get(1).runId());
  }

  @Test
  void findByStatusEmptyReturnsEmpty() {
    assertTrue(store.findByStatus(AgentRunStatus.FAILED).isEmpty());
  }

  @Test
  void rejectsNullCheckpoint() {
    assertThrows(NullPointerException.class, () -> store.checkpoint(null));
  }

  @Test
  void rejectsNullStatusFilter() {
    assertThrows(NullPointerException.class, () -> store.findByStatus(null));
  }

  @Test
  void preservesNullSessionAndUser() {
    var id = Ids.newId();
    var run =
        AgentRun.newBuilder()
            .withRunId(id)
            .withAgentId("a")
            .withStatus(AgentRunStatus.RUNNING)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build();
    store.checkpoint(run);
    var loaded = store.find(id).orElseThrow();
    assertNull(loaded.sessionId());
    assertNull(loaded.userId());
  }
}
