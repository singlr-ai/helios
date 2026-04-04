/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Tool definition for the Claude Messages API.
 *
 * @param name the tool name
 * @param description description of what the tool does
 * @param inputSchema JSON Schema for tool parameters
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String name,
    String description,
    @JsonProperty("input_schema") Map<String, Object> inputSchema) {}
