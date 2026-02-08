/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A streaming event from the Interactions API SSE stream.
 *
 * @param eventType the event type ("content.delta", "interaction.complete", etc.)
 * @param delta incremental content (for content.delta events)
 * @param interaction complete interaction (for interaction.complete events)
 */
public record StreamingEvent(
    @JsonProperty("event_type") String eventType,
    OutputItem delta,
    InteractionResponse interaction) {

  public boolean isContentDelta() {
    return "content.delta".equals(eventType);
  }

  public boolean isComplete() {
    return "interaction.complete".equals(eventType);
  }
}
