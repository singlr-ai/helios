/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import ai.singlr.core.eval.ConfidenceScorer;
import ai.singlr.core.eval.ExperimentEntry;
import ai.singlr.core.eval.ExperimentLog;
import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Coach tools for the code autoresearch example. Five tools:
 *
 * <ul>
 *   <li>{@code read_file} — read a file inside the scope
 *   <li>{@code write_file} — overwrite a file inside the scope
 *   <li>{@code run_experiment} — execute the benchmark command; parse {@code METRIC name=value}
 *       lines from stdout
 *   <li>{@code log_experiment} — commit (keep) or revert (discard/crash); append to log
 *   <li>{@code show_log} — return the last N log entries for the agent's review
 * </ul>
 *
 * <p>The tools interact with the workspace via {@link GitWorkspace} — all git mutations are
 * confined to that class. The metric-parsing contract is {@code METRIC name=value} lines in stdout;
 * the primary metric is the one whose name matches {@code metricName}.
 */
public final class CodeCoachTools {

  private static final Pattern METRIC_LINE = Pattern.compile("^METRIC\\s+(\\S+)\\s*=\\s*(\\S+)$");

  private final Tool readFile;
  private final Tool writeFile;
  private final Tool runExperiment;
  private final Tool logExperiment;
  private final Tool showLog;

  private CodeCoachTools(Tool r, Tool w, Tool run, Tool log, Tool show) {
    this.readFile = r;
    this.writeFile = w;
    this.runExperiment = run;
    this.logExperiment = log;
    this.showLog = show;
  }

  public Tool readFile() {
    return readFile;
  }

  public Tool writeFile() {
    return writeFile;
  }

  public Tool runExperiment() {
    return runExperiment;
  }

  public Tool logExperiment() {
    return logExperiment;
  }

  public Tool showLog() {
    return showLog;
  }

  /**
   * Build the tool quintet.
   *
   * @param workspace git-backed workspace
   * @param scope files the agent may read and write
   * @param benchmarkCommand command argv that produces {@code METRIC name=value} lines
   * @param metricName name of the primary metric
   * @param benchmarkTimeout per-run timeout
   * @param log experiment log to append to
   * @param higherIsBetter {@code true} if higher primary-metric values are improvements
   * @param bestScore current best score reference
   * @return the constructed tool quintet
   */
  public static CodeCoachTools create(
      GitWorkspace workspace,
      List<Path> scope,
      List<String> benchmarkCommand,
      String metricName,
      Duration benchmarkTimeout,
      ExperimentLog log,
      boolean higherIsBetter,
      AtomicReference<Double> bestScore) {
    var lastRun = new AtomicReference<RunOutcome>();
    return new CodeCoachTools(
        readFileTool(workspace, scope),
        writeFileTool(workspace, scope),
        runExperimentTool(workspace, benchmarkCommand, benchmarkTimeout, metricName, lastRun),
        logExperimentTool(workspace, log, lastRun, bestScore, higherIsBetter),
        showLogTool(log));
  }

  private static Tool readFileTool(GitWorkspace workspace, List<Path> scope) {
    return Tool.newBuilder()
        .withName("read_file")
        .withDescription("Read a file in the workspace scope and return its contents.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("path")
                .withType(ParameterType.STRING)
                .withDescription("Path relative to workspace root")
                .withRequired(true)
                .build())
        .withExecutor(
            (args, ctx) -> {
              var resolved = resolveInScope(workspace, scope, asString(args, "path"));
              if (resolved == null) {
                return ToolResult.failure("path not in scope");
              }
              try {
                return ToolResult.success(Files.readString(resolved, StandardCharsets.UTF_8));
              } catch (IOException e) {
                return ToolResult.failure("read failed: " + e.getMessage());
              }
            })
        .build();
  }

  private static Tool writeFileTool(GitWorkspace workspace, List<Path> scope) {
    return Tool.newBuilder()
        .withName("write_file")
        .withDescription(
            "Overwrite a file in the workspace scope. Does not commit — changes stay in the"
                + " working tree until log_experiment is called.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("path")
                .withType(ParameterType.STRING)
                .withDescription("Path relative to workspace root")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("content")
                .withType(ParameterType.STRING)
                .withDescription("New file contents")
                .withRequired(true)
                .build())
        .withExecutor(
            (args, ctx) -> {
              var resolved = resolveInScope(workspace, scope, asString(args, "path"));
              if (resolved == null) {
                return ToolResult.failure("path not in scope");
              }
              var content = asString(args, "content");
              if (content == null) {
                return ToolResult.failure("content must not be null");
              }
              try {
                Files.createDirectories(resolved.getParent());
                Files.writeString(resolved, content, StandardCharsets.UTF_8);
                return ToolResult.success("wrote " + resolved.toString());
              } catch (IOException e) {
                return ToolResult.failure("write failed: " + e.getMessage());
              }
            })
        .build();
  }

