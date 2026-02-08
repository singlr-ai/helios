/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import java.util.List;

/**
 * Response from the Gemini Interactions API.
 *
 * @param id unique interaction identifier
 * @param model the model used
 * @param status interaction status ("in_progress", "requires_action", "completed", "failed",
 *     "cancelled")
 * @param outputs the generated outputs
 * @param usage token usage statistics
 */
public record InteractionResponse(
    String id, String model, String status, List<OutputItem> outputs, InteractionUsage usage) {

  public boolean isCompleted() {
    return "completed".equals(status);
  }

  public boolean isFailed() {
    return "failed".equals(status);
  }

  public boolean requiresAction() {
    return "requires_action".equals(status);
  }
}
