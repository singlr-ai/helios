/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ResponseType {
  TEXT("text"),
  JSON_OBJECT("json_object"),
  JSON_SCHEMA("json_schema");

  private final String value;

  ResponseType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }
}
