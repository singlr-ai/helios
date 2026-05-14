/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders the markdown summary of one pass. The report is small on purpose — a single table per
 * fixture, optionally with a delta-vs-baseline column. No HTML, no charts, no rollups across
 * passes.
 */
public final class ReportWriter {

  private static final double ITERATION_REGRESSION_THRESHOLD = 0.20;
  private static final double TOKEN_REGRESSION_THRESHOLD = 0.30;

  private ReportWriter() {}

  public static String render(List<Fixture> fixtures, List<Metrics> metrics, Path baseline)
      throws IOException {
    var baselineByCell = baseline == null ? Map.<String, Cell>of() : loadBaseline(baseline);
    var sb = new StringBuilder();
    sb.append("# Workload Fixtures Pass\n\n");
    sb.append("- Timestamp: ").append(OffsetDateTime.now()).append("\n");
    sb.append("- Fixtures: ").append(fixtures.size()).append("\n");
    sb.append("- Attempts: ").append(metrics.size()).append("\n");
    if (baseline != null) {
      sb.append("- Baseline: ").append(baseline).append("\n");
    }
    sb.append('\n');

    var byFixture = groupByFixture(fixtures, metrics);
    var regressions = new ArrayList<String>();
    for (var fixture : fixtures) {
      var rows = byFixture.get(fixture.name());
      if (rows == null || rows.isEmpty()) {
        continue;
      }
      sb.append("## ").append(fixture.name()).append("\n\n");
      sb.append("_").append(fixture.description()).append("_\n\n");
      renderTable(sb, rows, baselineByCell, fixture.name(), regressions);
      sb.append('\n');
    }

    if (!regressions.isEmpty()) {
      sb.append("## Regressions vs baseline\n\n");
      for (var note : regressions) {
        sb.append("- ").append(note).append('\n');
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private static Map<String, List<Metrics>> groupByFixture(
      List<Fixture> fixtures, List<Metrics> metrics) {
    var out = new LinkedHashMap<String, List<Metrics>>();
    for (var f : fixtures) {
      out.put(f.name(), new ArrayList<>());
    }
    for (var m : metrics) {
      out.computeIfAbsent(m.fixture(), k -> new ArrayList<>()).add(m);
    }
    return out;
  }

  private static void renderTable(
      StringBuilder sb,
      List<Metrics> rows,
      Map<String, Cell> baselineByCell,
      String fixtureName,
      List<String> regressions) {
    var byModel = new LinkedHashMap<String, List<Metrics>>();
    for (var m : rows) {
      byModel.computeIfAbsent(m.model(), k -> new ArrayList<>()).add(m);
    }
    var hasBaseline = !baselineByCell.isEmpty();
    sb.append("| Model | Pass rate | Avg iters | Avg tokens | Avg recovery | Avg ms |");
    if (hasBaseline) {
      sb.append(" Δ iters | Δ tokens |");
    }
    sb.append('\n');
    sb.append("|---|---|---|---|---|---|");
    if (hasBaseline) {
      sb.append("---|---|");
    }
    sb.append('\n');
    for (var entry : byModel.entrySet()) {
      var modelId = entry.getKey();
      var attempts = entry.getValue();
      var cell = aggregate(attempts);
      sb.append("| ").append(modelId).append(" | ");
      sb.append(formatPassRate(cell.passRate())).append(" | ");
      sb.append(formatDouble(cell.avgIters())).append(" | ");
      sb.append(cell.avgTokens()).append(" | ");
      sb.append(formatDouble(cell.avgRecovery())).append(" | ");
      sb.append(cell.avgMs()).append(" |");
      if (hasBaseline) {
        var key = fixtureName + "|" + modelId;
        var prior = baselineByCell.get(key);
        if (prior == null) {
          sb.append(" — | — |");
        } else {
          var iterDelta = delta(cell.avgIters(), prior.avgIters());
          var tokenDelta = delta(cell.avgTokens(), prior.avgTokens());
          sb.append(' ').append(formatDelta(iterDelta)).append(" |");
          sb.append(' ').append(formatDelta(tokenDelta)).append(" |");
          if (iterDelta > ITERATION_REGRESSION_THRESHOLD) {
            regressions.add(
                fixtureName + " × " + modelId + ": iterations +" + formatDelta(iterDelta));
          }
          if (tokenDelta > TOKEN_REGRESSION_THRESHOLD) {
            regressions.add(fixtureName + " × " + modelId + ": tokens +" + formatDelta(tokenDelta));
          }
        }
      }
      sb.append('\n');
    }
  }

  static Cell aggregate(List<Metrics> attempts) {
    int n = attempts.size();
    int passed = 0;
    double iters = 0;
    long tokens = 0;
    double recovery = 0;
    long ms = 0;
    for (var m : attempts) {
      if (m.passed()) {
        passed++;
      }
      iters += m.totalIterations();
      tokens += m.totalTokens();
      recovery += m.recoveryIterations();
      ms += m.durationMs();
    }
    return new Cell(
        (double) passed / n,
        iters / n,
        Math.round((double) tokens / n),
        recovery / n,
        Math.round((double) ms / n));
  }

  static double delta(double current, double prior) {
    if (prior == 0) {
      return current == 0 ? 0 : 1.0;
    }
    return (current - prior) / prior;
  }

  static String formatPassRate(double rate) {
    return String.format(Locale.ROOT, "%.0f%%", rate * 100);
  }

  static String formatDouble(double v) {
    return String.format(Locale.ROOT, "%.1f", v);
  }

  static String formatDelta(double v) {
    var pct = String.format(Locale.ROOT, "%+.0f%%", v * 100);
    if (v > ITERATION_REGRESSION_THRESHOLD || v > TOKEN_REGRESSION_THRESHOLD) {
      return "⚠️ " + pct;
    }
    return pct;
  }

  static Map<String, Cell> loadBaseline(Path baseline) throws IOException {
    var aggBuckets = new LinkedHashMap<String, List<Metrics>>();
    var lines = Files.readAllLines(baseline, StandardCharsets.UTF_8);
    var keys = new LinkedHashSet<String>();
    for (var line : lines) {
      if (line.isBlank()) {
        continue;
      }
      var m = BaselineParser.parse(line);
      var key = m.fixture() + "|" + m.model();
      keys.add(key);
      aggBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
    }
    var out = new LinkedHashMap<String, Cell>();
    for (var key : keys) {
      out.put(key, aggregate(aggBuckets.get(key)));
    }
    return out;
  }

  /** Aggregated per-(fixture,model) cell. Public for testing. */
  public record Cell(
      double passRate, double avgIters, long avgTokens, double avgRecovery, long avgMs) {}

  /**
   * Parses a single JSONL line back into Metrics for baseline loading. Hand-rolled, frozen shape.
   */
  static final class BaselineParser {

    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern BOOL_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

    private BaselineParser() {}

    static Metrics parse(String line) {
      var strings = new LinkedHashMap<String, String>();
      var numbers = new LinkedHashMap<String, Long>();
      var bools = new LinkedHashMap<String, Boolean>();
      var sm = STRING_FIELD.matcher(line);
      while (sm.find()) {
        strings.put(sm.group(1), sm.group(2));
      }
      var nm = NUMBER_FIELD.matcher(line);
      while (nm.find()) {
        numbers.put(nm.group(1), Long.parseLong(nm.group(2)));
      }
      var bm = BOOL_FIELD.matcher(line);
      while (bm.find()) {
        bools.put(bm.group(1), Boolean.parseBoolean(bm.group(2)));
      }
      return new Metrics(
          strings.getOrDefault("fixture", ""),
          strings.getOrDefault("model", ""),
          numbers.getOrDefault("attempt", 0L).intValue(),
          bools.getOrDefault("passed", false),
          numbers.getOrDefault("setupTurns", 0L).intValue(),
          numbers.getOrDefault("totalIterations", 0L).intValue(),
          numbers.getOrDefault("totalTokens", 0L).intValue(),
          numbers.getOrDefault("recoveryIterations", 0L).intValue(),
          numbers.getOrDefault("durationMs", 0L),
          List.of());
    }
  }
}
