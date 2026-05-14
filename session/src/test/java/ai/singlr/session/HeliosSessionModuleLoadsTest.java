/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PR 1 smoke test for the helios-session module.
 *
 * <p>The mere fact that this test compiles and runs confirms: {@code pom.xml} is wired into the
 * reactor, the {@code module-info.java} is valid, surefire discovers tests in the new module, and
 * the dependency on {@code helios-core} resolves. Subsequent PRs replace this file with real
 * coverage as types land.
 */
final class HeliosSessionModuleLoadsTest {

  @Test
  void moduleScaffoldingIsWired() {
    var loader = getClass().getClassLoader();
    assertNotNull(loader, "test class loader must be present");
    assertTrue(true, "helios-session module compiles and surefire discovers tests");
  }
}
