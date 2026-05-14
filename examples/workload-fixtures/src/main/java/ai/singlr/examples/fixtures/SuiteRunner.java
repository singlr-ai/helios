/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.examples.fixtures.tasks.ClassificationFixture;
import ai.singlr.examples.fixtures.tasks.NumericStatsFixture;
import ai.singlr.examples.fixtures.tasks.UserTypedSdtmFixture;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entrypoint: runs the configured fixture × provider matrix and writes {@code pass.jsonl} + {@code
 * pass.md} under {@code --out}. Deliberately a {@code main}, not a JUnit test — real-API calls are
 * not appropriate for CI.
 *
 * <p>Cost discipline: every fixture × provider × attempt round trip is a real LLM call. The whole
 * default pass (3 fixtures × 1 provider × 1 rep) should cost cents on Gemini Flash. With {@code
 * --reps 3} it's still under a dollar. Re-running the suite is intentional — we want N>1 variance
 * numbers — but it should be a deliberate action.
 */
public final class SuiteRunner {

  private SuiteRunner() {}

  public static void main(String[] args) throws IOException {
    var parsed = Args.parse(args);
    System.out.println("workload-fixtures: " + parsed);
    var providers = buildProviderModels(parsed.providers);
    var fixtures = selectFixtures(parsed.fixtures);
    var outDir = Path.of(parsed.outDir);
    Files.createDirectories(outDir);
    var jsonlPath = outDir.resolve("pass.jsonl");
    var markdownPath = outDir.resolve("pass.md");

    var allMetrics = new ArrayList<Metrics>();
    try (BufferedWriter w = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8)) {
      for (var fixture : fixtures) {
        for (var entry : providers.entrySet()) {
          System.out.println(
              "running fixture="
                  + fixture.name()
                  + " model="
                  + entry.getKey()
                  + " reps="
                  + parsed.reps);
          var results = FixtureRunner.run(fixture, entry.getValue(), parsed.reps);
          for (var m : results) {
            w.write(JsonWriter.toJson(m));
            w.newLine();
          }
          allMetrics.addAll(results);
        }
      }
    }

    var report = ReportWriter.render(fixtures, allMetrics, parsed.baseline);
    Files.writeString(markdownPath, report, StandardCharsets.UTF_8);

    System.out.println(
        "pass complete: " + allMetrics.size() + " attempts; " + jsonlPath + "; " + markdownPath);
  }

  static Map<String, Model> buildProviderModels(List<String> providerNames) {
    var result = new LinkedHashMap<String, Model>();
    for (var name : providerNames) {
      switch (name) {
        case "gemini" -> result.put(name, geminiModel());
        default ->
            throw new IllegalArgumentException(
                "unsupported provider for this release: "
                    + name
                    + " (only 'gemini' is wired in v1)");
      }
    }
    return result;
  }

  private static Model geminiModel() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("GEMINI_API_KEY env var is required");
    }
    var cfg = ModelConfig.newBuilder().withApiKey(apiKey).build();
    return new GeminiProvider().create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), cfg);
  }

  static List<Fixture> selectFixtures(List<String> requested) {
    var all =
        List.<Fixture>of(
            new NumericStatsFixture(), new UserTypedSdtmFixture(), new ClassificationFixture());
    if (requested.isEmpty()) {
      return all;
    }
    var byName = new LinkedHashMap<String, Fixture>();
    for (var f : all) {
      byName.put(f.name(), f);
    }
    var out = new ArrayList<Fixture>(requested.size());
    for (var name : requested) {
      var f = byName.get(name);
      if (f == null) {
        throw new IllegalArgumentException(
            "unknown fixture: " + name + "; valid: " + String.join(",", byName.keySet()));
      }
      out.add(f);
    }
    return out;
  }

  /** Parsed CLI args. Public for testing. */
  public record Args(
      List<String> providers, List<String> fixtures, int reps, String outDir, Path baseline) {

    static Args parse(String[] args) {
      var providers = List.of("gemini");
      List<String> fixtures = List.of();
      int reps = 1;
      String outDir =
          "reports/"
              + OffsetDateTime.now()
                  .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT));
      Path baseline = null;
      for (int i = 0; i < args.length; i++) {
        var arg = args[i];
        switch (arg) {
          case "--providers" -> providers = splitCsv(requireNext(args, i++));
          case "--fixtures" -> fixtures = splitCsv(requireNext(args, i++));
          case "--reps" -> reps = Integer.parseInt(requireNext(args, i++));
          case "--out" -> outDir = requireNext(args, i++);
          case "--baseline" -> baseline = Path.of(requireNext(args, i++));
          default -> throw new IllegalArgumentException("unknown arg: " + arg);
        }
      }
      if (reps < 1) {
        throw new IllegalArgumentException("--reps must be >= 1; got " + reps);
      }
      return new Args(providers, fixtures, reps, outDir, baseline);
    }

    private static String requireNext(String[] args, int currentIdx) {
      if (currentIdx + 1 >= args.length) {
        throw new IllegalArgumentException("missing value after " + args[currentIdx]);
      }
      return args[currentIdx + 1];
    }

    private static List<String> splitCsv(String csv) {
      return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
  }
}
