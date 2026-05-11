/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Events fired by the agent loop at well-defined points so external memory backends, behavior
 * extractors, and observability systems can react. Sealed so {@code switch} statements can be
 * exhaustive over the event set.
 *
 * <p>Fire points and where they originate in {@code Agent}:
 *
 * <ul>
 *   <li>{@link BeforeApiCall} — fired in {@code Agent.step} immediately before {@code model.chat},
 *       carrying the messages that will be sent. Use for prefetching context that should land in
 *       the prompt before the API call.
 *   <li>{@link AfterTurn} — fired in {@code Agent.step} after the model response and any tool calls
 *       have been processed. The canonical hook for "watch the user/assistant interaction and react
 *       to it" — behavior extractors, consolidation triggers, external observability.
 *   <li>{@link BeforeCompaction} — fired by {@link ai.singlr.core.agent.ContextCompactor} right
 *       before the algorithm runs. Last chance to extract durable signals before middle turns get
 *       summarized.
 *   <li>{@link SessionEnd} — fired in {@code Agent.runLoop} / {@code Agent.streamLoop} after the
 *       run reaches a terminal state. Use to flush pending memory writes or kick off offline
 *       consolidation.
 *   <li>{@link MemoryWrite} — fired by memory implementations on every mutation (put / update /
 *       replace / remove / archive). Use to mirror writes to an external store or audit them.
 * </ul>
 *
 * <p>All event handlers run synchronously on the calling thread. Implementations MUST NOT block —
 * push heavy work (LLM calls, network I/O, vector indexing) onto a daemon thread or virtual thread
 * inside the handler. Exceptions thrown by handlers are caught by the agent loop and logged at
 * {@code WARNING}; they do not abort the run.
 */
public sealed interface MemoryEvent
    permits MemoryEvent.BeforeApiCall,
        MemoryEvent.AfterTurn,
        MemoryEvent.BeforeCompaction,
        MemoryEvent.SessionEnd,
        MemoryEvent.MemoryWrite {

  /**
   * Fired immediately before each {@code model.chat} call in the agent loop. The {@code messages}
   * list reflects what will be sent (post-compaction, post-system-prompt-refresh).
   */
  record BeforeApiCall(String userId, UUID sessionId, List<Message> messages, int iteration)
      implements MemoryEvent {}

  /**
   * Fired after a model response has been processed for the current iteration. {@code
   * assistantMessage} is the assistant's response. {@code toolMessages} are the tool result
   * messages produced this turn (empty when the assistant turn had no tool calls). {@code
   * userMessage} is the most recent user message in the message list at the time the turn started —
   * may be a synthetic injected message (e.g., a guardrail-injected USER turn) or the original
   * input.
   */
  record AfterTurn(
      String userId,
      UUID sessionId,
      Message userMessage,
      Message assistantMessage,
      List<Message> toolMessages,
      int iteration)
      implements MemoryEvent {}

  /**
   * Fired by {@link ai.singlr.core.agent.ContextCompactor} right before it rewrites the message
   * list. Listeners can use this to scan the full pre-compaction history for durable signals (e.g.,
   * critical decisions, file paths, error patterns) and emit memory writes before the middle turns
   * collapse into a summary.
   */
  record BeforeCompaction(String userId, UUID sessionId, List<Message> messages)
      implements MemoryEvent {}

  /**
   * Fired when the agent loop reaches a terminal state — successful completion, terminal failure,
   * or max-iteration exhaustion. {@code finalMessages} is the complete message list at termination.
   */
  record SessionEnd(
      String userId, UUID sessionId, List<Message> finalMessages, Termination termination)
      implements MemoryEvent {

    /** Why the session ended — useful for consolidators that treat completion differently. */
    public enum Termination {
      /** Model produced a final response without tool calls and any guardrails were satisfied. */
      COMPLETED,
      /** Loop hit {@code maxIterations} before completing. */
      MAX_ITERATIONS,
      /** Step returned a failure (exception, parse error, etc). */
      FAILED
    }
  }

  /**
   * Fired by memory implementations on every mutation. Listeners can mirror these writes to an
   * external store, audit them, or trigger downstream effects.
   *
   * <p>For {@link Action#ARCHIVE} the {@code blockName} field is {@code null} and {@code data}
   * contains a single {@code "content"} entry plus any metadata; for block mutations {@code
   * blockName} is set and {@code data} is the block's full data map (post-write).
   */
  record MemoryWrite(Action action, String blockName, Map<String, Object> data)
      implements MemoryEvent {

    public enum Action {
      PUT_BLOCK,
      UPDATE_BLOCK,
      REPLACE_BLOCK,
      REMOVE_BLOCK,
      ARCHIVE
    }
  }
}
