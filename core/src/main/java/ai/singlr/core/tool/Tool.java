/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a tool that can be called by the model.
 *
 * @param name the unique name of the tool
 * @param description description of what the tool does (for the model)
 * @param parameters the parameters the tool accepts
 * @param executor the function to execute when the tool is called
 */
public record Tool(
    String name, String description, List<ToolParameter> parameters, ToolExecutor executor) {

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Execute this tool with the given arguments. */
  public ToolResult execute(Map<String, Object> arguments) {
    try {
      return executor.execute(arguments);
    } catch (Exception e) {
      return ToolResult.failure("Tool execution failed", e);
    }
  }

  /** Get required parameter names. */
  public List<String> requiredParameters() {
    return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name).toList();
  }

  /** Convert parameters to JSON Schema format (for model APIs). */
  public Map<String, Object> parametersAsJsonSchema() {
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();

    for (var param : parameters) {
      var propSchema = new LinkedHashMap<String, Object>();
      propSchema.put("type", param.type().jsonType());
      if (param.description() != null) {
        propSchema.put("description", param.description());
      }
      if (param.defaultValue() != null) {
        propSchema.put("default", param.defaultValue());
      }
      if (param.items() != null && param.type() == ParameterType.ARRAY) {
        var itemsSchema = new LinkedHashMap<String, Object>();
        itemsSchema.put("type", param.items().type().jsonType());
        if (param.items().description() != null) {
          itemsSchema.put("description", param.items().description());
        }
        propSchema.put("items", itemsSchema);
      }
      properties.put(param.name(), propSchema);

      if (param.required()) {
        required.add(param.name());
      }
    }

    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return Map.copyOf(schema);
  }

  /** Fluent builder to prepare a Tool. */
  public static class Builder {
    private String name;
    private String description;
    private final List<ToolParameter> parameters = new ArrayList<>();
    private ToolExecutor executor;

    private Builder() {}

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withParameter(ToolParameter parameter) {
      this.parameters.add(parameter);
      return this;
    }

    public Builder withParameters(List<ToolParameter> parameters) {
      this.parameters.addAll(parameters);
      return this;
    }

    public Builder withExecutor(ToolExecutor executor) {
      this.executor = executor;
      return this;
    }

    public Tool build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("Tool name is required");
      }
      if (executor == null) {
        throw new IllegalStateException("Tool executor is required");
      }
      return new Tool(name, description, List.copyOf(parameters), executor);
    }
  }
}
