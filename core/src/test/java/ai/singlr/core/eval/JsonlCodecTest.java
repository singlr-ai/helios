/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonlCodecTest {

  private static ExperimentEntry sample() {
    return ExperimentEntry.newBuilder()
        .withId(UUID.fromString("01234567-89ab-7cde-8123-456789abcdef"))
        .withSegment(2)
        .withStatus(ExperimentStatus.KEEP)
        .withPrimaryMetric(42.5)
        .withSecondaryMetrics(Map.of("latencyMs", 12.0, "size", 1000.0))
        .withDescription("hello\nworld")
        .withAsi(Map.of("note", "\"quoted\""))
        .withConfidence(1.75)
        .withTimestamp(Instant.parse("2026-04-19T10:00:00Z"))
        .build();
  }

  @Test
  void roundTrip() {
    var original = sample();
    var line = JsonlCodec.encode(original);
    var parsed = JsonlCodec.decode(line);
    assertEquals(original, parsed);
  }

  @Test
  void roundTripWithNullConfidence() {
    var e =
        ExperimentEntry.newBuilder()
            .withStatus(ExperimentStatus.DISCARD)
            .withPrimaryMetric(1.0)
            .withConfidence(null)
            .build();
    assertEquals(e, JsonlCodec.decode(JsonlCodec.encode(e)));
  }

  @Test
  void roundTripWithEmptyMaps() {
    var e =
        ExperimentEntry.newBuilder()
            .withStatus(ExperimentStatus.KEEP)
            .withPrimaryMetric(0.0)
            .build();
    assertEquals(e, JsonlCodec.decode(JsonlCodec.encode(e)));
  }

  @Test
  void encodesControlCharsAsEscapes() {
    var e =
        ExperimentEntry.newBuilder()
            .withStatus(ExperimentStatus.KEEP)
            .withPrimaryMetric(1.0)
            .withDescription("\b\f\n\r\t\u0001")
            .build();
    var line = JsonlCodec.encode(e);
    assertTrue(line.contains("\\b"));
    assertTrue(line.contains("\\f"));
    assertTrue(line.contains("\\n"));
    assertTrue(line.contains("\\r"));
    assertTrue(line.contains("\\t"));
    assertTrue(line.contains("\\u0001"));
    var parsed = JsonlCodec.decode(line);
    assertEquals(e.description(), parsed.description());
  }

  @Test
  void encodesIntegerValuesWithoutDecimal() {
    var e =
        ExperimentEntry.newBuilder()
            .withStatus(ExperimentStatus.KEEP)
            .withPrimaryMetric(12.0)
            .withSecondaryMetrics(Map.of("n", 3.0))
            .build();
    var line = JsonlCodec.encode(e);
    assertTrue(line.contains("\"primary_metric\":12"));
    assertTrue(line.contains("\"n\":3"));
  }

  @Test
  void decodeRejectsMissingField() {
    assertThrows(
        JsonlCodecException.class,
        () -> JsonlCodec.decode("{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\"}"));
  }

  @Test
  void decodeRejectsBadTimestamp() {
    var e = sample();
    var line = JsonlCodec.encode(e);
    var broken = line.replace("2026-04-19T10:00:00Z", "not-a-date");
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(broken));
  }

  @Test
  void decodeRejectsBadUuid() {
    var broken =
        "{\"id\":\"not-a-uuid\",\"segment\":0,\"status\":\"keep\",\"primary_metric\":1,"
            + "\"secondary_metrics\":{},\"description\":\"\",\"asi\":{},\"confidence\":null,"
            + "\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(broken));
  }

  @Test
  void decodeRejectsTrailingGarbage() {
    var line = JsonlCodec.encode(sample()) + "garbage";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(line));
  }

  @Test
  void decodeParsesUEscapes() {
    var line =
        "{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\",\"segment\":0,\"status\":\"keep\","
            + "\"primary_metric\":1,\"secondary_metrics\":{},\"description\":\"\\u0041\","
            + "\"asi\":{},\"confidence\":null,\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    assertEquals("A", JsonlCodec.decode(line).description());
  }

  @Test
  void decodeRejectsBadEscape() {
    var line =
        "{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\",\"segment\":0,\"status\":\"keep\","
            + "\"primary_metric\":1,\"secondary_metrics\":{},\"description\":\"\\x\","
            + "\"asi\":{},\"confidence\":null,\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(line));
  }

  @Test
  void decodeRejectsBadUEscape() {
    var line =
        "{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\",\"segment\":0,\"status\":\"keep\","
            + "\"primary_metric\":1,\"secondary_metrics\":{},\"description\":\"\\uZZZZ\","
            + "\"asi\":{},\"confidence\":null,\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(line));
  }

  @Test
  void decodeRejectsUnterminatedString() {
    var line = "{\"id\":\"01234567";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(line));
  }

  @Test
  void decodeRejectsBadNumber() {
    var line =
        "{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\",\"segment\":0,\"status\":\"keep\","
            + "\"primary_metric\":--,\"secondary_metrics\":{},\"description\":\"\","
            + "\"asi\":{},\"confidence\":null,\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    assertThrows(JsonlCodecException.class, () -> JsonlCodec.decode(line));
  }

  @Test
  void decodeHandlesEmptyObjects() {
    var line =
        "{\"id\":\"01234567-89ab-7cde-8123-456789abcdef\",\"segment\":0,\"status\":\"keep\","
            + "\"primary_metric\":1,\"secondary_metrics\":{},\"description\":\"\","
            + "\"asi\":{},\"confidence\":null,\"timestamp\":\"2026-04-19T10:00:00Z\"}";
    var parsed = JsonlCodec.decode(line);
    assertTrue(parsed.secondaryMetrics().isEmpty());
    assertTrue(parsed.asi().isEmpty());
  }

  @Test
  void exceptionWithCauseBuilds() {
    var ex = new JsonlCodecException("x", new RuntimeException("y"));
    assertEquals("x", ex.getMessage());
    assertEquals("y", ex.getCause().getMessage());
  }
}
