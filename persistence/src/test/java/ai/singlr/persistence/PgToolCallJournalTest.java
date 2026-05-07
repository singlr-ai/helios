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
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgToolCallJournalTest {

  private PgToolCallJournal journal;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
    journal = new PgToolCallJournal(PgTestSupport.pgConfig());
  }

  private static ToolCallRecord started(UUID runId, String callId, String toolName) {
    return ToolCallRecord.newBuilder()
        .withRunId(runId)
        .withIteration(0)
        .withToolCallId(callId)
        .withToolName(toolName)
        .withArgs(Map.of("k", "v"))
        .withStartedAt(Ids.now())
        .build();
  }

  @Test
  void startThenComplete() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "c1", "sunny");

    var all = journal.all(runId);
    assertEquals(1, all.size());
    assertEquals(ToolCallStatus.SUCCEEDED, all.get(0).status());
    assertEquals("sunny", all.get(0).output());
    assertNotNull(all.get(0).endedAt());
    assertNull(all.get(0).error());
    assertEquals(Map.of("k", "v"), all.get(0).args());
  }

  @Test
  void startThenFail() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.fail(runId, "c1", "boom");

    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("boom", rec.error());
    assertNull(rec.output());
  }

  @Test
  void inflightExcludesTerminal() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "send"));
    journal.start(started(runId, "c2", "send"));
    journal.start(started(runId, "c3", "send"));
    journal.complete(runId, "c1", "ok");
    journal.fail(runId, "c2", "boom");

    var inflight = journal.inflight(runId);
    assertEquals(1, inflight.size());
    assertEquals("c3", inflight.get(0).toolCallId());
  }

  @Test
  void completeNoMatchIsNoOp() {
    var runId = Ids.newId();
    journal.complete(runId, "missing", "irrelevant");
    assertTrue(journal.all(runId).isEmpty());
  }

  @Test
  void completeAfterTerminalIsNoOp() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "send"));
    journal.fail(runId, "c1", "first");
    journal.complete(runId, "c1", "second");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("first", rec.error());
  }

  @Test
  void inflightUnknownReturnsEmpty() {
    assertTrue(journal.inflight(Ids.newId()).isEmpty());
    assertTrue(journal.inflight(null).isEmpty());
  }

  @Test
  void allUnknownReturnsEmpty() {
    assertTrue(journal.all(Ids.newId()).isEmpty());
    assertTrue(journal.all(null).isEmpty());
  }

  @Test
  void rejectsNullStart() {
    assertThrows(NullPointerException.class, () -> journal.start(null));
  }

  @Test
  void rejectsNullCompleteRunId() {
    assertThrows(NullPointerException.class, () -> journal.complete(null, "c1", "ok"));
  }

  @Test
  void rejectsNullCompleteCallId() {
    assertThrows(NullPointerException.class, () -> journal.complete(Ids.newId(), null, "ok"));
  }

  @Test
  void rejectsNullFailRunId() {
    assertThrows(NullPointerException.class, () -> journal.fail(null, "c1", "boom"));
  }

  @Test
  void rejectsNullFailCallId() {
    assertThrows(NullPointerException.class, () -> journal.fail(Ids.newId(), null, "boom"));
  }

  @Test
  void duplicateInsertOnSameKeyThrows() {
    var runId = Ids.newId();
    var record = started(runId, "c1", "send");
    journal.start(record);
    assertThrows(PgException.class, () -> journal.start(record));
  }

  @Test
  void argsNullPersistsAsNull() {
    var runId = Ids.newId();
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("c1")
            .withToolName("send")
            .withArgs(null)
            .withStartedAt(Ids.now())
            .build();
    journal.start(record);
    assertNull(journal.all(runId).get(0).args());
  }
}
