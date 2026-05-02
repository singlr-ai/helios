/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.SubmitFunction;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReplSessionTest {

  private static ReplConfig configWith(Sandbox sandbox) {
    return ReplConfig.newBuilder()
        .withSandboxFactory(registry -> sandbox)
        .withExecutionTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  void createReturnsSession() {
    var config = configWith(new FakeSandbox());
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(config, semaphore);

    assertNotNull(session);
    assertTrue(session.isOpen());
    assertEquals(0, semaphore.availablePermits());

    session.close();
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void nullConfigThrows() {
    assertThrows(IllegalArgumentException.class, () -> ReplSession.create(null, new Semaphore(1)));
  }

  @Test
  void nullSemaphoreThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReplSession.create(configWith(new FakeSandbox()), null));
  }

  @Test
  void noPermitsThrows() {
    var semaphore = new Semaphore(0);
    assertThrows(
        ReplException.class, () -> ReplSession.create(configWith(new FakeSandbox()), semaphore));
  }

  @Test
  void factoryExceptionReleasesSemaphore() {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  throw new RuntimeException("sandbox init failed");
                })
            .build();
    var semaphore = new Semaphore(1);

    assertThrows(ReplException.class, () -> ReplSession.create(config, semaphore));
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void factoryThrowsReplExceptionDirectly() {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  throw new ReplException("custom repl error");
                })
            .build();
    var semaphore = new Semaphore(1);

    var ex = assertThrows(ReplException.class, () -> ReplSession.create(config, semaphore));
    assertEquals("custom repl error", ex.getMessage());
  }

  @Test
  void executeCode() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));

    var result = session.execute("1 + 1");

    assertEquals("ok", result.stdout());
    assertEquals(1, session.history().size());

    session.close();
  }

  @Test
  void executeOnClosedSessionThrows() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    session.close();

    assertThrows(ReplException.class, () -> session.execute("code"));
  }

  @Test
  void executeOnDeadSandboxThrows() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));
    sandbox.kill();

    assertThrows(ReplException.class, () -> session.execute("code"));

    session.close();
  }

  @Test
  void historyIsUnmodifiable() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertThrows(UnsupportedOperationException.class, () -> session.history().clear());
    session.close();
  }

  @Test
  void submittedOutputInitiallyNull() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertNull(session.submittedOutput());
    session.close();
  }

  @Test
  void submitHolderNotNull() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertNotNull(session.submitHolder());
    session.close();
  }

  @Test
  void registryNotNull() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertNotNull(session.registry());
    session.close();
  }

  @Test
  void closeReleasesSemaphore() {
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(configWith(new FakeSandbox()), semaphore);
    assertEquals(0, semaphore.availablePermits());

    session.close();
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void doubleCloseIsSafe() {
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(configWith(new FakeSandbox()), semaphore);

    session.close();
    session.close();

    assertEquals(1, semaphore.availablePermits());
    assertFalse(session.isOpen());
  }

  @Test
  void submitFunctionAutoRegisteredWhenAbsent() throws Exception {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    var submit = session.registry().get("submit");
    assertNotNull(submit, "SubmitFunction should be auto-registered");

    submit.handler().handle(Map.of("output", "the-answer"));
    assertEquals("the-answer", session.submittedOutput());

    session.close();
  }

  @Test
  void userRegisteredSubmitFunctionIsNotOverwritten() throws Exception {
    var userHolder = new AtomicReference<>();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> new FakeSandbox())
            .withHostFunction(SubmitFunction.create(userHolder))
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    session.registry().get("submit").handler().handle(Map.of("output", 42));
    assertEquals(42, userHolder.get());
    assertNull(session.submittedOutput());

    session.close();
  }

  @Test
  void hostFunctionsFromConfigRegistered() {
    var fn = new HostFunction("custom", "custom fn", params -> "ok");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> new FakeSandbox())
            .withHostFunction(fn)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    assertNotNull(session.registry().get("custom"));

    session.close();
  }

  @Test
  void isOpenReflectsState() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));
    assertTrue(session.isOpen());

    sandbox.kill();
    assertFalse(session.isOpen());

    session.close();
  }

  @Test
  void predictCallsRecordsEveryInvocationWithIteration() throws Exception {
    var devilsInstr = "Take the opposing view.";
    var rosyInstr = "Look on the bright side.";
    var predict =
        new HostFunction("predict", "fake", params -> Map.of("output", params.get("instructions")));
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunction(predict)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    // Iter 0: call predict twice with different instructions
    sandbox.behavior =
        registry -> {
          try {
            registry
                .get("predict")
                .handler()
                .handle(Map.of("instructions", devilsInstr, "input", "x"));
            registry
                .get("predict")
                .handler()
                .handle(Map.of("instructions", rosyInstr, "input", "y"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("first");

    // Iter 1: call predict once
    sandbox.behavior =
        registry -> {
          try {
            registry
                .get("predict")
                .handler()
                .handle(Map.of("instructions", devilsInstr, "input", "z"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("second");

    var calls = session.predictCalls();
    assertEquals(3, calls.size(), "all three predict calls recorded");
    assertEquals(devilsInstr, calls.get(0).instructions());
    assertEquals("x", calls.get(0).input());
    assertEquals(0, calls.get(0).iteration(), "first predict ran during iter 0 (first execute)");
    assertEquals(rosyInstr, calls.get(1).instructions());
    assertEquals(0, calls.get(1).iteration(), "second predict same iter as first");
    assertEquals(devilsInstr, calls.get(2).instructions());
    assertEquals(1, calls.get(2).iteration(), "third predict ran during iter 1");
    session.close();
  }

  @Test
  void predictCallsIsImmutableSnapshot() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    var snapshot = session.predictCalls();
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.add(new PredictCall("x", "y", 0)));
    session.close();
  }

  @Test
  void predictInstructionsDerivesFromPredictCalls() throws Exception {
    var predict = new HostFunction("predict", "fake", params -> Map.of("output", "ok"));
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunction(predict)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));
    sandbox.behavior =
        registry -> {
          try {
            registry.get("predict").handler().handle(Map.of("instructions", "a", "input", "x"));
            registry.get("predict").handler().handle(Map.of("instructions", "b", "input", "y"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("noop");
    assertEquals(List.of("a", "b"), session.predictInstructions());
    session.close();
  }

  @Test
  void calledHostFunctionsCountsNonPredictInvocations() throws Exception {
    var predict = new HostFunction("predict", "fake", params -> Map.of("output", "ok"));
    var marketQuote = new HostFunction("marketQuote", "fake", params -> "$200");
    var fredIndicator = new HostFunction("fredIndicator", "fake", params -> 1.5);
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunctions(java.util.List.of(predict, marketQuote, fredIndicator))
            .build();
    var session = ReplSession.create(config, new Semaphore(1));
    sandbox.behavior =
        registry -> {
          try {
            // 1 predict (excluded from calledHostFunctions)
            registry.get("predict").handler().handle(Map.of("instructions", "x", "input", "y"));
            // 3 marketQuote
            registry.get("marketQuote").handler().handle(Map.of("ticker", "AAPL"));
            registry.get("marketQuote").handler().handle(Map.of("ticker", "GOOG"));
            registry.get("marketQuote").handler().handle(Map.of("ticker", "MSFT"));
            // 1 fredIndicator
            registry.get("fredIndicator").handler().handle(Map.of("seriesId", "GDP"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("noop");

    var counts = session.calledHostFunctions();
    assertEquals(3, counts.get("marketQuote"));
    assertEquals(1, counts.get("fredIndicator"));
    assertNull(counts.get("predict"), "predict is excluded — has its own predictCalls accessor");
    session.close();
  }

  @Test
  void calledHostFunctionsExcludesGetInputWiring() throws Exception {
    // RlmHarness registers __getInput as framework wiring. Callers shouldn't see it in their
    // metric counts.
    var getInput = new HostFunction("__getInput", "fake", params -> Map.of("query", "hi"));
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunction(getInput)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));
    sandbox.behavior =
        registry -> {
          try {
            registry.get("__getInput").handler().handle(Map.of());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("noop");
    assertNull(session.calledHostFunctions().get("__getInput"));
    session.close();
  }

  @Test
  void calledSignaturesEmptyWhenNonePredictsCalled() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertTrue(session.calledSignatures().isEmpty());
    session.close();
  }

  @Test
  void calledSignaturesRecordsExactInstructionMatch() throws Exception {
    // Register a fake predict that just records the instructions; configure two required sigs.
    var devilsAdvocate = "Take the opposing view on the consensus.";
    var deepDive = "Drill deep into the leading hypothesis.";
    var predict =
        new HostFunction("predict", "fake", params -> Map.of("output", params.get("instructions")));
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> new FakeSandbox())
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunction(predict)
            .withRequiredPredictSignature(
                new RequiredPredictSignature("devils_advocate", devilsAdvocate))
            .withRequiredPredictSignature(new RequiredPredictSignature("deep_dive", deepDive))
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    // Call predict() with an instruction that matches one signature exactly.
    session
        .registry()
        .get("predict")
        .handler()
        .handle(Map.of("instructions", devilsAdvocate, "input", "x"));
    assertEquals(java.util.Set.of("devils_advocate"), session.calledSignatures());

    // Substring or paraphrased match must NOT count.
    session
        .registry()
        .get("predict")
        .handler()
        .handle(Map.of("instructions", "Drill deep", "input", "x"));
    assertEquals(java.util.Set.of("devils_advocate"), session.calledSignatures());

    // Now call deep_dive verbatim.
    session
        .registry()
        .get("predict")
        .handler()
        .handle(Map.of("instructions", deepDive, "input", "x"));
    assertEquals(java.util.Set.of("devils_advocate", "deep_dive"), session.calledSignatures());

    session.close();
  }

  @Test
  void calledSignaturesIsImmutableSnapshot() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    var snapshot = session.calledSignatures();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add("x"));
    session.close();
  }

  @Test
  void executedCodeTruncatedAtCap() {
    // 1.1.5 ask #3: ExecutionResult.executedCode is capped per ReplConfig.maxExecutedCodeChars.
    // Truncation appends "... (len=N)" so consumers know the original length.
    var sandbox = new EchoSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withMaxExecutedCodeChars(20)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    var bigCode = "var x = " + "a".repeat(100) + ";";
    var result = session.execute(bigCode);

    assertTrue(
        result.executedCode().length() <= 50,
        "truncated length should be ~cap+marker, got len=" + result.executedCode().length());
    assertTrue(result.executedCode().contains("(len=" + bigCode.length() + ")"));
    assertTrue(result.executedCode().startsWith("var x = a"));
    session.close();
  }

  @Test
  void executedCodePreservedWhenUnderCap() {
    var sandbox = new EchoSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withMaxExecutedCodeChars(5000)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    var smallCode = "var x = 1;";
    var result = session.execute(smallCode);

    assertEquals(smallCode, result.executedCode(), "code under cap is returned verbatim");
    session.close();
  }

  @Test
  void executedCodeNoTruncationWhenCapIsZero() {
    var sandbox = new EchoSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withMaxExecutedCodeChars(0) // disabled
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    var bigCode = "x".repeat(50_000);
    var result = session.execute(bigCode);

    assertEquals(bigCode, result.executedCode(), "cap=0 disables truncation");
    session.close();
  }

  /** Sandbox that echoes the request's code into the result's executedCode field. */
  private static class EchoSandbox implements Sandbox {
    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return new ExecutionResult(request.code(), "ok", "", 0, null, java.util.Map.of());
    }

    @Override
    public boolean isAlive() {
      return true;
    }

    @Override
    public void close() {}
  }

  /**
   * Sandbox that runs a configurable behavior against the registry on each execute. Lets tests
   * simulate the model emitting code that calls registered host functions, so we can drive
   * predict/marketQuote/etc through the real ReplSession wrapper path.
   */
  private static class RecordingSandbox implements Sandbox {
    java.util.function.Consumer<ai.singlr.repl.host.HostFunctionRegistry> behavior = r -> {};
    private ai.singlr.repl.host.HostFunctionRegistry registry;

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      // Use a captured registry; in real life RpcChannel does this dispatch.
      // Tests inject the registry via configWith → ReplSession.create wires it.
      if (registry != null) {
        behavior.accept(registry);
      }
      return new ExecutionResult("", "", 0, null, java.util.Map.of());
    }

    @Override
    public boolean isAlive() {
      return true;
    }

    @Override
    public void close() {}

    void setRegistry(ai.singlr.repl.host.HostFunctionRegistry registry) {
      this.registry = registry;
    }
  }

  @Test
  void multipleExecutionsAccumulate() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));

    session.execute("a");
    session.execute("b");
    session.execute("c");

    assertEquals(3, session.history().size());

    session.close();
  }

  private static class FakeSandbox implements Sandbox {
    private final AtomicBoolean alive = new AtomicBoolean(true);

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return ExecutionResult.success("ok");
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    void kill() {
      alive.set(false);
    }

    @Override
    public void close() {
      alive.set(false);
    }
  }
}
