/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.code;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.common.Result;
import ai.singlr.core.eval.ExperimentLog;
import ai.singlr.core.model.Model;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reference example: iteratively optimize source code in a local git repository. Inspired by
 * pi-autoresearch (karpathy/autoresearch lineage). Uses the {@code core/eval} primitives plus a
 * simple {@link GitWorkspace} checkpoint and a {@link CodeCoachTools} set.
 *
 * <p>The coach agent reads files in the declared scope, writes candidate edits, invokes the
 * user-supplied benchmark command, parses {@code METRIC name=value} lines from stdout, and either
 * commits (keep) or reverts (discard/crash) the change. Every iteration is appended to the {@link
 * ExperimentLog} with ASI diagnostics for resumption after a context reset.
 */
public final class CodeAutoresearch {

  private final Config config;
  private final GitWorkspace workspace;
  private final AtomicReference<Double> bestScore = new AtomicReference<>();

  private CodeAutoresearch(Config config) {
    this.config = config;
    this.workspace = new GitWorkspace(config.repoRoot);
  }

  public Outcome run() {
    var tools =
        CodeCoachTools.create(
            workspace,
            config.scope,
            config.benchmarkCommand,
            config.metricName,
            config.benchmarkTimeout,
            config.log,
            config.higherIsBetter,
            bestScore);
    var coachConfig =
        AgentConfig.newBuilder()
            .withName("code-coach")
            .withModel(config.coachModel)
            .withSystemPrompt(systemPrompt())
            .withTool(tools.readFile())
            .withTool(tools.writeFile())
            .withTool(tools.runExperiment())
            .withTool(tools.logExperiment())
            .withTool(tools.showLog())
            .withMaxIterations(config.maxIterations)
            .withIncludeMemoryTools(false)
            .build();
    var coach = new Agent(coachConfig);
    var outcome = coach.run(config.task);
    String lastMessage =
        switch (outcome) {
          case Result.Success<?> s when s.value() instanceof ai.singlr.core.model.Response<?> r ->
              r.content();
          default -> null;
        };
    return new Outcome(workspace.snapshot(), bestScore.get(), lastMessage, config.log.entries());
  }

  public String currentHead() {
    return workspace.snapshot();
  }

  public Double bestScore() {
    return bestScore.get();
  }

  private String systemPrompt() {
    var direction = config.higherIsBetter ? "higher is better" : "lower is better";
    return """
        You are a code optimizer. Your job is to make iterative edits to the files in \
        scope and keep only the changes that improve the benchmark metric.

        Task: %s

        Metric: %s (%s)

        Scope (paths relative to repo root):
        %s

        Workflow for every iteration:
          1. Read the files you want to modify with read_file.
          2. Write edits with write_file — this only touches the working tree, not git.
          3. Call run_experiment — it executes the benchmark and returns the parsed METRIC.
          4. Call log_experiment(status, description, asi) with status=keep if the \
             primary metric improved, status=discard otherwise. log_experiment commits on \
             keep and reverts working-tree changes on discard/crash.
          5. Never stop until the harness ends the session. When stuck, call show_log() to \
             remind yourself what you've already tried.
        """
        .formatted(config.task, config.metricName, direction, renderScope(config.scope));
  }

