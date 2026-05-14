/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

  private static AgentConfig configWith(Model model) {
    return AgentConfig.newBuilder()
        .withName("eval-test")
        .withModel(model)
        .withIncludeMemoryTools(false)
        .build();
  }

  @Test
  void happyPath() {
    var model = new MockModel("world");
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withDataset(List.of(Example.of("hi", "world"), Example.of("bye", "world")))
            .withMetric(Metric.exactMatch())
            .build();

    var result = evaluator.run();
    assertEquals(1.0, result.meanScore());
    assertEquals(2, result.perExample().size());
    assertEquals("world", result.perExample().get(0).actual());
    assertNotNull(result.perExample().get(0).outcome());
  }

  @Test
  void metricScoresPartial() {
    var model = new MockModel("world");
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withDataset(List.of(Example.of("a", "world"), Example.of("b", "other")))
            .withMetric(Metric.exactMatch())
            .build();

    assertEquals(0.5, evaluator.run().meanScore());
  }

  @Test
  void agentFailureScoresZero() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model boom");
          }

          @Override
          public String id() {
            return "failing";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withDataset(List.of(Example.of("hi", "x")))
            .withMetric(Metric.exactMatch())
            .build();

    var result = evaluator.run();
    assertEquals(0.0, result.meanScore());
    assertFalse(result.perExample().get(0).outcome().isSuccess());
    assertNull(result.perExample().get(0).actual());
  }

  @Test
  void parallelismRunsConcurrently() {
    var counter = new AtomicInteger();
    var maxConcurrent = new AtomicInteger();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            int now = counter.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
              Thread.sleep(30);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            counter.decrementAndGet();
            return Response.newBuilder()
                .withContent("ok")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "m";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var dataset = List.of(Example.of("a", "ok"), Example.of("b", "ok"), Example.of("c", "ok"));
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withDataset(dataset)
            .withMetric(Metric.exactMatch())
            .withParallelism(3)
            .build();

    var result = evaluator.run();
    assertEquals(1.0, result.meanScore());
    assertTrue(maxConcurrent.get() >= 2, "expected concurrent runs, saw max=" + maxConcurrent);
  }

  @Test
  void serialWhenParallelismOne() {
    var counter = new AtomicInteger();
    var maxConcurrent = new AtomicInteger();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            int now = counter.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            counter.decrementAndGet();
            return Response.newBuilder()
                .withContent("ok")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "m";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var dataset = List.of(Example.of("a", "ok"), Example.of("b", "ok"));
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withDataset(dataset)
            .withMetric(Metric.exactMatch())
            .withParallelism(1)
            .build();

    evaluator.run();
    assertEquals(1, maxConcurrent.get());
  }

  @Test
  void customInputMapper() {
    var model = new MockModel("ok");
    var captured = new java.util.concurrent.atomic.AtomicReference<String>();
    var evaluator =
        Evaluator.<Integer, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withDataset(List.of(Example.of(42, "ok")))
            .withMetric(Metric.exactMatch())
            .withInputMapper(
                i -> {
                  var text = "num=" + i;
                  captured.set(text);
                  return SessionContext.of(text);
                })
            .build();
    evaluator.run();
    assertEquals("num=42", captured.get());
  }

  @Test
  void exampleAddedViaBuilder() {
    var model = new MockModel("ok");
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(model))
            .withInputMapper(SessionContext::of)
            .withExample(Example.of("a", "ok"))
            .withExample(Example.of("b", "ok"))
            .withMetric(Metric.exactMatch())
            .build();
    assertEquals(2, evaluator.run().perExample().size());
  }

  @Test
  void builderRejectsNullConfig() {
    var b =
        Evaluator.<String, String>newBuilder()
            .withMetric(Metric.exactMatch())
            .withInputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsNullMetric() {
    var b =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(new MockModel("x")))
            .withInputMapper(SessionContext::of);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsBadParallelism() {
    var b =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(new MockModel("x")))
            .withMetric(Metric.exactMatch())
            .withInputMapper(SessionContext::of)
            .withParallelism(0);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsMissingInputMapper() {
    var b =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(new MockModel("x")))
            .withMetric(Metric.exactMatch());
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void emptyDatasetReturnsZeroMean() {
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(new MockModel("x")))
            .withInputMapper(SessionContext::of)
            .withDataset(List.of())
            .withMetric(Metric.exactMatch())
            .build();
    var result = evaluator.run();
    assertEquals(0.0, result.meanScore());
    assertTrue(result.perExample().isEmpty());
  }

  @Test
  void withDatasetReplacesPriorEntries() {
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(configWith(new MockModel("ok")))
            .withInputMapper(SessionContext::of)
            .withExample(Example.of("a", "ok"))
            .withDataset(List.of(Example.of("b", "ok")))
            .withMetric(Metric.exactMatch())
            .build();
    assertEquals(1, evaluator.run().perExample().size());
  }

  @Test
  void traceCaptured() {
    var model = new MockModel("ok");
    var evaluator =
        Evaluator.<String, String>newBuilder()
            .withAgentConfig(
                AgentConfig.newBuilder()
                    .withName("trace-test")
                    .withModel(model)
                    .withIncludeMemoryTools(false)
                    .withEventSink(t -> {})
                    .build())
            .withInputMapper(SessionContext::of)
            .withDataset(List.of(Example.of("a", "ok")))
            .withMetric(Metric.exactMatch())
            .build();
    var result = evaluator.run();
    assertNotNull(result.perExample().get(0).trace());
  }
}
