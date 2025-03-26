/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.ErrorCode;
import ai.singlr.helios.literal.Constants;
import ai.singlr.helios.literal.StatusCodes;
import ai.singlr.helios.model.audio.AudioRequest;
import ai.singlr.helios.model.audio.Transcription;
import ai.singlr.helios.result.Result;
import ai.singlr.helios.result.ResultBuilder;
import ai.singlr.helios.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioImpl implements Audio {

  private static final Logger LOGGER = Logger.getLogger(AudioImpl.class.getName());

  private final OpenAiClient client;

  public AudioImpl(OpenAiClient client) {
    this.client = client;
  }

  @Override
  public Result<Transcription> createTranscription(AudioRequest audioRequest) {
    var resultBuilder = new ResultBuilder<Transcription>();

    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(client.fullUri("/audio/transcriptions"));
      for (var entry : client.defaultHeaders.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(Constants.CONTENT_TYPE_KEY)) {
          continue;
        }
        builder.header(entry.getKey(), entry.getValue());
      }

      String boundary = "------------------------" + System.currentTimeMillis();
      var request = client.buildMultiPartFormData(builder, boundary, audioRequest.toMap()).build();
      var response = client.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != StatusCodes.OK) {
        var msg = response.body();
        LOGGER.warning("Failed to create transcription: " + response.statusCode() + ". " + msg);
        resultBuilder
            .withAppMessage(msg)
            .withErrorCode(ErrorCode.lookup(response.statusCode()));

      } else {
        resultBuilder.withSuccess(
            JsonUtils.mapper().readValue(response.body(), Transcription.class),
            StatusCodes.OK
        );
      }

    } catch (NullPointerException | JsonProcessingException ex) {
      LOGGER.log(Level.WARNING, "Failed to create transcription. Invalid JSON", ex);
      resultBuilder
          .withAppMessage("Failed to create transcription")
          .withErrorCode(ErrorCode.INVALID)
          .withCause(ex);

    } catch (Exception ex) {
      LOGGER.log(Level.SEVERE, "Failed to create transcription", ex);
      resultBuilder
          .withAppMessage("Failed to create transcription")
          .withErrorCode(ErrorCode.INTERNAL)
          .withCause(ex);
    }

    return resultBuilder.build();
  }
}
