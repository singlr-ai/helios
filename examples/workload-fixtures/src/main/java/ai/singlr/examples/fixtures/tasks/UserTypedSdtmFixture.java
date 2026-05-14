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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * User-typed input shape — the regression test for Spec 05's hybrid binding fix. Input has {@code
 * List<SourceFile>} + {@code List<FieldInference>} where {@code SourceFile} / {@code
 * FieldInference} are user-defined records, not in {@code java.*}.
 *
 * <p>Before 1.5.2, the whole binding collapsed to raw {@link Object} and the model had to recover
 * via explicit casts (counted by {@code recoveryIterations}). After 1.5.2 the bindings come through
 * as {@code List<Object>} / {@code Map<String,Object>}-shaped, so {@code .size()} / {@code .get(0)}
 * work without ceremony. If this fixture's {@code recoveryIterations} metric climbs in a future
 * release, the hybrid binding has regressed.
 */
public final class UserTypedSdtmFixture implements Fixture {

  public record SourceFile(String name, List<String> fields) {}

  public record FieldInference(String sourceField, String targetField) {}

  public record In(List<SourceFile> files, List<FieldInference> inferences) {}

  public record Out(Map<String, List<String>> targetToSources) {}

  @Override
  public String name() {
    return "user-typed-sdtm";
  }

  @Override
  public String description() {
    return "User-typed nested records (regression test for Spec 05 hybrid binding); groups inferences by target field.";
  }

  @Override
  public FixtureOutcome run(Model model) {
    var input =
        new In(
            List.of(
                new SourceFile("demographics.csv", List.of("subject_id", "birth_date", "sex")),
                new SourceFile(
                    "adverse_events.csv", List.of("subject_id", "event_term", "severity"))),
            List.of(
                new FieldInference("subject_id", "USUBJID"),
                new FieldInference("birth_date", "BRTHDTC"),
                new FieldInference("sex", "SEX"),
                new FieldInference("event_term", "AETERM"),
                new FieldInference("severity", "AESEV")));

    var expected = expectedGrouping(input.inferences());

    var harness =
        CodeActHarness.builder(In.class, Out.class)
            .model(model)
            .sandboxFactory(JvmSandbox.factory())
            .strategy(
                "Build a Map keyed by targetField, where each value is the sorted list of"
                    + " sourceFields that map to it. Iterate over the inferences binding (its"
                    + " elements are Map<String,Object> at runtime — cast as needed to read"
                    + " 'sourceField' / 'targetField'). Return {targetToSources: that map}.")
            .maxIterations(6)
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
    boolean passed = false;
    if (result.success() && result.output().isPresent()) {
      passed = matches(result.output().get().targetToSources(), expected);
      if (!passed) {
        notes.add(
            "output mismatch: got "
                + result.output().get().targetToSources()
                + " expected "
                + expected);
      }
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

  static Map<String, List<String>> expectedGrouping(List<FieldInference> inferences) {
    var out = new LinkedHashMap<String, List<String>>();
    for (var inf : inferences) {
      out.computeIfAbsent(inf.targetField(), k -> new ArrayList<>()).add(inf.sourceField());
    }
    var sorted = new LinkedHashMap<String, List<String>>();
    for (var e : out.entrySet()) {
      sorted.put(e.getKey(), List.copyOf(new TreeSet<>(e.getValue())));
    }
    return sorted;
  }

  static boolean matches(Map<String, List<String>> actual, Map<String, List<String>> expected) {
    if (actual == null || actual.size() != expected.size()) {
      return false;
    }
    for (var entry : expected.entrySet()) {
      var actualValues = actual.get(entry.getKey());
      if (actualValues == null) {
        return false;
      }
      var actualSet = new TreeSet<>(actualValues);
      var expectedSet = new TreeSet<>(entry.getValue());
      if (!actualSet.equals(expectedSet)) {
        return false;
      }
    }
    return true;
  }
}
