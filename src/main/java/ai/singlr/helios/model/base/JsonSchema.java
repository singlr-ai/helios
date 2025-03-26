/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.base;

import java.util.Map;

public record JsonSchema(
    String description,
    String name,
    Map<String, Object> schema,
    Boolean strict
) {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(JsonSchema jsonSchema) {
    return new Builder(jsonSchema);
  }

  public static class Builder {
    private String description;
    private String name;
    private Map<String, Object> schema;
    private Boolean strict;

    private Builder() {}

    private Builder(JsonSchema jsonSchema) {
      this.description = jsonSchema.description;
      this.name = jsonSchema.name;
      this.schema = jsonSchema.schema;
      this.strict = jsonSchema.strict;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withSchema(Map<String, Object> schema) {
      this.schema = schema;
      return this;
    }

    public Builder withStrict(Boolean strict) {
      this.strict = strict;
      return this;
    }

    public JsonSchema build() {
      return new JsonSchema(description, name, schema, strict);
    }
  }
}
