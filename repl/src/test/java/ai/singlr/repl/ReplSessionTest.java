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
