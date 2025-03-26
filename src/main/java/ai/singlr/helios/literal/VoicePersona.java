/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

public enum VoicePersona {
  ALLOY("alloy"),
  ASH("ash"),
  BALLAD("ballad"),
  CORAL("coral"),
  ECHO("echo"),
  SAGE("sage"),
  SHIMMER("shimmer"),
  VERSE("verse");

  private final String value;

  VoicePersona (String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }
}
