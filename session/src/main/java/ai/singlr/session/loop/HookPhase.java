/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

/**
 * Lifecycle phase at which hooks may fire. Every {@link HookRunner#fire(HookPhase) HookRunner.fire}
 * call names one phase.
 *
 * <p>The seven phases mirror the {@code hooks/} sealed-{@code Hook} subtypes the spec calls for in
 * Phase 2; the enum is declared in Phase 1 so the loop wiring can call into a no-op {@link
 * HookRunner} without yet depending on the {@code Hook} hierarchy.
 */
public enum HookPhase {
  /** Fired before the model is called for a turn. */
  PRE_MODEL_TURN,
  /** Fired after the model returns and the turn is fully observed. */
  POST_MODEL_TURN,
  /** Fired before a tool call is dispatched. */
  PRE_TOOL_USE,
  /** Fired after a tool call returns or fails. */
  POST_TOOL_USE,
  /** Fired when a user message is received from the steering queue. */
  ON_USER_MESSAGE,
  /** Fired before the session terminates; hooks may veto termination by injecting follow-up. */
  PRE_STOP,
  /** Fired for every {@link ai.singlr.session.QueryEvent QueryEvent} the loop emits. */
  ON_STREAM_EVENT
}
