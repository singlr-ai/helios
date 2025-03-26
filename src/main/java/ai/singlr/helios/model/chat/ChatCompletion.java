/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.Literal;
import ai.singlr.helios.literal.ServiceTier;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
  * Represents a chat completion response returned by model, based on the provided input.
 **/

public record ChatCompletion(
    String id,
    List<Choice> choices,
    Integer created,
    String model,
    @JsonProperty("service_tier")
    ServiceTier serviceTier,
    @JsonProperty("system_fingerprint")
    String systemFingerprint,
    Literal object,
    CompletionUsage usage
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletion chatCompletion) {
    return new Builder(chatCompletion);
  }

  public static class Builder {
    private String id;
    private List<Choice> choices = List.of();
    private Integer created;
    private String model;
    private ServiceTier serviceTier;
    private String systemFingerprint;
    private Literal _object;
    private CompletionUsage usage;

    private Builder() {}

    private Builder(ChatCompletion chatCompletion) {
      this.id = chatCompletion.id;
      this.choices = chatCompletion.choices;
      this.created = chatCompletion.created;
      this.model = chatCompletion.model;
      this.serviceTier = chatCompletion.serviceTier;
      this.systemFingerprint = chatCompletion.systemFingerprint;
      this._object = chatCompletion.object;
      this.usage = chatCompletion.usage;
    }

    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    public Builder withChoices(List<Choice> choices) {
      this.choices = choices;
      return this;
    }

    public Builder withCreated(Integer created) {
      this.created = created;
      return this;
    }

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withServiceTier(ServiceTier serviceTier) {
      this.serviceTier = serviceTier;
      return this;
    }

    public Builder withSystemFingerprint(String systemFingerprint) {
      this.systemFingerprint = systemFingerprint;
      return this;
    }

    public Builder withObject(Literal _object) {
      this._object = _object;
      return this;
    }

    public Builder withUsage(CompletionUsage usage) {
      this.usage = usage;
      return this;
    }

    public ChatCompletion build() {
      return new ChatCompletion(
          id,
          choices,
          created,
          model,
          serviceTier,
          systemFingerprint,
          _object,
          usage
      );
    }
  }
}
