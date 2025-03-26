/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.AudioFormat;
import ai.singlr.helios.literal.VoicePersona;

public record ChatCompletionAudio(VoicePersona voice, AudioFormat format) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionAudio audio) {
    return new Builder(audio);
  }

  public static class Builder {
    private VoicePersona voice;
    private AudioFormat format;

    private Builder() {}

    private Builder(ChatCompletionAudio audio) {
      this.voice = audio.voice;
      this.format = audio.format;
    }

    public Builder withVoice(VoicePersona voice) {
      this.voice = voice;
      return this;
    }

    public Builder withFormat(AudioFormat format) {
      this.format = format;
      return this;
    }

    public ChatCompletionAudio build() {
      return new ChatCompletionAudio(voice, format);
    }
  }
}
