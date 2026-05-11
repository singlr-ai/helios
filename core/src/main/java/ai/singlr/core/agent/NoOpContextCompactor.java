/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.memory.MemoryListener;
import ai.singlr.core.model.Message;
import java.util.List;
import java.util.UUID;

/**
 * A {@link ContextCompactor} that never rewrites the message list. Use when running short-lived
 * agents, evals, or tests where compaction is undesirable or counterproductive.
 */
public final class NoOpContextCompactor implements ContextCompactor {

  /** Singleton instance — stateless. */
  public static final NoOpContextCompactor INSTANCE = new NoOpContextCompactor();

  private NoOpContextCompactor() {}

  @Override
  public List<Message> compactIfNeeded(
      List<Message> messages, String userId, UUID sessionId, List<MemoryListener> listeners) {
    return messages;
  }
}
