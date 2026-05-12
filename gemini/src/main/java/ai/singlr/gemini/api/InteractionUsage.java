/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>The live wire shape carries {@code total_tokens}, {@code total_input_tokens}, {@code
 * total_output_tokens} (plus optional per-modality breakdowns that we ignore). The breaking-change
 * doc reads {@code prompt_tokens}/{@code completion_tokens}, but the deployed v2 server still emits
 * the {@code total_*} family. We track the wire reality.
 *
 * @param totalTokens total tokens used
 * @param inputTokens prompt / input tokens (wire field {@code total_input_tokens})
 * @param outputTokens completion / output tokens (wire field {@code total_output_tokens})
 */
public record InteractionUsage(
    @JsonProperty("total_tokens") Integer totalTokens,
    @JsonProperty("total_input_tokens") Integer inputTokens,
    @JsonProperty("total_output_tokens") Integer outputTokens) {}
