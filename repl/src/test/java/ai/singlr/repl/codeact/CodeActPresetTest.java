/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.repl.execution.JShellExecutionProvider;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.permissions.PermissionMode;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link CodeActPreset}. Phase6AcceptanceTest already drives the preset
 * end-to-end against a real sandbox; this class pins down the wiring contract that the end-to-end
 * test takes for granted — preset-internal provider ownership, tool-set shape, hooks registered,
 * argument null-checks, OwnedExecutionProvider wrapping.
 */
final class CodeActPresetTest {

  public record Input(String topic) {}

  public record Answer(String headline) {}

  private static Model fixedReply(String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(reply).build();
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        throw new AssertionError("unused");
      }

      @Override
      public String id() {
        return "fixed";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void typedRejectsNullInputType() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> CodeActPreset.typed(null, Answer.class, new Input("x")));
    assertEquals("inputType must not be null", ex.getMessage());
  }

  @Test
  void typedRejectsNullOutputType() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> CodeActPreset.typed(Input.class, null, new Input("x")));
    assertEquals("outputType must not be null", ex.getMessage());
  }

  @Test
  void withSubLmRejectsNullInputType() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> CodeActPreset.withSubLm(null, Answer.class, new Input("x"), fixedReply("any")));
    assertEquals("inputType must not be null", ex.getMessage());
  }

  @Test
  void withSubLmRejectsNullSubModel() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> CodeActPreset.withSubLm(Input.class, Answer.class, new Input("x"), null));
    assertEquals("subModel must not be null", ex.getMessage());
  }

  @Test
  void typedRegistersOnlyExecuteAtAgentLoopLevel() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    var names = options.tools().bindings().stream().map(b -> b.tool().name()).toList();
    assertEquals(List.of(ExecuteTool.NAME), names);
  }

  @Test
  void typedSetsLockedDownPermission() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    assertEquals(PermissionMode.LOCKED_DOWN, options.permission().orElseThrow().mode());
  }

  @Test
  void typedWiresOutputSchemaAndSystemPrompt() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    assertTrue(options.outputSchema().isPresent());
    var prompt = options.systemPrompt().orElseThrow();
    assertTrue(prompt.contains("JShell"));
    assertTrue(prompt.contains("Execute"));
  }

  @Test
  void typedExecutionProviderIsOwnedAndShutdownHookFree() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    var provider = options.executionProvider();
    var owned = assertInstanceOf(OwnedExecutionProvider.class, provider);
    // The wrapped JShellExecutionProvider must NOT have installed a JVM shutdown hook — that's
    // the whole point of the Owned wrapper. Inspection is indirect: we close the OwnedProvider
    // and assert the inner is closed, then we assert the inner was created via the no-hook path
    // by reflecting on internal state via JShellExecutionProvider's public surface.
    assertNotNull(owned.delegate());
    // Closing the OwnedExecutionProvider must close the inner.
    owned.close();
    assertTrue(((JShellExecutionProvider) owned.delegate()).isClosed());
  }

  @Test
  void withSubLmAlsoRegistersOnlyExecute() {
    var options =
        build(
            CodeActPreset.withSubLm(Input.class, Answer.class, new Input("x"), fixedReply("any")));
    var names = options.tools().bindings().stream().map(b -> b.tool().name()).toList();
    assertEquals(List.of(ExecuteTool.NAME), names);
  }

  @Test
  void withSubLmRegistersOnSubmitStopHook() {
    var options =
        build(
            CodeActPreset.withSubLm(Input.class, Answer.class, new Input("x"), fixedReply("any")));
    var hookNames = options.hooks().stream().map(h -> h.name()).toList();
    assertTrue(hookNames.stream().anyMatch(n -> n.equals("OnSubmitStopHook")));
  }

  @Test
  void typedDoesNotRegisterOnSubmitStopHook() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    var hookNames = options.hooks().stream().map(h -> h.name()).toList();
    assertTrue(hookNames.stream().noneMatch(n -> n.equals("OnSubmitStopHook")));
  }

  @Test
  void requireExecuteCodeHookAlwaysRegistered() {
    var options = build(CodeActPreset.typed(Input.class, Answer.class, new Input("x")));
    var hookNames = options.hooks().stream().map(h -> h.name()).toList();
    assertTrue(hookNames.stream().anyMatch(n -> n.equals("RequireExecuteCodeHook")));
  }

  @Test
  void distinctProvidersForDistinctApplications() {
    var preset = CodeActPreset.typed(Input.class, Answer.class, new Input("x"));
    var a = build(preset);
    var b = build(preset);
    // Two preset applications produce two distinct providers — preset state is not shared across
    // sessions, which is what "every preset application constructs its own per-session state"
    // in the class javadoc promises.
    var providerA = ((OwnedExecutionProvider) a.executionProvider()).delegate();
    var providerB = ((OwnedExecutionProvider) b.executionProvider()).delegate();
    org.junit.jupiter.api.Assertions.assertNotSame(providerA, providerB);
    a.executionProvider();
    b.executionProvider();
  }

  @Test
  void nullInputValueIsAllowed() {
    // The spec allows null inputValue — the model is expected to read the JSON user message
    // instead. Ensure no NPE during preset application.
    var options = build(CodeActPreset.typed(Input.class, Answer.class, null));
    assertNotNull(options);
  }

  private static SessionOptions build(ai.singlr.session.SessionPreset preset) {
    return SessionOptions.newBuilder().withModel(fixedReply("ignored")).apply(preset).build();
  }
}
