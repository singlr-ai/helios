/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.base;

import java.util.Map;

public record FunctionObject(
    String description,
    String name,
    Map<String, Object> parameters,
    Boolean strict
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(FunctionObject functionObject) {
    return new Builder(functionObject);
  }

  public static class Builder {
    private String description;
    private String name;
    private Map<String, Object> parameters;
    private Boolean strict;

    private Builder() {}

    private Builder(FunctionObject functionObject) {
      this.description = functionObject.description;
      this.name = functionObject.name;
      this.parameters = functionObject.parameters;
      this.strict = functionObject.strict;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withParameters(Map<String, Object> parameters) {
      this.parameters = parameters;
      return this;
    }

    public Builder withStrict(Boolean strict) {
      this.strict = strict;
      return this;
    }

    public FunctionObject build() {
      return new FunctionObject(description, name, parameters, strict);
    }
  }
}
