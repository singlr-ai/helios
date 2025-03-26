package ai.singlr.helios.literal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ModelName {
  GPT_4O("gpt-4o"),
  GPT_4O_MINI("gpt-4o-mini"),
  WHISPER("whisper-1");

  private final String value;

  ModelName(String value) {
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
