/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import jdk.jshell.JShell;

/**
 * Installs a script-friendly preamble into a {@link JShell} instance at sandbox boot. The preamble
 * adds standard imports, free {@code print}/{@code println}/{@code printf} methods, and a curated
 * set of helpers ({@code sum}, {@code mean}, {@code max}, {@code min}, {@code join}, {@code
 * filter}, {@code map}, {@code sorted}, {@code countBy}) so sandbox code can express common
 * operations as one-liners instead of full {@code Stream} chains.
 *
 * <p>Lives at the sandbox layer so every consumer benefits — direct {@code CodeExecutionTool}
 * users, the RLM harness, and any future composition all see the same surface. Running before any
 * user code guarantees the helpers are visible from the first {@code execute_code} call.
 *
 * <p>The preamble is a single Java snippet that {@link #install} splits into completion units via
 * {@link jdk.jshell.SourceCodeAnalysis} and evaluates in order. No source preprocessing, no class
 * wrapping — just JShell snippets.
 */
public final class SandboxPrelude {

  private SandboxPrelude() {}

  /**
   * The full preamble snippet. Public so users introspecting the sandbox surface (e.g. for
   * documentation) can see exactly what's available; the LLM's system prompt should reference the
   * names but not the bodies.
   */
  public static final String SNIPPET =
      """
      import java.util.*;
      import java.util.stream.*;
      import java.util.stream.Collectors;
      import java.util.function.*;
      import java.util.regex.*;
      import java.io.*;
      import java.math.*;
      import java.time.*;

      void print(Object x) { System.out.print(x); }
      void println() { System.out.println(); }
      void println(Object x) { System.out.println(x); }
      void printf(String fmt, Object... args) { System.out.printf(fmt, args); }

      static double sum(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).sum();
      }
      static long sumInts(Collection<? extends Number> xs) {
        return xs.stream().mapToLong(Number::longValue).sum();
      }
      static double mean(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).average().orElse(Double.NaN);
      }
      static double max(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).max().orElse(Double.NaN);
      }
      static double min(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).min().orElse(Double.NaN);
      }

      static String join(String sep, Collection<?> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(sep));
      }
      static <T> List<T> filter(Collection<T> xs, Predicate<? super T> p) {
        return xs.stream().filter(p).collect(Collectors.toList());
      }
      static <T, R> List<R> map(Collection<T> xs, Function<? super T, ? extends R> f) {
        return xs.stream().map(f).collect(Collectors.toList());
      }
      static <T extends Comparable<T>> List<T> sorted(Collection<T> xs) {
        return xs.stream().sorted().collect(Collectors.toList());
      }
      static <T, K> Map<K, Long> countBy(Collection<T> xs, Function<? super T, ? extends K> key) {
        return xs.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
      }
      """;

  /**
   * Install the preamble into a JShell instance. Splits {@link #SNIPPET} into completion units via
   * {@link jdk.jshell.SourceCodeAnalysis} and evaluates each one in order. Stops (without throwing)
   * on the first incomplete unit, which would only happen if the snippet had a typo — covered by
   * unit tests.
   *
   * @param jshell the JShell instance to install into
   */
  public static void install(JShell jshell) {
    var sca = jshell.sourceCodeAnalysis();
    var remaining = SNIPPET;
    while (!remaining.isBlank()) {
      var info = sca.analyzeCompletion(remaining);
      if (!info.completeness().isComplete()) {
        return;
      }
      jshell.eval(info.source());
      remaining = info.remaining();
    }
  }

  /**
   * One-line model-facing summary of what the preamble offers — for inclusion in the system prompt.
   * Returns the helper signatures only (not the bodies) so the LLM sees the surface.
   */
  public static String modelFacingSummary() {
    return """
        Pre-installed in every execute_code call (no imports needed):
          - Standard imports: java.util.*, java.util.stream.*, java.util.function.*, \
        java.util.regex.*, java.io.*, java.math.*, java.time.*, Collectors
          - print(x), println(), println(x), printf(fmt, args...)  — no need for System.out
          - Numeric reductions over any Collection<? extends Number>:
              sum(xs) -> double, sumInts(xs) -> long, mean(xs) -> double, max(xs) -> double, \
        min(xs) -> double
          - Collection helpers:
              join(sep, xs) -> String
              filter(xs, predicate) -> List
              map(xs, function) -> List
              sorted(xs) -> List      (xs must be Comparable)
              countBy(xs, keyFn) -> Map<K, Long>
        Use these instead of writing the equivalent .stream().collect(...) chains by hand.\
        """;
  }
}
