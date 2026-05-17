/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.session.tools.ToolCategory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class ExecuteToolTest {

  /** Recording provider that captures the last request and returns a configurable result. */
  private static final class RecordingProvider implements ExecutionProvider {
    ExecutionRequest lastRequest;
    final ExecutionResult result;
    boolean throwOnExecute;

    RecordingProvider(ExecutionResult result) {
      this.result = result;
    }

    @Override
    public ExecutionCapabilities capabilities() {
      return ExecutionCapabilities.newBuilder().build();
    }

    @Override
    public CompletionStage<ExecutionResult> execute(
        ExecutionRequest request, CancellationToken cancellation) {
      this.lastRequest = request;
      if (throwOnExecute) {
        return CompletableFuture.failedFuture(new RuntimeException("boom"));
      }
      return CompletableFuture.completedFuture(result);
    }
  }

  private static ExecutionResult ok(String stdout) {
    return new ExecutionResult(0, stdout, "", Duration.ofMillis(10), false, Map.of());
  }

  private static ToolContext ctx() {
    return ToolContext.of(new CancellationToken(), Duration.ofSeconds(5));
  }

  // ── binding shape ────────────────────────────────────────────────────────

  @Test
  void bindingRejectsNullProvider() {
    var ex = assertThrows(NullPointerException.class, () -> ExecuteTool.binding(null));
    assertEquals("provider must not be null", ex.getMessage());
  }

  @Test
  void bindingHasExecutionCategoryAndExpectedName() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    assertEquals(ExecuteTool.NAME, binding.tool().name());
    assertEquals(ToolCategory.EXECUTION, binding.category());
  }

  @Test
  void bindingDeclaresExpectedParameters() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var names = binding.tool().parameters().stream().map(p -> p.name()).toList();
    assertTrue(names.contains("runtime"));
    assertTrue(names.contains("script"));
    assertTrue(names.contains("args"));
    assertTrue(names.contains("workingDirectory"));
    assertTrue(names.contains("timeoutSeconds"));
    assertTrue(names.contains("environment"));
    assertTrue(names.contains("stdin"));
  }

  // ── permission key ───────────────────────────────────────────────────────

  @Test
  void permissionKeyEncodesRuntimeAndFirstToken() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "bash", "script", "rm -rf /tmp/foo"));
    assertEquals(ExecuteTool.NAME, key.toolName());
    assertEquals("BASH/rm", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesSingleTokenScript() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "BASH", "script", "true"));
    assertEquals("BASH/true", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesMissingRuntime() {
    var key = ExecuteTool.permissionKey(Map.of("script", "echo hi"));
    assertEquals("UNKNOWN/echo", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesBlankScript() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "BASH", "script", ""));
    assertEquals("BASH/", key.canonicalArgs());
  }

  // ── arg validation ───────────────────────────────────────────────────────

  @Test
  void missingRuntimeReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("script", "echo hi"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'runtime'"));
  }

  @Test
  void unknownRuntimeReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "DELPHI", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("unknown runtime"));
  }

  @Test
  void missingScriptReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'script'"));
  }

  @Test
  void argsMustBeArrayOfStrings() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "args", List.of(1, 2, 3)), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void argsCannotBeNonArray() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "args", "not-array"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void invalidTimeoutSecondsReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "timeoutSeconds", 0), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("positive integer"));
  }

  @Test
  void environmentMustBeStringMap() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of("runtime", "BASH", "script", "echo", "environment", Map.of("FOO", 123)),
                ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("string→string"));
  }

  @Test
  void environmentMustNotBeNonMap() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of("runtime", "BASH", "script", "echo", "environment", "not-a-map"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("string→string"));
  }

  // ── successful dispatch ──────────────────────────────────────────────────

  @Test
  void successfulCallFormatsResultAndCarriesStructuredData() {
    var provider = new RecordingProvider(ok("hello\n"));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of(
                    "runtime",
                    "BASH",
                    "script",
                    "echo hello",
                    "args",
                    List.of("a", "b"),
                    "workingDirectory",
                    "/tmp",
                    "timeoutSeconds",
                    7,
                    "environment",
                    Map.of("FOO", "bar"),
                    "stdin",
                    "in"),
                ctx());
    assertTrue(result.success());
    assertTrue(result.output().contains("runtime BASH"));
    assertTrue(result.output().contains("exit 0"));
    assertTrue(result.output().contains("hello"));
    assertInstanceOf(ExecutionResult.class, result.data());
    assertSame(provider.result, result.data());
    assertEquals(Runtime.BASH, provider.lastRequest.runtime());
    assertEquals("echo hello", provider.lastRequest.script());
    assertEquals(List.of("a", "b"), provider.lastRequest.args());
    assertEquals(Path.of("/tmp"), provider.lastRequest.workingDirectory());
    assertEquals(Duration.ofSeconds(7), provider.lastRequest.timeout());
    assertEquals(Map.of("FOO", "bar"), provider.lastRequest.environment());
    assertEquals("in", provider.lastRequest.stdin().orElseThrow());
  }

  @Test
  void timeoutFlagPropagatedToOutput() {
    var timedOut = new ExecutionResult(-1, "", "", Duration.ofMillis(50), true, Map.of());
    var provider = new RecordingProvider(timedOut);
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "sleep 5", "timeoutSeconds", 1), ctx());
    assertTrue(result.success(), "tool dispatch should succeed even when execution timed out");
    assertTrue(result.output().contains("TIMEOUT"));
    var data = assertInstanceOf(ExecutionResult.class, result.data());
    assertTrue(data.timedOut());
  }

  @Test
  void formatIncludesStderrWhenPresent() {
    var withErr = new ExecutionResult(2, "out\n", "err\n", Duration.ofMillis(5), false, Map.of());
    var provider = new RecordingProvider(withErr);
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertTrue(result.output().contains("[stderr]"));
    assertTrue(result.output().contains("err"));
  }

  // ── provider failure paths ───────────────────────────────────────────────

  @Test
  void providerExceptionalCompletionReturnsToolFailure() {
    var provider = new RecordingProvider(ok(""));
    provider.throwOnExecute = true;
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("provider failed"));
    assertTrue(result.output().contains("boom"));
  }

  @Test
  void cancellationBeforeDispatchReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var token = new CancellationToken();
    token.cancel("test");
    var toolCtx = ToolContext.of(token, Duration.ofSeconds(1));
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), toolCtx);
    assertFalse(result.success());
    assertTrue(result.output().contains("test"));
  }
}
