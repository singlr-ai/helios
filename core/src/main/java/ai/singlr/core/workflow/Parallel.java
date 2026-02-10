/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * A step that runs child steps concurrently using virtual threads. All steps receive the same
 * context snapshot. If any step fails, the first failure is returned.
 *
 * <p>When a timeout is configured, it applies to the <b>total</b> parallel execution, not per step.
 *
 * @param name the step name
 * @param steps the steps to run concurrently
 * @param timeout maximum duration for all steps to complete, or null for no timeout
 */
public record Parallel(String name, List<Step> steps, Duration timeout) implements Step {

  public Parallel(String name, List<Step> steps) {
    this(name, List.copyOf(steps), null);
  }

  public Parallel {
    steps = List.copyOf(steps);
  }

  @Override
  public StepResult execute(StepContext context) {
    var results = new ArrayList<StepResult>(steps.size());
    var deadline = timeout != null ? Instant.now().plus(timeout) : null;
    var executor = Executors.newVirtualThreadPerTaskExecutor();

    try {
      var futures =
          steps.stream().map(step -> executor.submit(() -> step.execute(context))).toList();

      for (var future : futures) {
        if (deadline != null) {
          var remainingMs = Duration.between(Instant.now(), deadline).toMillis();
          if (remainingMs <= 0) {
            cancelAll(futures);
            return StepResult.failure(name, "Parallel execution timed out after " + timeout);
          }
          results.add(future.get(remainingMs, TimeUnit.MILLISECONDS));
        } else {
          results.add(future.get());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return StepResult.failure(name, "Parallel execution interrupted");
    } catch (ExecutionException e) {
      var cause = e.getCause();
      var message = cause != null ? cause.getMessage() : e.getMessage();
      return StepResult.failure(name, message != null ? message : "Unknown execution error");
    } catch (TimeoutException e) {
      return StepResult.failure(name, "Parallel execution timed out after " + timeout);
    } finally {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    for (var result : results) {
      if (!result.success()) {
        return result;
      }
    }

    var mergedContent =
        results.stream()
            .map(StepResult::content)
            .filter(c -> c != null)
            .collect(Collectors.joining("\n"));

    var mergedData = new LinkedHashMap<String, String>();
    for (var result : results) {
      mergedData.putAll(result.data());
    }

    return StepResult.success(name, mergedContent, mergedData);
  }

  private static void cancelAll(List<Future<StepResult>> futures) {
    for (var future : futures) {
      future.cancel(true);
    }
  }
}
