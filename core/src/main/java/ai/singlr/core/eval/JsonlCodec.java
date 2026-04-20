/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private JSONL codec for {@link ExperimentEntry}. Hand-rolled to avoid pulling Jackson
 * into {@code core}, which is dependency-free by design.
 *
 * <p>The format is a deliberately narrow subset of JSON — exactly what {@link ExperimentEntry}
 * needs. Values are null, strings, finite numbers, or flat maps of string-to-string /
 * string-to-number. Unsupported tokens surface as {@link JsonlCodecException}. Callers must supply
 * finite numbers; {@link ExperimentEntry}'s constructor enforces that invariant upstream.
 */
final class JsonlCodec {

  private JsonlCodec() {}

  static String encode(ExperimentEntry entry) {
    var sb = new StringBuilder(256);
    sb.append('{');
    writeString(sb, "id");
    sb.append(':');
    writeString(sb, entry.id().toString());
    sb.append(',');
    writeString(sb, "segment");
    sb.append(':').append(entry.segment()).append(',');
    writeString(sb, "status");
    sb.append(':');
    writeString(sb, entry.status());
    sb.append(',');
    writeString(sb, "primary_metric");
    sb.append(':').append(formatNumber(entry.primaryMetric())).append(',');
    writeString(sb, "secondary_metrics");
    sb.append(':');
    writeNumberMap(sb, entry.secondaryMetrics());
    sb.append(',');
    writeString(sb, "description");
    sb.append(':');
    writeString(sb, entry.description());
    sb.append(',');
    writeString(sb, "asi");
    sb.append(':');
    writeStringMap(sb, entry.asi());
    sb.append(',');
    writeString(sb, "confidence");
    sb.append(':');
    if (entry.confidence() == null) {
      sb.append("null");
    } else {
      sb.append(formatNumber(entry.confidence()));
    }
    sb.append(',');
    writeString(sb, "timestamp");
    sb.append(':');
    writeString(sb, entry.timestamp().toString());
    sb.append('}');
    return sb.toString();
  }

  static ExperimentEntry decode(String line) {
    var parser = new Parser(line);
    var obj = parser.parseObject();
    parser.expectEnd();
    UUID id;
    try {
      id = UUID.fromString((String) requireField(obj, "id"));
    } catch (IllegalArgumentException e) {
      throw new JsonlCodecException("invalid id: " + obj.get("id"), e);
    }
    int segment = ((Number) requireField(obj, "segment")).intValue();
    String status = (String) requireField(obj, "status");
    double primary = ((Number) requireField(obj, "primary_metric")).doubleValue();
    var secondary = asDoubleMap((Map<?, ?>) requireField(obj, "secondary_metrics"));
    String description = (String) requireField(obj, "description");
    var asi = asStringMap((Map<?, ?>) requireField(obj, "asi"));
    Object confidenceRaw = obj.get("confidence");
    Double confidence = confidenceRaw == null ? null : ((Number) confidenceRaw).doubleValue();
    Instant timestamp;
    try {
      timestamp = Instant.parse((String) requireField(obj, "timestamp"));
    } catch (DateTimeParseException | ClassCastException e) {
      throw new JsonlCodecException("invalid timestamp: " + obj.get("timestamp"), e);
    }
    return new ExperimentEntry(
        id, segment, status, primary, secondary, description, asi, confidence, timestamp);
  }

  private static Object requireField(Map<String, Object> obj, String name) {
    if (!obj.containsKey(name)) {
      throw new JsonlCodecException("missing field: " + name);
    }
    var value = obj.get(name);
    if (value == null) {
      throw new JsonlCodecException("field must not be null: " + name);
    }
    return value;
  }

