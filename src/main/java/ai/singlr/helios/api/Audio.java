/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.model.audio.AudioRequest;
import ai.singlr.helios.model.audio.Transcription;
import ai.singlr.helios.result.Result;

public interface Audio {

  Result<Transcription> createTranscription(AudioRequest audioRequest);

  static Audio newAudio(OpenAiClient client) {
    return new AudioImpl(client);
  }
}
