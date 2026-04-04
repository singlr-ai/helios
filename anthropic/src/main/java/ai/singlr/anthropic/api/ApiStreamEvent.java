/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE event envelope from Claude Messages API streaming.
 *
 * <p>The {@code type} field discriminates between event kinds: message_start, content_block_start,
 * content_block_delta, content_block_stop, message_delta, message_stop.
 *
 * @param type event type discriminator
 * @param message full message object (for message_start)
 * @param index content block index (for content_block_start/delta/stop)
 * @param contentBlock the content block (for content_block_start)
 * @param delta incremental content (for content_block_delta and message_delta)
 * @param usage token usage (for message_delta)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiStreamEvent(
    String type,
    MessagesResponse message,
    Integer index,
    @JsonProperty("content_block") ContentBlock contentBlock,
    ContentDelta delta,
    ApiUsage usage) {

  public boolean hasTypeMessageStart() {
    return "message_start".equals(type);
  }

  public boolean hasTypeContentBlockStart() {
    return "content_block_start".equals(type);
  }

  public boolean hasTypeContentBlockDelta() {
    return "content_block_delta".equals(type);
  }

  public boolean hasTypeContentBlockStop() {
    return "content_block_stop".equals(type);
  }

  public boolean hasTypeMessageDelta() {
    return "message_delta".equals(type);
  }

  public boolean hasTypeMessageStop() {
    return "message_stop".equals(type);
  }

  public boolean hasTypeError() {
    return "error".equals(type);
  }
}
