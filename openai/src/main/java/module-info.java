/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - OpenAI GPT Provider Module.
 *
 * <p>Implements the ModelProvider SPI for OpenAI's GPT API using the Responses API for multi-turn
 * conversations and SSE streaming support.
 */
module ai.singlr.openai {
  requires ai.singlr.core;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.fasterxml.jackson.annotation;

  exports ai.singlr.openai;

  opens ai.singlr.openai.api to
      tools.jackson.databind;

  provides ai.singlr.core.model.ModelProvider with
      ai.singlr.openai.OpenAIProvider;
}
