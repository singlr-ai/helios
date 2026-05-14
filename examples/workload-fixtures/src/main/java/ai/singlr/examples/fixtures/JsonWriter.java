/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

/**
 * Minimal hand-rolled JSON writer for {@link Metrics}. The JSON shape is tiny and frozen by the
 * {@link Metrics} record; pulling Jackson in just to serialize one flat record would be
 * disproportionate.
 */
final class JsonWriter {

  private JsonWriter() {}

  static String toJson(Metrics m) {
    var sb = new StringBuilder(256);
    sb.append('{');
    appendString(sb, "fixture", m.fixture()).append(',');
    appendString(sb, "model", m.model()).append(',');
    appendNumber(sb, "attempt", m.attempt()).append(',');
    appendBoolean(sb, "passed", m.passed()).append(',');
    appendNumber(sb, "setupTurns", m.setupTurns()).append(',');
    appendNumber(sb, "totalIterations", m.totalIterations()).append(',');
    appendNumber(sb, "totalTokens", m.totalTokens()).append(',');
    appendNumber(sb, "recoveryIterations", m.recoveryIterations()).append(',');
    appendNumber(sb, "durationMs", m.durationMs()).append(',');
    appendStringArray(sb, "notes", m.notes());
    sb.append('}');
    return sb.toString();
  }

  private static StringBuilder appendString(StringBuilder sb, String key, String value) {
    return appendKey(sb, key).append(quote(value));
  }

  private static StringBuilder appendNumber(StringBuilder sb, String key, long value) {
    return appendKey(sb, key).append(value);
  }

  private static StringBuilder appendBoolean(StringBuilder sb, String key, boolean value) {
    return appendKey(sb, key).append(value);
  }

  private static StringBuilder appendStringArray(
      StringBuilder sb, String key, java.util.List<String> values) {
    appendKey(sb, key).append('[');
    boolean first = true;
    for (var v : values) {
      if (!first) {
        sb.append(',');
      }
      sb.append(quote(v));
      first = false;
    }
    return sb.append(']');
  }

  private static StringBuilder appendKey(StringBuilder sb, String key) {
    return sb.append(quote(key)).append(':');
  }

  static String quote(String s) {
    if (s == null) {
      return "null";
    }
    var sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
