/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

public enum AudioFormat {
  WAV("wav"),
  MP3("mp3"),
  FLAC("flac"),
  OPUS("opus"),
  PCM16("pcm16");

  private final String value;

  AudioFormat (String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }
}
