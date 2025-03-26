/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.ModelName;
import ai.singlr.helios.literal.Modalities;
import ai.singlr.helios.literal.ServiceTier;
import ai.singlr.helios.model.base.ResponseFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.Float;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompletionRequest(
    List<ChatCompletionMessage> messages,
    ModelName model,
    Boolean store,
    Map<String, String> metadata,
    @JsonProperty("frequency_penalty")
    Float frequencyPenalty,
    @JsonProperty("logit_bias")
    Map<String, Integer> logitBias,
    Boolean logprobs,
    @JsonProperty("top_logprobs")
    Integer topLogprobs,
    @JsonProperty("max_tokens")
    Integer maxTokens,
    @JsonProperty("max_completion_tokens")
    Integer maxCompletionTokens,
    Integer n,
    List<Modalities> modalities,
    ChatCompletionPrediction prediction,
    ChatCompletionAudio audio,
    @JsonProperty("presence_penalty")
    Float presencePenalty,
    @JsonProperty("response_format")
    ResponseFormat responseFormat,
    Integer seed,
    @JsonProperty("service_tier")
    ServiceTier serviceTier,
    Object stop,
    Boolean stream,
    @JsonProperty("stream_options")
    ChatCompletionStreamOptions streamOptions,
    Float temperature,
    @JsonProperty("top_p")
    Float topP,
    List<ChatCompletionTool> tools,
    @JsonProperty("tool_choice")
    ChatCompletionToolChoice toolChoice,
    @JsonProperty("parallel_tool_calls")
    Boolean parallelToolCalls,
    String user
) {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(CompletionRequest request) {
    return new Builder(request);
  }

  public static class Builder {
    private List<ChatCompletionMessage> messages = new ArrayList<>();
    private ModelName model;
    private Boolean store = false;
    private Map<String, String> metadata;
    private Float frequencyPenalty = 0F;
    private Map<String, Integer> logitBias;
    private Boolean logprobs = false;
    private Integer topLogprobs;
    private Integer maxTokens;
    private Integer maxCompletionTokens;
    private Integer n = 1;
    private List<Modalities> modalities;
    private ChatCompletionPrediction prediction;
    private ChatCompletionAudio audio;
    private Float presencePenalty = 0F;
    private ResponseFormat responseFormat;
    private Integer seed;
    private ServiceTier serviceTier = ServiceTier.AUTO;
    private Object stop;
    private Boolean stream = false;
    private ChatCompletionStreamOptions streamOptions;
    private Float temperature = 1F;
    private Float topP = 1F;
    private List<ChatCompletionTool> tools;
    private ChatCompletionToolChoice toolChoice;
    private Boolean parallelToolCalls = null;
    private String user;

    private Builder() {}

    private Builder(CompletionRequest request) {
      this.messages = new ArrayList<>(request.messages);
      this.model = request.model;
      this.store = request.store;
      this.metadata = request.metadata;
      this.frequencyPenalty = request.frequencyPenalty;
      this.logitBias = request.logitBias;
      this.logprobs = request.logprobs;
      this.topLogprobs = request.topLogprobs;
      this.maxTokens = request.maxTokens;
      this.maxCompletionTokens = request.maxCompletionTokens;
      this.n = request.n;
      this.modalities = request.modalities;
      this.prediction = request.prediction;
      this.audio = request.audio;
      this.presencePenalty = request.presencePenalty;
      this.responseFormat = request.responseFormat;
      this.seed = request.seed;
      this.serviceTier = request.serviceTier;
      this.stop = request.stop;
      this.stream = request.stream;
      this.streamOptions = request.streamOptions;
      this.temperature = request.temperature;
      this.topP = request.topP;
      this.tools = request.tools;
      this.toolChoice = request.toolChoice;
      this.parallelToolCalls = request.parallelToolCalls;
      this.user = request.user;
    }

    public Builder prependMessage(ChatCompletionMessage message) {
      this.messages.addFirst(message);
      return this;
    }

    public Builder withMessages(List<ChatCompletionMessage> messages) {
      this.messages = messages;
      return this;
    }

    public Builder withModel(ModelName model) {
      this.model = model;
      return this;
    }

    public Builder withStore(Boolean store) {
      this.store = store;
      return this;
    }

    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder withFrequencyPenalty(Float frequencyPenalty) {
      this.frequencyPenalty = frequencyPenalty;
      return this;
    }

    public Builder withLogitBias(Map<String, Integer> logitBias) {
      this.logitBias = logitBias;
      return this;
    }

    public Builder withLogprobs(Boolean logprobs) {
      this.logprobs = logprobs;
      return this;
    }

    public Builder withTopLogprobs(Integer topLogprobs) {
      this.topLogprobs = topLogprobs;
      return this;
    }

    public Builder withMaxTokens(Integer maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public Builder withMaxCompletionTokens(Integer maxCompletionTokens) {
      this.maxCompletionTokens = maxCompletionTokens;
      return this;
    }

    public Builder withN(Integer n) {
      this.n = n;
      return this;
    }

    public Builder withModalities(List<Modalities> modalities) {
      this.modalities = modalities;
      return this;
    }

    public Builder withPrediction(ChatCompletionPrediction prediction) {
      this.prediction = prediction;
      return this;
    }

    public Builder withAudio(ChatCompletionAudio audio) {
      this.audio = audio;
      return this;
    }

    public Builder withPresencePenalty(Float presencePenalty) {
      this.presencePenalty = presencePenalty;
      return this;
    }

    public Builder withResponseFormat(ResponseFormat responseFormat) {
      this.responseFormat = responseFormat;
      return this;
    }

    public Builder withSeed(Integer seed) {
      this.seed = seed;
      return this;
    }

    public Builder withServiceTier(ServiceTier serviceTier) {
      this.serviceTier = serviceTier;
      return this;
    }

    public Builder withStop(Object stop) {
      this.stop = stop;
      return this;
    }

    public Builder withStream(Boolean stream) {
      this.stream = stream;
      return this;
    }

    public Builder withStreamOptions(ChatCompletionStreamOptions streamOptions) {
      this.streamOptions = streamOptions;
      return this;
    }

    public Builder withTemperature(Float temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTopP(Float topP) {
      this.topP = topP;
      return this;
    }

    public Builder withTools(List<ChatCompletionTool> tools) {
      this.tools = tools;
      return this;
    }

    public Builder withToolChoice(ChatCompletionToolChoice toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public Builder withParallelToolCalls(Boolean parallelToolCalls) {
      this.parallelToolCalls = parallelToolCalls;
      return this;
    }

    public Builder withUser(String user) {
      this.user = user;
      return this;
    }

    public CompletionRequest build() {
      return new CompletionRequest(
          messages,
          model,
          store,
          metadata,
          frequencyPenalty,
          logitBias,
          logprobs,
          topLogprobs,
          maxTokens,
          maxCompletionTokens,
          n,
          modalities,
          prediction,
          audio,
          presencePenalty,
          responseFormat,
          seed,
          serviceTier,
          stop,
          stream,
          streamOptions,
          temperature,
          topP,
          tools,
          toolChoice,
          parallelToolCalls,
          user
      );
    }
  }
}
