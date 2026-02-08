/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the Interactions API.
 *
 * @param totalTokens total tokens used
 * @param inputTokens tokens in the input
 * @param outputTokens tokens in the output
 */
public record InteractionUsage(
    @JsonProperty("total_tokens") Integer totalTokens,
    @JsonProperty("total_input_tokens") Integer inputTokens,
    @JsonProperty("total_output_tokens") Integer outputTokens) {}