  private static String renderScope(List<Path> scope) {
    var sb = new StringBuilder();
    for (var p : scope) {
      sb.append("  - ").append(p).append('\n');
    }
    return sb.toString();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Final result of a {@link CodeAutoresearch#run()} call. */
  public record Outcome(
      String finalHead,
      Double bestScore,
      String coachFinalMessage,
      List<ai.singlr.core.eval.ExperimentEntry> log) {}

  record Config(
      Path repoRoot,
      Model coachModel,
      List<Path> scope,
      List<String> benchmarkCommand,
      String metricName,
      Duration benchmarkTimeout,
      ExperimentLog log,
      String task,
      int maxIterations,
      boolean higherIsBetter,
      boolean allowDirtyRepo) {}

  /** Builder for {@link CodeAutoresearch}. */
  public static final class Builder {

    private Path repoRoot;
    private Model coachModel;
    private final List<Path> scope = new ArrayList<>();
    private List<String> benchmarkCommand;
    private String metricName;
    private Duration benchmarkTimeout = Duration.ofMinutes(5);
    private ExperimentLog log;
    private String task;
    private int maxIterations = 25;
    private boolean higherIsBetter = true;
    private boolean allowDirtyRepo = false;

    private Builder() {}

    public Builder withRepoRoot(Path repoRoot) {
      this.repoRoot = repoRoot;
      return this;
    }

    public Builder withCoachModel(Model coachModel) {
      this.coachModel = coachModel;
      return this;
    }

    public Builder withScope(List<Path> scope) {
      this.scope.clear();
      this.scope.addAll(scope);
      return this;
    }

    public Builder withFile(Path file) {
      this.scope.add(file);
      return this;
    }

    public Builder withBenchmarkCommand(List<String> command) {
      this.benchmarkCommand = command;
      return this;
    }

    public Builder withMetricName(String metricName) {
      this.metricName = metricName;
      return this;
    }

    public Builder withBenchmarkTimeout(Duration timeout) {
      this.benchmarkTimeout = timeout;
      return this;
    }

    public Builder withLog(ExperimentLog log) {
      this.log = log;
      return this;
    }

    public Builder withTask(String task) {
      this.task = task;
      return this;
    }

    public Builder withMaxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    public Builder withHigherIsBetter(boolean higherIsBetter) {
      this.higherIsBetter = higherIsBetter;
      return this;
    }

    /**
     * Allow building even when the repository working tree has uncommitted changes. Off by default
     * — committing on keep and {@code git clean -fd} on discard are destructive, and we refuse to
     * run on a dirty repo unless the caller has explicitly opted in.
     *
     * @param allow {@code true} to skip the clean-working-tree check
     * @return this builder
     */
    public Builder withAllowDirtyRepo(boolean allow) {
      this.allowDirtyRepo = allow;
      return this;
    }

    public CodeAutoresearch build() {
      if (repoRoot == null) {
        throw new IllegalStateException("repoRoot must not be null");
      }
      if (coachModel == null) {
        throw new IllegalStateException("coachModel must not be null");
      }
      if (scope.isEmpty()) {
        throw new IllegalStateException("scope must not be empty");
      }
      if (benchmarkCommand == null || benchmarkCommand.isEmpty()) {
        throw new IllegalStateException("benchmarkCommand must not be empty");
      }
      if (metricName == null || metricName.isBlank()) {
        throw new IllegalStateException("metricName must not be blank");
      }
      if (log == null) {
        throw new IllegalStateException("log must not be null");
      }
      if (task == null || task.isBlank()) {
        throw new IllegalStateException("task must not be blank");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      if (!allowDirtyRepo) {
        assertCleanWorkingTree(repoRoot);
      }
      return new CodeAutoresearch(
          new Config(
              repoRoot,
              coachModel,
              List.copyOf(scope),
              List.copyOf(benchmarkCommand),
              metricName,
              benchmarkTimeout,
              log,
              task,
              maxIterations,
              higherIsBetter,
              allowDirtyRepo));
    }

    private static void assertCleanWorkingTree(Path repoRoot) {
      var workspace = new GitWorkspace(repoRoot);
      var result = workspace.exec(List.of("git", "status", "--porcelain"), Duration.ofSeconds(10));
      if (result.exitCode() != 0) {
        throw new IllegalStateException(
            "git status failed in " + repoRoot + ": " + result.stderr());
      }
      if (!result.stdout().isBlank()) {
        throw new IllegalStateException(
            "repoRoot has uncommitted changes; commit, stash, or pass withAllowDirtyRepo(true):\n"
                + result.stdout());
      }
    }
  }
}
