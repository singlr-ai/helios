/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import ai.singlr.core.model.Model;

/**
 * One workload definition. Concrete implementations construct their own harness, supply their own
 * input, and return a {@link FixtureOutcome} that {@link FixtureRunner} converts into {@link
 * Metrics}.
 */
public interface Fixture {

  /** Stable slug used in CLI args and report tables (e.g., {@code numeric-stats}). */
  String name();

  /** Human-readable one-line description for the markdown report. */
  String description();

  /** Run the fixture against the given model. */
  FixtureOutcome run(Model model);
}
