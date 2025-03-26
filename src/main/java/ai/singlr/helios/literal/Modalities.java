/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

public enum Modalities {

  TEXT("text"),
  AUDIO("audio");

  private final String value;

  Modalities (String v) {
    value = v;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }
}
