/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExperimentEntryTest {

  @Test
  void buildWithDefaults() {
    var e = ExperimentEntry.newBuilder().withStatus("keep").withPrimaryMetric(1.2).build();
    assertNotNull(e.id());
    assertEquals(0, e.segment());
    assertEquals("keep", e.status());
    assertEquals(1.2, e.primaryMetric());
    assertEquals("", e.description());
    assertTrue(e.secondaryMetrics().isEmpty());
    assertTrue(e.asi().isEmpty());
    assertNull(e.confidence());
    assertNotNull(e.timestamp());
  }

  @Test
  void buildWithAllOptions() {
    var id = UUID.randomUUID();
    var ts = Instant.parse("2026-04-19T10:15:30Z");
    var e =
        ExperimentEntry.newBuilder()
            .withId(id)
            .withSegment(2)
            .withStatus("discard")
            .withPrimaryMetric(3.14)
            .withSecondaryMetrics(Map.of("latencyMs", 45.0))
            .withDescription("tried pooling")
            .withAsi(Map.of("hypothesis", "fork/join helps"))
            .withConfidence(2.1)
            .withTimestamp(ts)
            .build();
    assertEquals(id, e.id());
    assertEquals(2, e.segment());
    assertEquals("discard", e.status());
    assertEquals(3.14, e.primaryMetric());
    assertEquals(45.0, e.secondaryMetrics().get("latencyMs"));
    assertEquals("tried pooling", e.description());
    assertEquals("fork/join helps", e.asi().get("hypothesis"));
    assertEquals(2.1, e.confidence());
    assertEquals(ts, e.timestamp());
  }

  @Test
  void crashStatusAccepted() {
    var e = ExperimentEntry.newBuilder().withStatus("crash").withPrimaryMetric(0.0).build();
    assertEquals("crash", e.status());
  }

  @Test
  void rejectsNullId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExperimentEntry(null, 0, "keep", 1.0, Map.of(), "", Map.of(), null, Instant.now()));
  }

  @Test
  void rejectsNegativeSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ExperimentEntry.newBuilder().withSegment(-1).withStatus("keep").build());
  }

  @Test
  void rejectsUnknownStatus() {
    var b = ExperimentEntry.newBuilder().withStatus("bogus");
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void rejectsNullStatus() {
    var b = ExperimentEntry.newBuilder();
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void rejectsNonFinitePrimaryMetric() {
    var b = ExperimentEntry.newBuilder().withStatus("keep").withPrimaryMetric(Double.NaN);
    assertThrows(IllegalArgumentException.class, b::build);
    var b2 =
        ExperimentEntry.newBuilder().withStatus("keep").withPrimaryMetric(Double.POSITIVE_INFINITY);
    assertThrows(IllegalArgumentException.class, b2::build);
  }

  @Test
  void rejectsNonFiniteSecondaryMetric() {
    var map = new HashMap<String, Double>();
    map.put("x", Double.NaN);
    var b = ExperimentEntry.newBuilder().withStatus("keep").withSecondaryMetrics(map);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void rejectsNullSecondaryValue() {
    var map = new HashMap<String, Double>();
    map.put("x", null);
    var b = ExperimentEntry.newBuilder().withStatus("keep").withSecondaryMetrics(map);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void rejectsNullDescription() {
    var b = ExperimentEntry.newBuilder().withStatus("keep").withDescription(null);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void rejectsNonFiniteConfidence() {
    var b = ExperimentEntry.newBuilder().withStatus("keep").withConfidence(Double.NaN);
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void nullMapsBecomeEmpty() {
    var e =
        new ExperimentEntry(UUID.randomUUID(), 0, "keep", 1.0, null, "", null, null, Instant.now());
    assertTrue(e.secondaryMetrics().isEmpty());
    assertTrue(e.asi().isEmpty());
  }

  @Test
  void rejectsNullTimestamp() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExperimentEntry(
                UUID.randomUUID(), 0, "keep", 1.0, Map.of(), "", Map.of(), null, null));
  }
}
