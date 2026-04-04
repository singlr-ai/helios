/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - Anthropic Claude Provider Module.
 *
 * <p>Implements the ModelProvider SPI for Anthropic's Claude API using the Messages API for
 * multi-turn conversations and SSE streaming support.
 */
module ai.singlr.anthropic {
  requires ai.singlr.core;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.fasterxml.jackson.annotation;

  exports ai.singlr.anthropic;

  opens ai.singlr.anthropic.api to
      tools.jackson.databind;

  provides ai.singlr.core.model.ModelProvider with
      ai.singlr.anthropic.AnthropicProvider;
}
