/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.audio;

import ai.singlr.helios.literal.ModelName;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record AudioRequest(
    File file,
    ModelName model,
    String language,
    String prompt,
    AudioResponseFormat responseFormat,
    Float temperature,
    List<String> timestampGranularities
) {

  public Map<String, Object> toMap() {
    var map = new HashMap<String, Object>();
    if (file != null) map.put("file", file.toPath());
    if (model != null) map.put("model", model.toString());
    if (language != null) map.put("language", language);
    if (prompt != null) map.put("prompt", prompt);
    if (responseFormat != null) map.put("response_format", responseFormat.toString());
    if (temperature != null) map.put("temperature", temperature);
    if (timestampGranularities != null && !timestampGranularities.isEmpty()) {
      map.put("timestamp_granularities[]", timestampGranularities.getFirst());
    }

    return map;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(AudioRequest audioRequest) {
    return new Builder(audioRequest);
  }

  public static class Builder {
    private File file;
    private ModelName model = ModelName.WHISPER;
    private String language;
    private String prompt;
    private AudioResponseFormat responseFormat = AudioResponseFormat.JSON;
    private Float temperature;
    private List<String> timestampGranularities;

    private Builder() {}

    private Builder(AudioRequest audioRequest) {
      this.file = audioRequest.file;
      this.model = audioRequest.model;
      this.language = audioRequest.language;
      this.prompt = audioRequest.prompt;
      this.responseFormat = audioRequest.responseFormat;
      this.temperature = audioRequest.temperature;
      this.timestampGranularities = audioRequest.timestampGranularities;
    }

    public Builder withFile(File file) {
      this.file = file;
      return this;
    }

    public Builder withLanguage(String language) {
      this.language = language;
      return this;
    }

    public Builder withPrompt(String prompt) {
      this.prompt = prompt;
      return this;
    }

    public Builder withResponseFormat(AudioResponseFormat responseFormat) {
      if (responseFormat == null) throw new IllegalArgumentException("responseFormat cannot be null");
      this.responseFormat = responseFormat;
      return this;
    }

    public Builder withTemperature(Float temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTimestampGranularities(List<String> timestampGranularities) {
      if (timestampGranularities == null) throw new IllegalArgumentException("timestampGranularities cannot be null");
      this.timestampGranularities = timestampGranularities;
      return this;
    }

    public AudioRequest build() {
      return new AudioRequest(
          file,
          model,
          language,
          prompt,
          responseFormat,
          temperature,
          timestampGranularities
      );
    }
  }
}
