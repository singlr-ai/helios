/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CodeExecutionToolTest {

  @Test
  void nullSessionThrows() {
    assertThrows(IllegalArgumentException.class, () -> CodeExecutionTool.create(null));
  }

  @Test
  void createReturnsTool() {
    var session = createSession(new StubSandbox(ExecutionResult.success("hello")));
    var tool = CodeExecutionTool.create(session);

    assertEquals("execute_code", tool.name());
    assertNotNull(tool.description());
    assertEquals(1, tool.parameters().size());
    assertEquals("code", tool.parameters().get(0).name());
    assertTrue(tool.parameters().get(0).required());

    session.close();
  }

  @Test
  void executeCodeSuccess() {
    var session = createSession(new StubSandbox(ExecutionResult.success("42")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "System.out.println(42)"));

    assertTrue(result.success());
    assertEquals("42", result.output());

    session.close();
  }

  @Test
  void executeCodeWithStderr() {
    var session = createSession(new StubSandbox(new ExecutionResult("out", "warn", 0, null)));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "code"));

    assertTrue(result.success());
    assertTrue(result.output().contains("out"));
    assertTrue(result.output().contains("STDERR: warn"));

    session.close();
  }

  @Test
  void executeCodeWithExitCode() {
    var session = createSession(new StubSandbox(ExecutionResult.failure("error", 1)));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "bad code"));

    assertTrue(result.success());
    assertTrue(result.output().contains("STDERR: error"));
    assertTrue(result.output().contains("[exit code 1]"));

    session.close();
  }

  @Test
  void executeCodeWithSubmittedValue() {
    var session = createSession(new StubSandbox(ExecutionResult.success("out", "answer")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "submit('answer')"));

    assertTrue(result.success());
    assertTrue(result.output().contains("[submitted: answer]"));

    session.close();
  }

  @Test
  void executeCodeNoOutput() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "noop"));

    assertTrue(result.success());
    assertEquals("(no output)", result.output());

    session.close();
  }

  @Test
  void missingCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of());

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void blankCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "  "));

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void nonStringCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", 42));

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void replExceptionReturnedAsSuccess() {
    var sandbox =
        new Sandbox() {
          @Override
          public ExecutionResult execute(ExecutionRequest request) {
            throw new ReplException("sandbox died");
          }

          @Override
          public boolean isAlive() {
            return true;
          }

          @Override
          public void close() {}
        };
    var session = createSession(sandbox);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "anything"));

    assertTrue(result.success());
    assertTrue(result.output().contains("Error: sandbox died"));

    session.close();
  }

  @Test
  void truncateReturnsInputUnchangedWhenUnderCap() {
    assertEquals("hello", CodeExecutionTool.truncate("hello", 100));
    assertEquals("hello", CodeExecutionTool.truncate("hello", 5));
  }

  @Test
  void truncateDisabledByZeroOrNegativeCap() {
    var big = "x".repeat(10_000);
    assertEquals(big, CodeExecutionTool.truncate(big, 0));
    assertEquals(big, CodeExecutionTool.truncate(big, -1));
  }

  @Test
  void truncateNullReturnsNull() {
    assertEquals(null, CodeExecutionTool.truncate(null, 100));
  }

  @Test
  void truncateClipsLongOutputAndAppendsMarker() {
    var big = "x".repeat(20_000);
    var truncated = CodeExecutionTool.truncate(big, 5_000);
    assertTrue(truncated.length() <= 5_000, "truncated length must respect cap");
    assertTrue(truncated.contains("output truncated"), "marker must be present");
    assertTrue(truncated.contains("20000 characters"), "marker must include real length");
    assertTrue(truncated.contains("Variables in the sandbox retain their full values"));
    assertTrue(truncated.startsWith("xxx"), "head of original output preserved");
  }

  @Test
  void executeCodeTruncatesLongStdoutForModelButKeepsFullInHistory() {
    var bigStdout = "y".repeat(20_000);
    var session =
        createSessionWithCap(new StubSandbox(ExecutionResult.success(bigStdout)), /* cap= */ 5_000);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "print(x)"));

    assertTrue(result.success());
    assertTrue(
        result.output().length() <= 5_000, "model-facing output must respect the configured cap");
    assertTrue(result.output().contains("output truncated"));

    var historical = session.history().get(0);
    assertEquals(bigStdout, historical.stdout(), "full stdout retained in session history");

    session.close();
  }

  @Test
  void budgetHeaderPrependedWhenEnabledAndBudgetSet() {
    var session =
        createSessionWithBudget(new StubSandbox(ExecutionResult.success("hello")), /* max= */ 50);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "println(\"hello\")"));

    assertTrue(result.success());
    assertTrue(
        result.output().startsWith("[budget: predicts=0/50]\n"),
        "budget header expected at the start of the model-facing output, got:\n" + result.output());
    assertTrue(result.output().contains("hello"));
    session.close();
  }

  @Test
  void budgetHeaderOmittedWhenBudgetIsUnlimited() {
    // maxLlmCalls=0 means "unlimited"; printing predicts=N/0 would be misleading. Skip the line.
    var session = createSessionWithBudget(new StubSandbox(ExecutionResult.success("hello")), 0);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "x"));

    assertFalse(result.output().contains("[budget:"), "no header when budget is unlimited");
    assertEquals("hello", result.output());
    session.close();
  }

  @Test
  void budgetHeaderOmittedWhenExplicitlyDisabled() {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> new StubSandbox(ExecutionResult.success("hello")))
            .withExecutionTimeout(Duration.ofSeconds(10))
            .withMaxLlmCalls(50)
            .withBudgetHeader(false)
            .build();
    var session = ReplSession.create(config, new Semaphore(10));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "x"));

    assertFalse(result.output().contains("[budget:"));
    session.close();
  }

  @Test
  void executeCodeNoTruncationWhenCapIsZero() {
    var bigStdout = "z".repeat(8_000);
    var session =
        createSessionWithCap(new StubSandbox(ExecutionResult.success(bigStdout)), /* cap= */ 0);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "print(x)"));

    assertTrue(result.success());
    assertEquals(bigStdout, result.output(), "cap=0 disables truncation");

    session.close();
  }

  private static ReplSession createSession(Sandbox sandbox) {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(10))
            .withBudgetHeader(false)
            .build();
    return ReplSession.create(config, new Semaphore(10));
  }

  private static ReplSession createSessionWithCap(Sandbox sandbox, int cap) {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(10))
            .withMaxOutputCharsToModel(cap)
            .withBudgetHeader(false)
            .build();
    return ReplSession.create(config, new Semaphore(10));
  }

  private static ReplSession createSessionWithBudget(Sandbox sandbox, int maxLlmCalls) {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(10))
            .withMaxLlmCalls(maxLlmCalls)
            .withBudgetHeader(true)
            .build();
    return ReplSession.create(config, new Semaphore(10));
  }

  private static class StubSandbox implements Sandbox {
    private final ExecutionResult result;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    StubSandbox(ExecutionResult result) {
      this.result = result;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return result;
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void close() {
      alive.set(false);
    }
  }
}