  private static Map<String, Double> asDoubleMap(Map<?, ?> raw) {
    var out = new LinkedHashMap<String, Double>(raw.size());
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new JsonlCodecException("map key must be string, got: " + entry.getKey());
      }
      if (!(entry.getValue() instanceof Number num)) {
        throw new JsonlCodecException(
            "expected number for key " + key + ", got: " + entry.getValue());
      }
      out.put(key, num.doubleValue());
    }
    return out;
  }

  private static Map<String, String> asStringMap(Map<?, ?> raw) {
    var out = new LinkedHashMap<String, String>(raw.size());
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new JsonlCodecException("map key must be string, got: " + entry.getKey());
      }
      if (!(entry.getValue() instanceof String value)) {
        throw new JsonlCodecException(
            "expected string for key " + key + ", got: " + entry.getValue());
      }
      out.put(key, value);
    }
    return out;
  }

  private static String formatNumber(double d) {
    if (d == Math.floor(d) && Math.abs(d) < 1e15) {
      return Long.toString((long) d);
    }
    return Double.toString(d);
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
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
  }

  private static void writeNumberMap(StringBuilder sb, Map<String, Double> map) {
    sb.append('{');
    boolean first = true;
    for (var entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeString(sb, entry.getKey());
      sb.append(':').append(formatNumber(entry.getValue()));
    }
    sb.append('}');
  }

  private static void writeStringMap(StringBuilder sb, Map<String, String> map) {
    sb.append('{');
    boolean first = true;
    for (var entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeString(sb, entry.getKey());
      sb.append(':');
      writeString(sb, entry.getValue());
    }
    sb.append('}');
  }

  /** Minimal JSON parser for the subset we emit. Not reusable for general JSON. */
  private static final class Parser {

    private final String src;
    private int pos;

    Parser(String src) {
      this.src = src;
      this.pos = 0;
    }

    Map<String, Object> parseObject() {
      skipWhitespace();
      expect('{');
      var out = new LinkedHashMap<String, Object>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return out;
      }
      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        Object value = parseValue();
        out.put(key, value);
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          continue;
        }
        expect('}');
        return out;
      }
    }

    Object parseValue() {
      char c = peek();
      if (c == '"') {
        return parseString();
      }
      if (c == '{') {
        return parseObject();
      }
      if (c == 'n') {
        expectKeyword("null");
        return null;
      }
      return parseNumber();
    }

    String parseString() {
      expect('"');
      var sb = new StringBuilder();
      while (pos < src.length()) {
        char c = src.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (pos >= src.length()) {
            throw new JsonlCodecException("unterminated escape at position " + pos);
          }
          char esc = src.charAt(pos++);
          switch (esc) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
              if (pos + 4 > src.length()) {
                throw new JsonlCodecException("bad \\u escape at position " + pos);
              }
              int codepoint;
              try {
                codepoint = Integer.parseInt(src.substring(pos, pos + 4), 16);
              } catch (NumberFormatException ex) {
                throw new JsonlCodecException("bad \\u escape at position " + pos, ex);
              }
              sb.append((char) codepoint);
              pos += 4;
            }
            default -> throw new JsonlCodecException("bad escape \\" + esc + " at position " + pos);
          }
        } else {
          sb.append(c);
        }
      }
      throw new JsonlCodecException("unterminated string");
    }

    Number parseNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      while (pos < src.length()) {
        char c = src.charAt(pos);
        if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          pos++;
        } else {
          break;
        }
      }
      if (pos == start) {
        throw new JsonlCodecException("expected number at position " + pos);
      }
      var lex = src.substring(start, pos);
      try {
        return Double.parseDouble(lex);
      } catch (NumberFormatException e) {
        throw new JsonlCodecException("bad number: " + lex, e);
      }
    }

    void expect(char c) {
      if (pos >= src.length() || src.charAt(pos) != c) {
        throw new JsonlCodecException("expected '" + c + "' at position " + pos);
      }
      pos++;
    }

    void expectKeyword(String kw) {
      if (pos + kw.length() > src.length() || !src.startsWith(kw, pos)) {
        throw new JsonlCodecException("expected '" + kw + "' at position " + pos);
      }
      pos += kw.length();
    }

    void expectEnd() {
      skipWhitespace();
      if (pos != src.length()) {
        throw new JsonlCodecException("unexpected trailing content at position " + pos);
      }
    }

    char peek() {
      if (pos >= src.length()) {
        throw new JsonlCodecException("unexpected end of input");
      }
      return src.charAt(pos);
    }

    void skipWhitespace() {
      while (pos < src.length()) {
        char c = src.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
          pos++;
        } else {
          break;
        }
      }
    }
  }
}
