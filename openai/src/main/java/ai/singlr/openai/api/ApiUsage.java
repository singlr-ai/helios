/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the OpenAI Responses API.
 *
 * @param inputTokens number of input tokens consumed
 * @param outputTokens number of output tokens generated
 * @param totalTokens total tokens (input + output)
 */
public record ApiUsage(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("total_tokens") Integer totalTokens) {}
