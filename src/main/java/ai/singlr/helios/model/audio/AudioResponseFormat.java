/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.audio;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The format of the transcription output.
 */
public enum AudioResponseFormat {
  
  JSON("json"),
  
  TEXT("text"),
  
  SRT("srt"),
  
  VERBOSE_JSON("verbose_json"),
  
  VTT("vtt");

  private final String value;

  AudioResponseFormat(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }
}

