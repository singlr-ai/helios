/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.model.chat.CompletionRequest;
import ai.singlr.helios.model.chat.ChatCompletion;
import ai.singlr.helios.result.Result;

public interface Chat {

  Result<ChatCompletion> createCompletion(CompletionRequest completionRequest);

  static Chat newChat(OpenAiClient client) {
    return new ChatImpl(client);
  }
}