  private static Tool runExperimentTool(
      GitWorkspace workspace,
      List<String> command,
      Duration timeout,
      String metricName,
      AtomicReference<RunOutcome> lastRun) {
    return Tool.newBuilder()
        .withName("run_experiment")
        .withDescription(
            """
            Execute the benchmark command against the current working tree. Parses lines \
            matching "METRIC name=value" from stdout; the value whose name matches the \
            configured primary metric is returned. Must be followed by log_experiment to \
            commit (keep) or revert (discard/crash) the changes.""")
        .withExecutor(
            (args, ctx) -> {
              var result = workspace.exec(command, timeout);
              var metrics = parseMetrics(result.stdout());
              var primary = metrics.get(metricName);
              var outcome = new RunOutcome(primary, metrics, result);
              lastRun.set(outcome);
              if (primary == null) {
                return ToolResult.success(
                    "exit="
                        + result.exitCode()
                        + " (no METRIC "
                        + metricName
                        + " in output)\nstdout:\n"
                        + truncate(result.stdout()));
              }
              return ToolResult.success(
                  "exit="
                      + result.exitCode()
                      + " primary="
                      + metricName
                      + "="
                      + primary
                      + " secondary="
                      + secondaryWithoutPrimary(metrics, metricName)
                      + " duration="
                      + result.duration().toMillis()
                      + "ms");
            })
        .build();
  }

