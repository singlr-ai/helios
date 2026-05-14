/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.eval.Example;
import ai.singlr.core.eval.FeedbackMetric;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class GepaPromptOptimizerTest {

  /**
   * Student model whose response is the system prompt verbatim. Lets a test "score" by checking
   * whether the prompt contains the expected substring — every prompt is its own answer key.
   */
  private static Model echoStudent() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        var systemPrompt = messages.get(0).content();
        return Response.newBuilder()
            .withContent(systemPrompt)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "echo";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  /**
   * Reflection model whose nth response is {@code prompts[n]}. Reads round-robin past the end so
   * the test never crashes when the optimizer runs more iterations than the script anticipates.
   */
  private static Model scriptedReflection(String... prompts) {
    var iter =
        new Iterator<String>() {
          int i = 0;

          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public String next() {
            return prompts[i++ % prompts.length];
          }
        };
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(iter.next())
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "reflection";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  /** Score 1.0 if {@code actual} contains {@code expected}; feedback names the miss otherwise. */
  private static FeedbackMetric<String, String> containsMetric() {
    return (expected, actual, trace) -> {
      if (actual == null) {
        return FeedbackMetric.Result.of(
            0.0, "actual was null; expected to contain '" + expected + "'");
      }
      return actual.contains(expected)
          ? FeedbackMetric.Result.of(1.0, "matched")
          : FeedbackMetric.Result.of(0.0, "expected '" + expected + "' missing from output");
    };
  }

  private static AgentConfig studentConfig(String seedPrompt) {
    return AgentConfig.newBuilder()
        .withName("gepa-student")
        .withModel(echoStudent())
        .withSystemPrompt(seedPrompt)
        .withIncludeMemoryTools(false)
        .withMaxIterations(1)
        .build();
  }

  private static List<Example<String, String>> dataset(String... labels) {
    var out = new ArrayList<Example<String, String>>();
    for (var i = 0; i < labels.length; i++) {
      out.add(Example.of("q" + i, labels[i]));
    }
    return out;
  }

  @Test
  void builderRequiresStudent() {
    var b = GepaPromptOptimizer.<String, String>builder();
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRequiresNonBlankSystemPrompt() {
    var student =
        AgentConfig.newBuilder()
            .withName("s")
            .withModel(echoStudent())
            .withSystemPrompt(" ")
            .build();
    var b =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(student)
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsEmptyDatasets() {
    var b1 =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .valSet(dataset("alpha"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b1::build);
    var b2 =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .trainSet(dataset("alpha"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b2::build);
  }

  @Test
  void builderRejectsMutuallyExclusiveBudgetAndMaxIterations() {
    var b =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .trainSet(dataset("a"))
            .valSet(dataset("a"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of)
            .budget(AutoBudget.LIGHT)
            .maxIterations(3);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRequiresEitherReflectionLmOrMutator() {
    var b =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .trainSet(dataset("a"))
            .valSet(dataset("a"))
            .metric(containsMetric())
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsInvalidNumerics() {
    var common =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .trainSet(dataset("a"))
            .valSet(dataset("a"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, () -> common.minibatchSize(0).build());
    var common2 =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("seed"))
            .trainSet(dataset("a"))
            .valSet(dataset("a"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("p"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, () -> common2.parallelism(0).build());
  }

  @Test
  void seedEntersFrontierWithExpectedScores() {
    var log = new InMemoryExperimentLog();
    var opt =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("alpha"))
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha", "beta"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha beta"))
            .experimentLog(log)
            .inputMapper(SessionContext::of)
            .maxIterations(0) // seed only
            .build();
    var result = opt.optimize();
    assertEquals(0, result.iterationsRun());
    // seed prompt "alpha" matches example labelled "alpha" but not "beta": one of two.
    assertEquals(1.0, result.bestAggregateScore());
    assertEquals(1, log.entries().size(), "exactly the seed entry is logged when iterations=0");
  }

  @Test
  void monotonicImprovementOverThreeIterations() {
    var log = new InMemoryExperimentLog();
    var opt =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("neutral"))
            .trainSet(dataset("alpha", "beta"))
            .valSet(dataset("alpha", "beta", "gamma"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha", "alpha beta", "alpha beta gamma"))
            .experimentLog(log)
            .inputMapper(SessionContext::of)
            .maxIterations(3)
            .minibatchSize(1)
            .build();
    var result = opt.optimize();
    // Final iteration prompt covers all three labels.
    assertEquals(3.0, result.bestAggregateScore());
    assertEquals("alpha beta gamma", result.bestPrompt());
    assertEquals(3, result.iterationsRun());
    // 1 seed entry + 3 iteration entries.
    assertEquals(4, log.entries().size());
  }

  @Test
  void eventSinkSeesCorrectEventSequence() {
    var events = new CopyOnWriteArrayList<HeliosEvent>();
    GepaPromptOptimizer.<String, String>builder()
        .studentConfig(studentConfig("alpha"))
        .trainSet(dataset("alpha"))
        .valSet(dataset("alpha"))
        .metric(containsMetric())
        .reflectionLm(scriptedReflection("alpha"))
        .experimentLog(new InMemoryExperimentLog())
        .inputMapper(SessionContext::of)
        .eventSink(events::add)
        .maxIterations(1)
        .minibatchSize(1)
        .build()
        .optimize();

    // We expect optimizer.* lifecycle events plus per-candidate Proposed/Scored, in addition to the
    // run-lifecycle events that the underlying Evaluator's Agent emits.
    var optimizerEvents =
        events.stream()
            .filter(
                e ->
                    e instanceof HeliosEvent.Custom
                        || e instanceof HeliosEvent.OptimizerCandidateProposed
                        || e instanceof HeliosEvent.OptimizerCandidateScored)
            .toList();
    assertTrue(
        optimizerEvents.get(0) instanceof HeliosEvent.Custom c
            && "optimizer.started".equals(c.kind()),
        "first optimizer event is optimizer.started");
    assertTrue(
        optimizerEvents.get(optimizerEvents.size() - 1) instanceof HeliosEvent.Custom c
            && "optimizer.completed".equals(c.kind()),
        "last optimizer event is optimizer.completed");

    var proposedCount =
        optimizerEvents.stream()
            .filter(HeliosEvent.OptimizerCandidateProposed.class::isInstance)
            .count();
    var scoredCount =
        optimizerEvents.stream()
            .filter(HeliosEvent.OptimizerCandidateScored.class::isInstance)
            .count();
    // 1 seed + 1 iteration child = 2 of each.
    assertEquals(2, proposedCount);
    assertEquals(2, scoredCount);
  }

  @Test
  void experimentLogAppendedOncePerIteration() {
    var log = new InMemoryExperimentLog();
    GepaPromptOptimizer.<String, String>builder()
        .studentConfig(studentConfig("seed"))
        .trainSet(dataset("alpha", "beta"))
        .valSet(dataset("alpha", "beta"))
        .metric(containsMetric())
        .reflectionLm(scriptedReflection("alpha", "alpha beta"))
        .experimentLog(log)
        .inputMapper(SessionContext::of)
        .maxIterations(2)
        .minibatchSize(1)
        .build()
        .optimize();
    // 1 seed segment + 2 iteration segments.
    assertEquals(3, log.entries().size());
    assertEquals(0, log.entries().get(0).segment());
    assertEquals(1, log.entries().get(1).segment());
    assertEquals(2, log.entries().get(2).segment());
  }

  @Test
  void sameSeedProducesSameLineage() {
    var seed = 12345L;
    var run1Ids = runAndCollectIds(seed);
    var run2Ids = runAndCollectIds(seed);
    // The seed candidate's UUID is a fresh Ids.newId(), which is UUID v7 (time-ordered) and
    // therefore differs across runs even with the same RNG seed. What we DO get for free from a
    // deterministic seed is identical *control flow*: same parent sampled per iteration, same
    // minibatch, same reflection-call ordering. Verify control flow by comparing lineage size +
    // best score, which depend on the path through the rng.
    var run1Score = runAndScore(seed);
    var run2Score = runAndScore(seed);
    assertEquals(run1Score, run2Score);
    assertEquals(run1Ids.size(), run2Ids.size());
  }

  private static List<java.util.UUID> runAndCollectIds(long seed) {
    var ids = new ArrayList<java.util.UUID>();
    GepaPromptOptimizer.<String, String>builder()
        .studentConfig(studentConfig("neutral"))
        .trainSet(dataset("alpha", "beta", "gamma"))
        .valSet(dataset("alpha", "beta"))
        .metric(containsMetric())
        .reflectionLm(scriptedReflection("alpha", "alpha beta"))
        .experimentLog(new InMemoryExperimentLog())
        .inputMapper(SessionContext::of)
        .seed(seed)
        .maxIterations(2)
        .minibatchSize(1)
        .eventSink(
            event -> {
              if (event instanceof HeliosEvent.OptimizerCandidateProposed p) {
                ids.add(p.candidateId());
              }
            })
        .build()
        .optimize();
    return ids;
  }

  private static double runAndScore(long seed) {
    return GepaPromptOptimizer.<String, String>builder()
        .studentConfig(studentConfig("neutral"))
        .trainSet(dataset("alpha", "beta", "gamma"))
        .valSet(dataset("alpha", "beta"))
        .metric(containsMetric())
        .reflectionLm(scriptedReflection("alpha", "alpha beta"))
        .experimentLog(new InMemoryExperimentLog())
        .inputMapper(SessionContext::of)
        .seed(seed)
        .maxIterations(2)
        .minibatchSize(1)
        .build()
        .optimize()
        .bestAggregateScore();
  }

  @Test
  void resultLineageContainsEveryCandidate() {
    var result =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("neutral"))
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of)
            .maxIterations(1)
            .minibatchSize(1)
            .build()
            .optimize();
    // 1 seed + 1 iteration child.
    assertEquals(2, result.lineage().size());
  }

  @Test
  void customMutatorPathSkipsReflectionLm() {
    var counter = new int[1];
    var log = new InMemoryExperimentLog();
    GepaPromptOptimizer.<String, String>builder()
        .studentConfig(studentConfig("alpha"))
        .trainSet(dataset("alpha"))
        .valSet(dataset("alpha"))
        .metric(containsMetric())
        .mutator(
            (parent, traces) -> {
              counter[0]++;
              return "alpha-" + counter[0];
            })
        .experimentLog(log)
        .inputMapper(SessionContext::of)
        .maxIterations(2)
        .minibatchSize(1)
        .build()
        .optimize();
    assertEquals(2, counter[0], "custom mutator invoked once per iteration");
  }

  @Test
  void autoBudgetMediumIsDefault() {
    var opt =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("alpha"))
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha", "beta"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of)
            .build();
    assertEquals(AutoBudget.MEDIUM.maxIterations(2, 1), opt.resolvedMaxIterations());
  }

  @Test
  void explicitMaxIterationsIgnoresAutoBudget() {
    var opt =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("alpha"))
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha", "beta"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of)
            .maxIterations(7)
            .build();
    assertEquals(7, opt.resolvedMaxIterations());
    assertEquals(Integer.MAX_VALUE, opt.resolvedMaxMetricCalls());
  }

  @Test
  void resultExposesFrontierAndBestPrompt() {
    var result =
        GepaPromptOptimizer.<String, String>builder()
            .studentConfig(studentConfig("alpha"))
            .trainSet(dataset("alpha"))
            .valSet(dataset("alpha"))
            .metric(containsMetric())
            .reflectionLm(scriptedReflection("alpha beta"))
            .experimentLog(new InMemoryExperimentLog())
            .inputMapper(SessionContext::of)
            .maxIterations(1)
            .minibatchSize(1)
            .build()
            .optimize();
    assertNotNull(result.frontier());
    assertNotNull(result.bestPrompt());
    assertSame(result.frontier(), result.frontier(), "frontier returned by accessor is stable");
  }
}
