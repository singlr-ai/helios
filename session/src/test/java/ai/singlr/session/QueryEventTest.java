/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QueryEventTest {

  private static final String SID = "sess-1";
  private static final long TURN = 3;
  private static final Instant TS = Instant.parse("2026-05-14T20:00:00Z");

  // ── AssistantText ──────────────────────────────────────────────────────────

  @Test
  void assistantTextConstructsAndExposesFields() {
    var e = new QueryEvent.AssistantText(SID, TURN, TS, "hello");
    assertEquals(SID, e.sessionId());
    assertEquals(TURN, e.turnIndex());
    assertEquals(TS, e.timestamp());
    assertEquals("hello", e.text());
  }

  @Test
  void assistantTextAllowsEmptyText() {
    var e = new QueryEvent.AssistantText(SID, TURN, TS, "");
    assertEquals("", e.text());
  }

  @Test
  void assistantTextRejectsNullText() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.AssistantText(SID, TURN, TS, null));
    assertEquals("text must not be null", ex.getMessage());
  }

  // ── Common-field validation (exercised through AssistantText) ─────────────

  @Test
  void nullSessionIdRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.AssistantText(null, TURN, TS, "x"));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void blankSessionIdRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.AssistantText("   ", TURN, TS, "x"));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void negativeTurnIndexRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new QueryEvent.AssistantText(SID, -1, TS, "x"));
    assertEquals("turnIndex must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void zeroTurnIndexAllowed() {
    var e = new QueryEvent.AssistantText(SID, 0, TS, "x");
    assertEquals(0, e.turnIndex());
  }

  @Test
  void nullTimestampRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.AssistantText(SID, TURN, null, "x"));
    assertEquals("timestamp must not be null", ex.getMessage());
  }

  // ── AssistantThinking ──────────────────────────────────────────────────────

  @Test
  void assistantThinkingConstructsAndExposesFields() {
    var e = new QueryEvent.AssistantThinking(SID, TURN, TS, "ponder", "sig-abc");
    assertEquals("ponder", e.text());
    assertEquals("sig-abc", e.signature());
  }

  @Test
  void assistantThinkingRejectsNullText() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.AssistantThinking(SID, TURN, TS, null, "sig"));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void assistantThinkingRejectsNullSignature() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.AssistantThinking(SID, TURN, TS, "t", null));
    assertEquals("signature must not be null", ex.getMessage());
  }

  // ── UserMessageReceived ───────────────────────────────────────────────────

  @Test
  void userMessageReceivedConstructsAndExposesFields() {
    var msg = UserMessage.text("hi");
    var e = new QueryEvent.UserMessageReceived(SID, TURN, TS, msg);
    assertSame(msg, e.message());
  }

  @Test
  void userMessageReceivedRejectsNullMessage() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.UserMessageReceived(SID, TURN, TS, null));
    assertEquals("message must not be null", ex.getMessage());
  }

  // ── ContextWarning ────────────────────────────────────────────────────────

  @Test
  void contextWarningConstructsAndExposesFields() {
    var e = new QueryEvent.ContextWarning(SID, TURN, TS, 0.85);
    assertEquals(0.85, e.usagePct());
  }

  @Test
  void contextWarningAllowsZeroPct() {
    var e = new QueryEvent.ContextWarning(SID, TURN, TS, 0.0);
    assertEquals(0.0, e.usagePct());
  }

  @Test
  void contextWarningRejectsNegativePct() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ContextWarning(SID, TURN, TS, -0.01));
    assertTrue(ex.getMessage().startsWith("usagePct must be non-negative and finite"));
  }

  @Test
  void contextWarningRejectsNanPct() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryEvent.ContextWarning(SID, TURN, TS, Double.NaN));
  }

  // ── ContextEdited ─────────────────────────────────────────────────────────

  @Test
  void contextEditedConstructsAndExposesFields() {
    var e = new QueryEvent.ContextEdited(SID, TURN, TS, 4, 18_000L, 12_000L);
    assertEquals(4, e.removedBlocks());
    assertEquals(18_000L, e.tokensBefore());
    assertEquals(12_000L, e.tokensAfter());
  }

  @Test
  void contextEditedRejectsNegativeRemovedBlocks() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ContextEdited(SID, TURN, TS, -1, 0L, 0L));
    assertEquals("removedBlocks must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void contextEditedRejectsNegativeTokensBefore() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ContextEdited(SID, TURN, TS, 0, -1L, 0L));
    assertEquals("tokensBefore must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void contextEditedRejectsNegativeTokensAfter() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ContextEdited(SID, TURN, TS, 0, 0L, -1L));
    assertEquals("tokensAfter must be non-negative, got -1", ex.getMessage());
  }

  // ── TurnEnded ─────────────────────────────────────────────────────────────

  @Test
  void turnEndedConstructsAndExposesFields() {
    var e = new QueryEvent.TurnEnded(SID, TURN, TS, StopReason.END_TURN);
    assertSame(StopReason.END_TURN, e.reason());
  }

  @Test
  void turnEndedRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.TurnEnded(SID, TURN, TS, null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  // ── LoopEnded ─────────────────────────────────────────────────────────────

  @Test
  void loopEndedConstructsAndExposesFields() {
    var result =
        new ResultMessage.Success(
            SID, "done", Usage.of(10, 5), CostEstimate.zero(), Duration.ofSeconds(1));
    var e = new QueryEvent.LoopEnded(SID, TURN, TS, result);
    assertSame(result, e.result());
  }

  @Test
  void loopEndedRejectsNullResult() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.LoopEnded(SID, TURN, TS, null));
    assertEquals("result must not be null", ex.getMessage());
  }

  // ── Error ─────────────────────────────────────────────────────────────────

  @Test
  void errorConstructsAndExposesFields() {
    var err = SerializedError.of("kind", "msg");
    var e = new QueryEvent.Error(SID, TURN, TS, err);
    assertSame(err, e.error());
  }

  @Test
  void errorRejectsNullSerializedError() {
    var ex =
        assertThrows(NullPointerException.class, () -> new QueryEvent.Error(SID, TURN, TS, null));
    assertEquals("error must not be null", ex.getMessage());
  }

  // ── Sealed-hierarchy contract ─────────────────────────────────────────────

  @Test
  void sealedInterfaceHasFourteenPermittedSubclasses() {
    var permits = QueryEvent.class.getPermittedSubclasses();
    assertEquals(14, permits.length);
  }

  @Test
  void switchPatternHandlesEverySubtype() {
    var result =
        new ResultMessage.Success(SID, "ok", Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO);
    List<QueryEvent> all =
        List.of(
            new QueryEvent.AssistantText(SID, TURN, TS, "t"),
            new QueryEvent.AssistantThinking(SID, TURN, TS, "th", "sig"),
            new QueryEvent.UserMessageReceived(SID, TURN, TS, UserMessage.text("hi")),
            new QueryEvent.ContextWarning(SID, TURN, TS, 0.5),
            new QueryEvent.ContextEdited(SID, TURN, TS, 1, 100L, 50L),
            new QueryEvent.TurnEnded(SID, TURN, TS, StopReason.END_TURN),
            new QueryEvent.LoopEnded(SID, TURN, TS, result),
            new QueryEvent.Error(SID, TURN, TS, SerializedError.of("k", "m")));
    for (var e : all) {
      var tag =
          switch (e) {
            case QueryEvent.AssistantText t -> "text";
            case QueryEvent.AssistantThinking t -> "thinking";
            case QueryEvent.UserMessageReceived u -> "user";
            case QueryEvent.ContextWarning w -> "ctx-warn";
            case QueryEvent.ContextEdited c -> "ctx-edit";
            case QueryEvent.ToolUse u -> "tool-use";
            case QueryEvent.ToolResult r -> "tool-result";
            case QueryEvent.ToolBlocked b -> "tool-blocked";
            case QueryEvent.ToolMutated m -> "tool-mutated";
            case QueryEvent.HookFired h -> "hook-fired";
            case QueryEvent.QuestionAsked q -> "question-asked";
            case QueryEvent.TurnEnded te -> "turn-end";
            case QueryEvent.LoopEnded le -> "loop-end";
            case QueryEvent.Error err -> "error";
          };
      assertTrue(tag != null && !tag.isEmpty());
    }
  }
}
