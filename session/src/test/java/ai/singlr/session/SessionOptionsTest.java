/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SessionOptionsTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  // ── canonical constructor validation ──────────────────────────────────────

  @Test
  void canonicalConstructorRejectsNullModel() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    null,
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC()));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullSessionId() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    null,
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsBlankSessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "   ",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(), "sess", null, ConcurrencyLimits.defaults(), Clock.systemUTC()));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullConcurrency() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(), "sess", SessionLimits.defaults(), null, Clock.systemUTC()));
    assertEquals("concurrency must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullClock() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  // ── builder happy path ────────────────────────────────────────────────────

  @Test
  void builderProducesValidOptionsWithModelAlone() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotNull(opts.model());
    assertTrue(opts.sessionId().startsWith("sess-"));
    assertSame(SessionLimits.defaults(), opts.limits());
    assertSame(ConcurrencyLimits.defaults(), opts.concurrency());
    assertNotNull(opts.clock());
  }

  @Test
  void builderRespectsAllOverrides() {
    var fixed = Clock.fixed(java.time.Instant.parse("2026-05-14T19:00:00Z"), ZoneOffset.UTC);
    var customLimits =
        new SessionLimits(
            50,
            java.util.Optional.empty(),
            java.time.Duration.ofMinutes(30),
            java.time.Duration.ofSeconds(20),
            64_000L);
    var customConcurrency = new ConcurrencyLimits(8, 2, 1, 32);
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSessionId("explicit-id")
            .withLimits(customLimits)
            .withConcurrencyLimits(customConcurrency)
            .withClock(fixed)
            .build();
    assertEquals("explicit-id", opts.sessionId());
    assertSame(customLimits, opts.limits());
    assertSame(customConcurrency, opts.concurrency());
    assertSame(fixed, opts.clock());
  }

  @Test
  void builderAutoGeneratesUniqueSessionIds() {
    var a = SessionOptions.newBuilder().withModel(stubModel()).build();
    var b = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotEquals(a.sessionId(), b.sessionId());
  }

  @Test
  void buildWithoutModelThrows() {
    var ex = assertThrows(IllegalStateException.class, () -> SessionOptions.newBuilder().build());
    assertTrue(ex.getMessage().startsWith("model is required"));
  }

  // ── builder validation per setter ────────────────────────────────────────

  @Test
  void withModelRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withModel(null));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void withSessionIdRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SessionOptions.newBuilder().withSessionId(null));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void withSessionIdRejectsBlank() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> SessionOptions.newBuilder().withSessionId("  "));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void withLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SessionOptions.newBuilder().withLimits(null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void withConcurrencyLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> SessionOptions.newBuilder().withConcurrencyLimits(null));
    assertEquals("concurrency must not be null", ex.getMessage());
  }

  @Test
  void withClockRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withClock(null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  // ── toBuilder round-trip ──────────────────────────────────────────────────

  @Test
  void toBuilderRoundTripsAllFields() {
    var original = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("orig").build();
    var copy = original.toBuilder().build();
    assertEquals(original, copy);
  }

  @Test
  void toBuilderAllowsFieldOverride() {
    var original = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("orig").build();
    var variant = original.toBuilder().withSessionId("variant").build();
    assertEquals("variant", variant.sessionId());
    assertSame(original.model(), variant.model());
  }
}
