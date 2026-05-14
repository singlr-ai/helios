/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures.tasks;

import ai.singlr.core.model.Model;
import ai.singlr.examples.fixtures.Fixture;
import ai.singlr.examples.fixtures.FixtureOutcome;
import ai.singlr.repl.CodeActHarness;
import ai.singlr.repl.InputBindings;
import ai.singlr.repl.sandbox.JvmSandbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fully-{@code java.*} input shape — the canonical "binding works perfectly" case. Whatever
 * trajectory cost a model accrues here is intrinsic to the task, not the binding seam. Used as the
 * floor for cross-fixture comparison.
 */
public final class NumericStatsFixture implements Fixture {

  public record In(List<Double> values, String operation) {}

  public record Out(double result, String operationPerformed) {}

  @Override
  public String name() {
    return "numeric-stats";
  }

  @Override
  public String description() {
    return "Fully java.* input (List<Double> + String); CodeActHarness computes sum/mean/max/min.";
  }

  @Override
  public FixtureOutcome run(Model model) {
    var input = new In(List.of(1.0, 5.0, 3.0, 2.0, 4.0, 7.0, 6.0), "sum");
    var expected = 28.0;
    var harness =
        CodeActHarness.builder(In.class, Out.class)
            .model(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Compute the requested operation ('sum', 'mean', 'max', or 'min') on the values."
                    + " Write Java in execute_code, then return your structured final answer as the"
                    + " assistant message. 'result' is the numeric answer; 'operationPerformed' is"
                    + " a short label.")
            .maxIterations(5)
            .build();

    var started = System.nanoTime();
    var result = harness.run(input);
    var elapsed = Duration.ofNanos(System.nanoTime() - started);
    var executedCode = new ArrayList<String>();
    for (var r : result.executionHistory()) {
      executedCode.add(r.executedCode());
    }
    var notes = new ArrayList<String>();
    if (!result.success()) {
      notes.add("failed: " + result.error().orElse("unknown"));
    }
    var passed =
        result.success()
            && result.output().isPresent()
            && Math.abs(result.output().get().result() - expected) < 1e-6;
    if (result.success() && !passed) {
      notes.add("returned wrong value: " + result.output().map(Out::result).orElse(Double.NaN));
    }
    return new FixtureOutcome(
        passed,
        0,
        executedCode,
        InputBindings.boundFieldNames(In.class),
        result.trace(),
        elapsed,
        notes);
  }
}
