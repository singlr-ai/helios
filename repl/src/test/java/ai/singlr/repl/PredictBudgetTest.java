/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictBudgetTest {

  private static final Sandbox NOOP_SANDBOX =
      new Sandbox() {
        @Override
        public ExecutionResult execute(ExecutionRequest request) {
          return ExecutionResult.success("");
        }

        @Override
        public boolean isAlive() {
          return true;
        }

        @Override
        public void close() {}
      };

  private static HostFunction recordingPredict(AtomicInteger calls) {
    return new HostFunction(
        "predict",
        "test predict",
        params -> {
          calls.incrementAndGet();
          return Map.of("output", "ok");
        });
  }

  @Test
  void allowsCallsUpToCap() throws Exception {
    var calls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(recordingPredict(calls))
                .withMaxLlmCalls(3)
                .build(),
            new Semaphore(1));

    var predict = session.registry().get("predict");
    predict.handler().handle(Map.of("instructions", "x", "input", "y"));
    predict.handler().handle(Map.of("instructions", "x", "input", "y"));
    predict.handler().handle(Map.of("instructions", "x", "input", "y"));

    assertEquals(3, calls.get());
    assertEquals(3, session.predictCallCount());

    session.close();
  }

  @Test
  void throwsBudgetExceptionOnceCapPassed() throws Exception {
    var calls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(recordingPredict(calls))
                .withMaxLlmCalls(2)
                .build(),
            new Semaphore(1));

    var predict = session.registry().get("predict");
    predict.handler().handle(Map.of("instructions", "x", "input", "y"));
    predict.handler().handle(Map.of("instructions", "x", "input", "y"));

    var ex =
        assertThrows(
            SandboxBudgetExceededException.class,
            () -> predict.handler().handle(Map.of("instructions", "x", "input", "y")));

    assertSame(SandboxBudgetExceededException.BudgetKind.LLM_CALLS, ex.kind());
    assertEquals(2, ex.limit());
    assertEquals(3, ex.actual());
    assertTrue(ex.getMessage().contains("budget"));
    assertTrue(ex.getMessage().contains("submit()"), "message must guide model toward submit()");

    assertEquals(2, calls.get(), "inner predict must NOT be called once budget is exhausted");
    assertEquals(3, session.predictCallCount(), "counter records the failed attempt too");

    session.close();
  }

  @Test
  void zeroBudgetDisablesEnforcement() throws Exception {
    var calls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(recordingPredict(calls))
                .withMaxLlmCalls(0)
                .build(),
            new Semaphore(1));

    var predict = session.registry().get("predict");
    for (int i = 0; i < 100; i++) {
      predict.handler().handle(Map.of("instructions", "x", "input", "y"));
    }
    assertEquals(100, calls.get());
    // 1.1.5: predictCallCount reflects actual calls regardless of budget. The load-bearing
    // assertion is "no SandboxBudgetExceededException thrown" — verified by reaching this line.
    assertEquals(100, session.predictCallCount(), "count tracks actual predict calls");

    session.close();
  }

  @Test
  void budgetWrapsOnlyPredictNotOtherFunctions() throws Exception {
    var predictCalls = new AtomicInteger();
    var otherCalls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(recordingPredict(predictCalls))
                .withHostFunction(
                    new HostFunction(
                        "fetch",
                        "test fetch",
                        params -> {
                          otherCalls.incrementAndGet();
                          return Map.of("body", "ok");
                        }))
                .withMaxLlmCalls(1)
                .build(),
            new Semaphore(1));

    var fetch = session.registry().get("fetch");
    for (int i = 0; i < 10; i++) {
      fetch.handler().handle(Map.of("url", "https://x"));
    }
    assertEquals(10, otherCalls.get(), "fetch must not be budgeted");
    assertEquals(0, session.predictCallCount(), "fetch calls must not increment predict counter");

    session.close();
  }

  @Test
  void counterIsPerSession() throws Exception {
    var calls = new AtomicInteger();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> NOOP_SANDBOX)
            .withHostFunction(recordingPredict(calls))
            .withMaxLlmCalls(1)
            .build();
    var sem = new Semaphore(2);

    var s1 = ReplSession.create(config, sem);
    var s2 = ReplSession.create(config, sem);

    s1.registry().get("predict").handler().handle(Map.of("instructions", "a", "input", "b"));
    s2.registry().get("predict").handler().handle(Map.of("instructions", "a", "input", "b"));

    assertEquals(1, s1.predictCallCount());
    assertEquals(1, s2.predictCallCount());
    assertEquals(2, calls.get());

    assertThrows(
        SandboxBudgetExceededException.class,
        () ->
            s1.registry()
                .get("predict")
                .handler()
                .handle(Map.of("instructions", "a", "input", "b")));

    s1.close();
    s2.close();
  }

  @Test
  void registryShowsWrappedDescriptionUnchanged() throws Exception {
    var calls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(
                    new HostFunction(
                        "predict",
                        "Original description",
                        params -> {
                          calls.incrementAndGet();
                          return Map.of("output", "ok");
                        }))
                .withMaxLlmCalls(5)
                .build(),
            new Semaphore(1));

    var fn = session.registry().get("predict");
    assertNotNull(fn);
    assertEquals("predict", fn.name());
    assertEquals(
        "Original description",
        fn.description(),
        "wrapper preserves the user's host function description so the model sees the same surface");

    session.close();
  }

  @Test
  void exhaustedBudgetThrowsRepeatedlyWithIncreasingActual() throws Exception {
    var calls = new AtomicInteger();
    var session =
        ReplSession.create(
            ReplConfig.newBuilder()
                .withSandboxFactory(registry -> NOOP_SANDBOX)
                .withHostFunction(recordingPredict(calls))
                .withMaxLlmCalls(1)
                .withExecutionTimeout(Duration.ofSeconds(1))
                .build(),
            new Semaphore(1));

    var predict = session.registry().get("predict");
    predict.handler().handle(Map.of("instructions", "a", "input", "b"));

    var first =
        assertThrows(
            SandboxBudgetExceededException.class,
            () -> predict.handler().handle(Map.of("instructions", "a", "input", "b")));
    var second =
        assertThrows(
            SandboxBudgetExceededException.class,
            () -> predict.handler().handle(Map.of("instructions", "a", "input", "b")));

    assertEquals(2, first.actual());
    assertEquals(3, second.actual());
    assertEquals(1, calls.get(), "inner predict only succeeded once before the budget tripped");

    session.close();
  }
}
