/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Role;
import java.util.List;

/**
 * Simple token estimator using chars/4 heuristic. Zero external dependencies — same approach as
 * Claude Code and similar frameworks.
 */
final class TokenEstimator {

  private TokenEstimator() {}

  /** Estimate total tokens for a list of messages. */
  static int estimate(List<Message> messages) {
    var total = 0;
    for (var msg : messages) {
      total += estimate(msg.content());
      if (msg.role() == Role.ASSISTANT && msg.hasToolCalls()) {
        for (var tc : msg.toolCalls()) {
          total += estimate(tc.name());
          total += estimate(tc.arguments().toString());
        }
      }
    }
    return total;
  }

  /** Estimate tokens for a single string. */
  static int estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return text.length() / 4;
  }
}
