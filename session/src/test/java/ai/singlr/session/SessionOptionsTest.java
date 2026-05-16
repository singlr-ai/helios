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

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
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
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
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
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    null,
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullConcurrency() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    null,
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
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
                    null,
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullTools() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    null,
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullHooks() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    null,
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullPermission() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    null,
                    java.util.Optional.empty(),
                    CostCalculator.ZERO));
    assertEquals("permission must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullMemoryBackend() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    null,
                    CostCalculator.ZERO));
    assertEquals("memoryBackend must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCostCalculator() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    java.util.List.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    null));
    assertEquals("costCalculator must not be null", ex.getMessage());
  }

  @Test
  void builderDefaultsCostCalculatorToZero() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertSame(CostCalculator.ZERO, opts.costCalculator());
  }

  @Test
  void withCostCalculatorRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withCostCalculator(null));
    assertEquals("costCalculator must not be null", ex.getMessage());
  }

  @Test
  void withCostCalculatorIsThreadedThroughBuildAndToBuilder() {
    CostCalculator custom = (id, u) -> CostEstimate.ofUsd(0.42);
    var opts =
        SessionOptions.newBuilder().withModel(stubModel()).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
    assertSame(custom, opts.toBuilder().build().costCalculator());
  }

  @Test
  void builderDefaultsPermissionToEmptyOptional() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertTrue(opts.permission().isEmpty());
  }

  @Test
  void withPermissionAcceptsNullAsClear() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withPermission(ai.singlr.session.permissions.Permission.defaultInWorkspace())
            .withPermission(null)
            .build();
    assertTrue(opts.permission().isEmpty());
  }

  @Test
  void withPermissionRetainsValue() {
    var perm = ai.singlr.session.permissions.Permission.defaultInWorkspace();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withPermission(perm).build();
    assertSame(perm, opts.permission().orElseThrow());
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
    var fixed = Clock.fixed(Instant.parse("2026-05-14T19:00:00Z"), ZoneOffset.UTC);
    var customLimits =
        SessionLimits.newBuilder()
            .withMaxTurns(50)
            .withMaxWallClock(Duration.ofMinutes(30))
            .withToolTimeoutDefault(Duration.ofSeconds(20))
            .withMaxContextTokens(64_000L)
            .build();
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

  @Test
  void withToolsRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withTools(null));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void builderDefaultsToolsToEmpty() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(0, opts.tools().size());
  }

  @Test
  void builderDefaultsHooksToEmpty() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(0, opts.hooks().size());
  }

  @Test
  void withHookAppendsAHookToTheList() {
    ai.singlr.session.hooks.PreToolUseHook hook =
        (call, ctx) -> ai.singlr.session.hooks.HookOutcome.cont();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withHook(hook).build();
    assertEquals(1, opts.hooks().size());
    assertSame(hook, opts.hooks().get(0));
  }

  @Test
  void withHooksReplacesTheList() {
    ai.singlr.session.hooks.PreToolUseHook a =
        (call, ctx) -> ai.singlr.session.hooks.HookOutcome.cont();
    ai.singlr.session.hooks.PreToolUseHook b =
        (call, ctx) -> ai.singlr.session.hooks.HookOutcome.cont();
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withHook(a)
            .withHooks(java.util.List.of(b))
            .build();
    assertEquals(1, opts.hooks().size());
    assertSame(b, opts.hooks().get(0));
  }

  @Test
  void withHookRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHook(null));
    assertEquals("hook must not be null", ex.getMessage());
  }

  @Test
  void withHooksRejectsNullList() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHooks(null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void withHooksRejectsListContainingNull() {
    var list = new java.util.ArrayList<ai.singlr.session.hooks.Hook>();
    list.add(null);
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHooks(list));
    assertEquals("hooks must not contain null", ex.getMessage());
  }

  @Test
  void builderHonorsCustomTools() {
    var custom = ToolRegistry.empty();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withTools(custom).build();
    assertSame(custom, opts.tools());
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
