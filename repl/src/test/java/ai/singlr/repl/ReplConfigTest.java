/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplConfigTest {

  private static final SandboxFactory DUMMY_FACTORY = registry -> null;

  @Test
  void constructorSetsFields() {
    var fn = new HostFunction("test", "desc", params -> "ok");
    var config = new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 5, List.of(fn));
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(5, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
  }

  @Test
  void nullFactoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(null, Duration.ofSeconds(10), 5, List.of()));
  }

  @Test
  void nullTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ReplConfig(DUMMY_FACTORY, null, 5, List.of()));
  }

  @Test
  void zeroTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ZERO, 5, List.of()));
  }

  @Test
  void negativeTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(-1), 5, List.of()));
  }

  @Test
  void zeroSessionsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 0, List.of()));
  }

  @Test
  void negativeSessionsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), -1, List.of()));
  }

  @Test
  void hostFunctionsAreImmutable() {
    var config = new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 5, List.of());
    assertThrows(
        UnsupportedOperationException.class,
        () -> config.hostFunctions().add(new HostFunction("x", "y", params -> "z")));
  }

  @Test
  void defaultConstants() {
    assertEquals(Duration.ofSeconds(30), ReplConfig.DEFAULT_EXECUTION_TIMEOUT);
    assertEquals(50, ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS);
  }

  @Test
  void builderDefaults() {
    var config = ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).build();
    assertEquals(ReplConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS, config.maxConcurrentSessions());
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
            .build();
    assertEquals(Duration.ofMinutes(1), config.executionTimeout());
    assertEquals(10, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
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
