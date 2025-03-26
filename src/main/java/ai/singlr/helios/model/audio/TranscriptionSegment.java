/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.audio;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TranscriptionSegment(
    Integer id,
    Integer seek,
    Float start,
    Float end,
    String text,
    List<Integer> tokens,
    Float temperature,
    @JsonProperty("avg_logprob")
    Float avgLogprob,
    @JsonProperty("compression_ratio")
    Float compressionRatio,
    @JsonProperty("no_speech_prob")
    Float noSpeechProb
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(TranscriptionSegment transcriptionSegment) {
    return new Builder(transcriptionSegment);
  }

  public static class Builder {
    private Integer id;
    private Integer seek;
    private Float start;
    private Float end;
    private String text;
    private List<Integer> tokens;
    private Float temperature;
    private Float avgLogprob;
    private Float compressionRatio;
    private Float noSpeechProb;

    private Builder() {}

    private Builder(TranscriptionSegment transcriptionSegment) {
      this.id = transcriptionSegment.id;
      this.seek = transcriptionSegment.seek;
      this.start = transcriptionSegment.start;
      this.end = transcriptionSegment.end;
      this.text = transcriptionSegment.text;
      this.tokens = transcriptionSegment.tokens;
      this.temperature = transcriptionSegment.temperature;
      this.avgLogprob = transcriptionSegment.avgLogprob;
      this.compressionRatio = transcriptionSegment.compressionRatio;
      this.noSpeechProb = transcriptionSegment.noSpeechProb;
    }

    public Builder withId(Integer id) {
      this.id = id;
      return this;
    }

    public Builder withSeek(Integer seek) {
      this.seek = seek;
      return this;
    }

    public Builder withStart(Float start) {
      this.start = start;
      return this;
    }

    public Builder withEnd(Float end) {
      this.end = end;
      return this;
    }

    public Builder withText(String text) {
      this.text = text;
      return this;
    }

    public Builder withTokens(List<Integer> tokens) {
      this.tokens = tokens;
      return this;
    }

    public Builder withTemperature(Float temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withAvgLogprob(Float avgLogprob) {
      this.avgLogprob = avgLogprob;
      return this;
    }

    public Builder withCompressionRatio(Float compressionRatio) {
      this.compressionRatio = compressionRatio;
      return this;
    }

    public Builder withNoSpeechProb(Float noSpeechProb) {
      this.noSpeechProb = noSpeechProb;
      return this;
    }

    public TranscriptionSegment build() {
      return new TranscriptionSegment(
          id,
          seek,
          start,
          end,
          text,
          tokens,
          temperature,
          avgLogprob,
          compressionRatio,
          noSpeechProb
      );
    }
  }
}
