/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Ids;
import ai.singlr.core.tool.ToolResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mediator between the agent loop and the {@link Durability} bundle. Encapsulates checkpointing,
 * tool-call journaling, and the "load existing or seed new" lifecycle of an {@link AgentRun}.
 *
 * <p>Every public method is a no-op when invoked with a state lacking a run id — callers can wire
 * the coordinator unconditionally and the right behavior happens whether or not a particular run is
 * durable.
 *
 * <p>Failures from the underlying {@link RunStore} or {@link ToolCallJournal} are caught and logged
 * at {@code WARNING}: durability is best-effort relative to the user's request, and a Postgres blip
 * during a checkpoint must never abort an otherwise-successful agent run.
 */
public class DurabilityCoordinator {

  private static final Logger LOG = Logger.getLogger(DurabilityCoordinator.class.getName());

  private final Durability durability;
  private final String agentName;

  public DurabilityCoordinator(Durability durability, String agentName) {
    this.durability = Objects.requireNonNull(durability, "durability");
    this.agentName = Objects.requireNonNull(agentName, "agentName");
  }

  public Durability durability() {
    return durability;
  }

  // --- Run lifecycle ---

  /** Write the initial {@link AgentRunStatus#RUNNING} checkpoint. Idempotent. */
  public void initialize(UUID runId, UUID sessionId, String userId, int iteration) {
    if (runId == null) {
      return;
    }
    var now = Ids.now();
    var run =
        AgentRun.newBuilder(loadOrSeed(runId, sessionId, userId, iteration, now))
            .withStatus(AgentRunStatus.RUNNING)
            .withLastCheckpointAt(now)
            .build();
    safeCheckpoint(run, "initialize");
  }

  /** Update the iteration counter and last-checkpoint time on an in-progress run. */
  public void checkpoint(UUID runId, UUID sessionId, String userId, int iteration) {
    if (runId == null) {
      return;
    }
    var now = Ids.now();
    var run =
        AgentRun.newBuilder(loadOrSeed(runId, sessionId, userId, iteration, now))
            .withStatus(AgentRunStatus.RUNNING)
            .withIteration(iteration)
            .withLastCheckpointAt(now)
            .build();
    safeCheckpoint(run, "checkpoint");
  }

  /** Mark the run {@link AgentRunStatus#COMPLETED} with an end timestamp. */
  public void complete(UUID runId, UUID sessionId, String userId, int iteration) {
    terminal(runId, sessionId, userId, iteration, AgentRunStatus.COMPLETED, null);
  }

  /** Mark the run {@link AgentRunStatus#FAILED} with the given error message. */
  public void fail(UUID runId, UUID sessionId, String userId, int iteration, String error) {
    terminal(runId, sessionId, userId, iteration, AgentRunStatus.FAILED, error);
  }

  private void terminal(
      UUID runId,
      UUID sessionId,
      String userId,
      int iteration,
      AgentRunStatus status,
      String error) {
    if (runId == null) {
      return;
    }
    var now = Ids.now();
    var run =
        AgentRun.newBuilder(loadOrSeed(runId, sessionId, userId, iteration, now))
            .withStatus(status)
            .withIteration(iteration)
            .withLastCheckpointAt(now)
            .withEndedAt(now)
            .withError(error)
            .build();
    safeCheckpoint(run, status.name().toLowerCase());
  }

  /**
   * Load the run from the store, or synthesize a fresh row seeded from the supplied state when no
   * row exists yet. Centralizes the "find existing or build new" pattern shared by every lifecycle
   * helper.
   */
  private AgentRun loadOrSeed(
      UUID runId, UUID sessionId, String userId, int iteration, OffsetDateTime now) {
    return durability
        .runStore()
        .find(runId)
        .orElseGet(
            () ->
                AgentRun.newBuilder()
                    .withRunId(runId)
                    .withSessionId(sessionId)
                    .withAgentId(agentName)
                    .withUserId(userId)
                    .withStatus(AgentRunStatus.RUNNING)
                    .withIteration(iteration)
                    .withStartedAt(now)
                    .withLastCheckpointAt(now)
                    .build());
  }

  // --- Tool-call journaling ---

  /**
   * Insert a {@link ToolCallStatus#STARTED} entry. Returns {@code true} when a journal row was
   * written so the caller knows whether to write the matching terminal status; failures and
   * non-durable runs both return {@code false}.
   */
  public boolean journalStart(
      UUID runId,
      int iteration,
      String toolCallId,
      String toolName,
      java.util.Map<String, Object> args) {
    if (runId == null) {
      return false;
    }
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(iteration)
            .withToolCallId(toolCallId)
            .withToolName(toolName)
            .withArgs(args)
            .withStatus(ToolCallStatus.STARTED)
            .withStartedAt(Ids.now())
            .build();
    try {
      durability.toolCallJournal().start(record);
      return true;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Tool-call journal start failed; continuing without journal", e);
      return false;
    }
  }

  /**
   * Write the terminal journal status for a tool call. Failures are logged but never propagated:
   * the tool already executed and the user is owed its result. An orphaned {@code STARTED} entry
   * will be reconciled on the next resume.
   */
  public void journalTerminal(UUID runId, String toolCallId, ToolResult toolResult) {
    try {
      if (toolResult.success()) {
        durability.toolCallJournal().complete(runId, toolCallId, toolResult.output());
      } else {
        durability.toolCallJournal().fail(runId, toolCallId, toolResult.output());
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Tool-call journal terminal write failed; tool result preserved, journal entry"
              + " left STARTED",
          e);
    }
  }

  /** Write a terminal {@code FAILED} entry — used by the {@code throws} path of tool execution. */
  public void journalTerminalFailure(UUID runId, String toolCallId, String error) {
    try {
      durability.toolCallJournal().fail(runId, toolCallId, error);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Tool-call journal failure-write failed; original tool exception will still propagate",
          e);
    }
  }

  // --- Resume primitives ---

  public Optional<AgentRun> findRun(UUID runId) {
    return durability.runStore().find(runId);
  }

  public List<ToolCallRecord> inflightFor(UUID runId) {
    return durability.toolCallJournal().inflight(runId);
  }

  /** Mark the run {@link AgentRunStatus#SUSPENDED} — used when refusing to resume. */
  public void markSuspended(AgentRun run) {
    safeCheckpoint(
        AgentRun.newBuilder(run).withStatus(AgentRunStatus.SUSPENDED).build(), "markSuspended");
  }

  /**
   * Best-effort transition of an in-flight journal entry to {@code FAILED} with a synthetic reason.
   * Used during resume preparation to clear the way for replay.
   */
  public void markInflightFailed(UUID runId, String toolCallId, String reason) {
    try {
      durability.toolCallJournal().fail(runId, toolCallId, reason);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "Failed to mark inflight entry " + toolCallId + " failed");
      LOG.log(Level.FINE, "mark-failed exception", e);
    }
  }

  private void safeCheckpoint(AgentRun run, String where) {
    try {
      durability.runStore().checkpoint(run);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "RunStore.checkpoint failed in " + where);
      LOG.log(Level.FINE, "checkpoint exception", e);
    }
  }
}
