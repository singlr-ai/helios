/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRunStoreTest {

  private final InMemoryRunStore store = new InMemoryRunStore();

  private static AgentRun runAt(UUID id, AgentRunStatus status, OffsetDateTime checkpoint) {
    return AgentRun.newBuilder()
        .withRunId(id)
        .withStatus(status)
        .withStartedAt(checkpoint)
        .withLastCheckpointAt(checkpoint)
        .build();
  }

  @Test
  void checkpointThenFind() {
    var id = Ids.newId();
    var run = runAt(id, AgentRunStatus.RUNNING, Ids.now());
    store.checkpoint(run);
    assertEquals(run, store.find(id).orElseThrow());
  }

  @Test
  void checkpointUpdatesInPlace() {
    var id = Ids.newId();
    store.checkpoint(runAt(id, AgentRunStatus.RUNNING, Ids.now()));
    store.checkpoint(runAt(id, AgentRunStatus.COMPLETED, Ids.now()));
    assertEquals(AgentRunStatus.COMPLETED, store.find(id).orElseThrow().status());
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
  void findByStatusReturnsOnlyMatching() {
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.RUNNING, Ids.now()));
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.COMPLETED, Ids.now()));
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.RUNNING, Ids.now()));
    assertEquals(2, store.findByStatus(AgentRunStatus.RUNNING).size());
    assertEquals(1, store.findByStatus(AgentRunStatus.COMPLETED).size());
    assertEquals(0, store.findByStatus(AgentRunStatus.FAILED).size());
  }

  @Test
  void findByStatusOrdersNewestFirst() {
    var older = OffsetDateTime.now().minusMinutes(5);
    var newer = OffsetDateTime.now();
    var olderId = Ids.newId();
    var newerId = Ids.newId();
    store.checkpoint(runAt(olderId, AgentRunStatus.RUNNING, older));
    store.checkpoint(runAt(newerId, AgentRunStatus.RUNNING, newer));
    var listed = store.findByStatus(AgentRunStatus.RUNNING);
    assertEquals(newerId, listed.get(0).runId());
    assertEquals(olderId, listed.get(1).runId());
  }

  @Test
  void rejectsNullCheckpoint() {
    assertThrows(NullPointerException.class, () -> store.checkpoint(null));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(NullPointerException.class, () -> store.findByStatus(null));
  }
}