  private static Tool logExperimentTool(
      GitWorkspace workspace,
      ExperimentLog log,
      AtomicReference<RunOutcome> lastRun,
      AtomicReference<Double> bestScore,
      boolean higherIsBetter) {
    return Tool.newBuilder()
        .withName("log_experiment")
        .withDescription(
            """
            Finalize the last run_experiment. status=keep commits the working tree; \
            status=discard or status=crash reverts working-tree changes. Always provide \
            a short description and an 'asi' string-to-string map with free-form \
            diagnostics for your future self.""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("status")
                .withType(ParameterType.STRING)
                .withDescription("One of keep|discard|crash")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("description")
                .withType(ParameterType.STRING)
                .withDescription("Short summary of what this candidate changes")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("asi")
                .withType(ParameterType.OBJECT)
                .withDescription("ASI string-to-string map")
                .withRequired(false)
                .build())
        .withExecutor(
            (args, ctx) -> {
              ExperimentStatus status;
              try {
                status = ExperimentStatus.fromWire(asString(args, "status"));
              } catch (IllegalArgumentException e) {
                return ToolResult.failure("status must be one of keep|discard|crash");
              }
              var rawDescription = asString(args, "description");
              var description = rawDescription == null ? "" : rawDescription;
              var asi = asStringMap(args.get("asi"));

              var run = lastRun.get();
              double primary = run == null || run.primary == null ? 0.0 : run.primary;
              Map<String, Double> secondary =
                  run == null ? Map.of() : secondaryWithoutPrimary(run.metrics, null);

              return switch (status) {
                case KEEP ->
                    handleKeep(
                        workspace,
                        log,
                        primary,
                        secondary,
                        description,
                        asi,
                        bestScore,
                        higherIsBetter);
                case DISCARD, CRASH -> {
                  workspace.discardWorkingChanges();
                  appendEntry(log, status, primary, secondary, description, asi);
                  yield ToolResult.success(formatLogResult(status, log));
                }
              };
            })
        .build();
  }

  private static ToolResult handleKeep(
      GitWorkspace workspace,
      ExperimentLog log,
      double primary,
      Map<String, Double> secondary,
      String description,
      Map<String, String> asi,
      AtomicReference<Double> bestScore,
      boolean higherIsBetter) {
    if (isImprovement(primary, bestScore.get(), higherIsBetter)) {
      bestScore.set(primary);
    }
    try {
      workspace.commit("autoresearch: " + description);
    } catch (Exception e) {
      appendEntry(
          log,
          ExperimentStatus.CRASH,
          primary,
          secondary,
          description,
          augment(asi, "commit_error", e.getMessage()));
      workspace.discardWorkingChanges();
      return ToolResult.success("commit failed, changes reverted: " + e.getMessage());
    }
    appendEntry(log, ExperimentStatus.KEEP, primary, secondary, description, asi);
    return ToolResult.success(formatLogResult(ExperimentStatus.KEEP, log));
  }

  private static boolean isImprovement(
      double candidate, Double currentBest, boolean higherIsBetter) {
    if (currentBest == null) {
      return true;
    }
    return higherIsBetter ? candidate > currentBest : candidate < currentBest;
  }

  private static String formatLogResult(ExperimentStatus status, ExperimentLog log) {
    var confidence = ConfidenceScorer.score(log);
    return "logged status="
        + status.wire()
        + (confidence == null ? "" : " confidence=" + String.format("%.2f", confidence));
  }

  private static Tool showLogTool(ExperimentLog log) {
    return Tool.newBuilder()
        .withName("show_log")
        .withDescription("Return the last N experiment log entries.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("limit")
                .withType(ParameterType.INTEGER)
                .withDescription("How many entries to return (default 10)")
                .withRequired(false)
                .build())
        .withExecutor(
            (args, ctx) -> {
              int limit = 10;
              if (args.get("limit") instanceof Number n) {
                limit = Math.max(1, n.intValue());
              }
              var entries = log.entries();
              var start = Math.max(0, entries.size() - limit);
              return ToolResult.success(render(entries.subList(start, entries.size())));
            })
        .build();
  }

  private static Map<String, Double> parseMetrics(String stdout) {
    var out = new LinkedHashMap<String, Double>();
    for (var line : stdout.split("\\R")) {
      var m = METRIC_LINE.matcher(line.trim());
      if (m.matches()) {
        try {
          out.put(m.group(1), Double.parseDouble(m.group(2)));
        } catch (NumberFormatException ignored) {
          // skip malformed
        }
      }
    }
    return out;
  }

  private static Map<String, Double> secondaryWithoutPrimary(
      Map<String, Double> metrics, String primaryKey) {
    var out = new LinkedHashMap<String, Double>(metrics);
    if (primaryKey != null) {
      out.remove(primaryKey);
    }
    return out;
  }

  private static String truncate(String s) {
    if (s.length() <= 2000) {
      return s;
    }
    return s.substring(0, 2000) + "\n... (truncated)";
  }

  private static Path resolveInScope(GitWorkspace workspace, List<Path> scope, String path) {
    if (path == null) {
      return null;
    }
    var root = workspace.root();
    var lexical = root.resolve(path).normalize();
    if (!lexical.startsWith(root)) {
      return null;
    }
    Path real;
    try {
      if (Files.exists(lexical)) {
        real = lexical.toRealPath();
      } else {
        var parent = lexical.getParent();
        if (parent == null || !Files.exists(parent)) {
          real = lexical;
        } else {
          real = parent.toRealPath().resolve(lexical.getFileName());
        }
      }
    } catch (IOException e) {
      return null;
    }
    if (!real.startsWith(root)) {
      return null;
    }
    for (var allowed : scope) {
      Path allowedReal;
      try {
        var lex = root.resolve(allowed).normalize();
        allowedReal = Files.exists(lex) ? lex.toRealPath() : lex;
      } catch (IOException e) {
        continue;
      }
      if (real.equals(allowedReal)
          || (Files.isDirectory(allowedReal) && real.startsWith(allowedReal))) {
        return real;
      }
    }
    return null;
  }

  private static String asString(Map<String, Object> args, String key) {
    return args.get(key) instanceof String s ? s : null;
  }

  private static Map<String, String> asStringMap(Object raw) {
    if (raw instanceof Map<?, ?> map) {
      var out = new HashMap<String, String>();
      for (var entry : map.entrySet()) {
        if (entry.getKey() instanceof String key) {
          out.put(key, String.valueOf(entry.getValue()));
        }
      }
      return Map.copyOf(out);
    }
    return Map.of();
  }

  private static Map<String, String> augment(Map<String, String> asi, String key, String value) {
    var m = new HashMap<>(asi);
    m.put(key, value == null ? "" : value);
    return Map.copyOf(m);
  }

  private static void appendEntry(
      ExperimentLog log,
      ExperimentStatus status,
      double primary,
      Map<String, Double> secondary,
      String description,
      Map<String, String> asi) {
    var confidence = ConfidenceScorer.score(log);
    log.append(
        ExperimentEntry.newBuilder()
            .withSegment(log.currentSegment())
            .withStatus(status)
            .withPrimaryMetric(primary)
            .withSecondaryMetrics(secondary)
            .withDescription(description)
            .withAsi(asi)
            .withConfidence(confidence)
            .build());
  }

  private static String render(List<ExperimentEntry> entries) {
    if (entries.isEmpty()) {
      return "(log empty)";
    }
    var sb = new StringBuilder();
    for (var e : entries) {
      sb.append('[')
          .append(e.status().wire())
          .append("] metric=")
          .append(e.primaryMetric())
          .append(" desc=")
          .append(e.description());
      if (!e.asi().isEmpty()) {
        sb.append(" asi=").append(e.asi());
      }
      if (e.confidence() != null) {
        sb.append(" confidence=").append(e.confidence());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private record RunOutcome(
      Double primary, Map<String, Double> metrics, GitWorkspace.CommandResult result) {}
}
