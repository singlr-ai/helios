/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplConfigTest {

  private static final SandboxFactory DUMMY_FACTORY = registry -> null;
  private static final int DEFAULT_CAP = ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;

  @Test
  void constructorSetsFields() {
    var fn = new HostFunction("test", "desc", params -> "ok");
    var config =
        new ReplConfig(
            DUMMY_FACTORY,
            Duration.ofSeconds(10),
            5,
            List.of(fn),
            DEFAULT_CAP,
            null,
            ReplConfig.DEFAULT_MAX_LLM_CALLS,
            true,
            java.util.List.of(),
            null,
            200,
            16384,
            null,
            5000);
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(5, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
    assertEquals(DEFAULT_CAP, config.maxOutputCharsToModel());
  }

  @Test
  void nullFactoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                null,
                Duration.ofSeconds(10),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void nullTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                null,
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void zeroTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ZERO,
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void negativeTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(-1),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void zeroSessionsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                0,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void negativeSessionsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                -1,
                List.of(),
                DEFAULT_CAP,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void negativeMaxOutputCharsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                5,
                List.of(),
                -1,
                null,
                ReplConfig.DEFAULT_MAX_LLM_CALLS,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void zeroMaxOutputCharsAllowedToDisableTruncation() {
    var config =
        new ReplConfig(
            DUMMY_FACTORY,
            Duration.ofSeconds(10),
            5,
            List.of(),
            0,
            null,
            ReplConfig.DEFAULT_MAX_LLM_CALLS,
            true,
            java.util.List.of(),
            null,
            200,
            16384,
            null,
            5000);
    assertEquals(0, config.maxOutputCharsToModel());
  }

  @Test
  void hostFunctionsAreImmutable() {
    var config =
        new ReplConfig(
            DUMMY_FACTORY,
            Duration.ofSeconds(10),
            5,
            List.of(),
            DEFAULT_CAP,
            null,
            ReplConfig.DEFAULT_MAX_LLM_CALLS,
            true,
            java.util.List.of(),
            null,
            200,
            16384,
            null,
            5000);
    assertThrows(
        UnsupportedOperationException.class,
        () -> config.hostFunctions().add(new HostFunction("x", "y", params -> "z")));
  }

  @Test
  void defaultConstants() {
    assertEquals(Duration.ofSeconds(30), ReplConfig.DEFAULT_EXECUTION_TIMEOUT);
    assertEquals(50, ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS);
    assertEquals(5000, ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL);
  }

  @Test
  void builderDefaults() {
    var config = ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).build();
    assertEquals(ReplConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS, config.maxConcurrentSessions());
    assertEquals(ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL, config.maxOutputCharsToModel());
    assertTrue(config.hostFunctions().isEmpty());
  }

  @Test
  void builderAllOptions() {
    var fn = new HostFunction("a", "desc", params -> "a");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(DUMMY_FACTORY)
            .withExecutionTimeout(Duration.ofMinutes(1))
            .withMaxConcurrentSessions(10)
            .withHostFunction(fn)
            .withMaxOutputCharsToModel(2000)
            .build();
    assertEquals(Duration.ofMinutes(1), config.executionTimeout());
    assertEquals(10, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
    assertEquals(2000, config.maxOutputCharsToModel());
  }

  @Test
  void builderSubmitSchemaDefaultsToNull() {
    var config = ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).build();
    org.junit.jupiter.api.Assertions.assertNull(config.submitSchema());
  }

  public record DummyOutput(String answer) {}

  @Test
  void builderWithSubmitSchema() {
    var schema = OutputSchema.of(DummyOutput.class);
    var config =
        ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).withSubmitSchema(schema).build();
    assertEquals(schema, config.submitSchema());
  }

  @Test
  void recordPositionalConstructorAcceptsSubmitSchema() {
    var schema = OutputSchema.of(String.class, JsonSchema.string());
    var config =
        new ReplConfig(
            DUMMY_FACTORY,
            Duration.ofSeconds(10),
            5,
            List.of(),
            DEFAULT_CAP,
            schema,
            ReplConfig.DEFAULT_MAX_LLM_CALLS,
            true,
            java.util.List.of(),
            null,
            200,
            16384,
            null,
            5000);
    assertEquals(schema, config.submitSchema());
  }

  @Test
  void defaultMaxLlmCallsIs50() {
    assertEquals(50, ReplConfig.DEFAULT_MAX_LLM_CALLS);
    var config = ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).build();
    assertEquals(50, config.maxLlmCalls());
  }

  @Test
  void builderWithMaxLlmCalls() {
    var config =
        ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).withMaxLlmCalls(7).build();
    assertEquals(7, config.maxLlmCalls());
  }

  @Test
  void zeroMaxLlmCallsAllowedToDisableBudget() {
    var config =
        ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).withMaxLlmCalls(0).build();
    assertEquals(0, config.maxLlmCalls());
  }

  @Test
  void negativeMaxLlmCallsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                -1,
                true,
                java.util.List.of(),
                null,
                200,
                16384,
                null,
                5000));
  }

  @Test
  void builderWithHostFunctionsList() {
    var fn1 = new HostFunction("a", "desc", params -> "a");
    var fn2 = new HostFunction("b", "desc", params -> "b");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(DUMMY_FACTORY)
            .withHostFunctions(List.of(fn1, fn2))
            .build();
    assertEquals(2, config.hostFunctions().size());
  }
}
