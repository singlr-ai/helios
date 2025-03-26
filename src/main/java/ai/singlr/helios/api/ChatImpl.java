/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.ErrorCode;
import ai.singlr.helios.literal.StatusCodes;
import ai.singlr.helios.model.chat.ChatCompletion;
import ai.singlr.helios.model.chat.CompletionRequest;
import ai.singlr.helios.result.Result;
import ai.singlr.helios.result.ResultBuilder;
import ai.singlr.helios.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatImpl implements Chat {

  private static final Logger LOGGER = Logger.getLogger(ChatImpl.class.getName());

  private final OpenAiClient client;

  public ChatImpl(OpenAiClient client) {
    this.client = client;
  }

  @Override
  public Result<ChatCompletion> createCompletion(CompletionRequest completionRequest) {
    var resultBuilder = new ResultBuilder<ChatCompletion>();

    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(client.fullUri("/chat/completions"));
      for (var entry : client.defaultHeaders.entrySet()) {
        builder.header(entry.getKey(), entry.getValue());
      }

      var requestBody = JsonUtils.mapper().writeValueAsString(completionRequest);
      var request = builder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .build();

      var response = client.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != StatusCodes.OK) {
        var msg = response.body();
        LOGGER.warning("Failed to create chat completion: " + response.statusCode() + ". " + msg);
        resultBuilder
            .withAppMessage(msg)
            .withErrorCode(ErrorCode.lookup(response.statusCode()));

      } else {
        resultBuilder.withSuccess(
            JsonUtils.mapper().readValue(response.body(), ChatCompletion.class),
            StatusCodes.OK
        );
      }

    } catch (NullPointerException | JsonProcessingException ex) {
      LOGGER.log(Level.WARNING, "Failed to create chat completion. Invalid JSON", ex);
      resultBuilder
          .withAppMessage("Failed to create chat completion")
          .withErrorCode(ErrorCode.INVALID)
          .withCause(ex);

    } catch (Exception ex) {
      LOGGER.log(Level.SEVERE, "Failed to create chat completion", ex);
      resultBuilder
          .withAppMessage("Failed to create chat completion")
          .withErrorCode(ErrorCode.INTERNAL)
          .withCause(ex);
    }

    return resultBuilder.build();
  }
}
