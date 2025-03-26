/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.base;

import ai.singlr.helios.literal.ResponseType;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An object specifying the format that the model must output. Compatible with [GPT-4o](/docs/models#gpt-4o), [GPT-4o mini](/docs/models#gpt-4o-mini), [GPT-4 Turbo](/docs/models#gpt-4-turbo-and-gpt-4) and all GPT-3.5 Turbo models newer than `gpt-3.5-turbo-1106`.  Setting to `{ \"type\": \"json_schema\", \"json_schema\": {...} }` enables Structured Outputs which ensures the model will match your supplied JSON schema. Learn more in the [Structured Outputs guide](/docs/guides/structured-outputs).  Setting to `{ \"type\": \"json_object\" }` enables JSON mode, which ensures the message the model generates is valid JSON.  **Important:** when using JSON mode, you **must** also instruct the model to produce JSON yourself via a system or user message. Without this, the model may generate an unending stream of whitespace until the generation reaches the token limit, resulting in a long-running and seemingly \"stuck\" request. Also note that the message content may be partially cut off if `finish_reason=\"length\"`, which indicates the generation exceeded `max_tokens` or the conversation exceeded the max context length.
 **/
public record ResponseFormat(
    ResponseType type,
    @JsonProperty("json_schema")
    JsonSchema jsonSchema) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ResponseFormat responseFormat) {
    return new Builder(responseFormat);
  }

  public static class Builder {
    private ResponseType type;
    private JsonSchema jsonSchema;

    private Builder() {}

    private Builder(ResponseFormat responseFormat) {
      this.type = responseFormat.type;
      this.jsonSchema = responseFormat.jsonSchema;
    }

    public Builder withType(ResponseType type) {
      this.type = type;
      return this;
    }

    public Builder withJsonSchema(JsonSchema jsonSchema) {
      this.jsonSchema = jsonSchema;
      return this;
    }

    public ResponseFormat build() {
      return new ResponseFormat(type, jsonSchema);
    }
  }
}
