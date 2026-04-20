/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JvmSandboxConfigTest {

  @Test
  void defaultsFactory() {
    var config = JvmSandboxConfig.defaults();
    assertEquals(Duration.ofSeconds(30), config.executionTimeout());
    assertEquals(256, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(60), config.callTimeout());
  }

  @Test
  void constructorSetsFields() {
    var config = new JvmSandboxConfig(Duration.ofSeconds(10), 512, Duration.ofSeconds(30));
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(512, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(30), config.callTimeout());
  }

  @Test
  void nullExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(null, 256, Duration.ofSeconds(60)));
  }

  @Test
  void zeroExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ZERO, 256, Duration.ofSeconds(60)));
  }

  @Test
  void negativeExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(-1), 256, Duration.ofSeconds(60)));
  }

  @Test
  void zeroHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(30), 0, Duration.ofSeconds(60)));
  }

  @Test
  void negativeHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(30), -1, Duration.ofSeconds(60)));
  }

  @Test
  void nullCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(30), 256, null));
  }

  @Test
  void zeroCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(30), 256, Duration.ZERO));
  }

  @Test
  void negativeCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JvmSandboxConfig(Duration.ofSeconds(30), 256, Duration.ofSeconds(-1)));
  }

  @Test
  void builderDefaults() {
    var config = JvmSandboxConfig.newBuilder().build();
    assertEquals(JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(JvmSandboxConfig.DEFAULT_MAX_HEAP_MB, config.maxHeapMb());
    assertEquals(JvmSandboxConfig.DEFAULT_CALL_TIMEOUT, config.callTimeout());
  }

  @Test
  void builderAllOptions() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withExecutionTimeout(Duration.ofMinutes(2))
            .withMaxHeapMb(1024)
            .withCallTimeout(Duration.ofMinutes(5))
            .build();
    assertEquals(Duration.ofMinutes(2), config.executionTimeout());
    assertEquals(1024, config.maxHeapMb());
    assertEquals(Duration.ofMinutes(5), config.callTimeout());
  }

  @Test
  void defaultConstants() {
    assertEquals(Duration.ofSeconds(30), JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT);
    assertEquals(256, JvmSandboxConfig.DEFAULT_MAX_HEAP_MB);
    assertEquals(Duration.ofSeconds(60), JvmSandboxConfig.DEFAULT_CALL_TIMEOUT);
  }
}
