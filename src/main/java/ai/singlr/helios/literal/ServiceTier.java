/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ServiceTier {
  AUTO("auto"),
  SCALE("scale"),
  DEFAULT("default");

  private final String value;

  ServiceTier(String value) {
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