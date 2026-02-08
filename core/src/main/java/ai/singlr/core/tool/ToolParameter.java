/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

/**
 * Definition of a tool parameter (JSON Schema style).
 *
 * @param name the parameter name
 * @param description description of the parameter for the model
 * @param type the JSON Schema type
 * @param required whether the parameter is required
 * @param defaultValue optional default value
 * @param items for ARRAY type, the schema of array items
 */
public record ToolParameter(
    String name,
    String description,
    ParameterType type,
    boolean required,
    Object defaultValue,
    ToolParameter items) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private String description;
    private ParameterType type = ParameterType.STRING;
    private boolean required = false;
    private Object defaultValue;
    private ToolParameter items;

    private Builder() {}

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withType(ParameterType type) {
      this.type = type;
      return this;
    }

    public Builder withRequired(boolean required) {
      this.required = required;
      return this;
    }

    public Builder withDefaultValue(Object defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    public Builder withItems(ToolParameter items) {
      this.items = items;
      return this;
    }

    public ToolParameter build() {
      return new ToolParameter(name, description, type, required, defaultValue, items);
    }
  }
}
