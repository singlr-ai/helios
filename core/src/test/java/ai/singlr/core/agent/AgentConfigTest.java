/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.TraceDetail;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

  private final Model mockModel =
      new Model() {
        @Override
        public Response chat(List<Message> messages, List<Tool> tools) {
          return Response.newBuilder()
              .withContent("OK")
              .withFinishReason(FinishReason.STOP)
              .build();
        }

        @Override
        public String id() {
          return "mock";
        }

        @Override
        public String provider() {
          return "test";
        }
      };

  @Test
  void buildMinimalConfig() {
    var config = AgentConfig.newBuilder().withModel(mockModel).build();

    assertEquals("Assistant", config.name());
    assertEquals(mockModel, config.model());
    assertTrue(config.tools().isEmpty());
    assertEquals(10, config.maxIterations());
    assertTrue(config.includeMemoryTools());
  }

  @Test
  void buildFullConfig() {
    var memory = InMemoryMemory.withDefaults();
    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("Test tool")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();

    var config =
        AgentConfig.newBuilder()
            .withName("TestAgent")
            .withModel(mockModel)
            .withSystemPrompt("Custom prompt")
            .withTool(tool)
            .withMemory(memory)
            .withMaxIterations(5)
            .withIncludeMemoryTools(false)
            .build();

    assertEquals("TestAgent", config.name());
    assertEquals("Custom prompt", config.systemPrompt());
    assertEquals(1, config.tools().size());
    assertEquals(memory, config.memory());
    assertEquals(5, config.maxIterations());
    assertFalse(config.includeMemoryTools());
  }

  @Test
  void modelRequired() {
    var builder = AgentConfig.newBuilder().withName("Test");

    var exception = assertThrows(IllegalStateException.class, builder::build);
    assertEquals("Model is required", exception.getMessage());
  }

  @Test
  void maxIterationsValidation() {
    var builder = AgentConfig.newBuilder().withModel(mockModel).withMaxIterations(0);

    var exception = assertThrows(IllegalStateException.class, builder::build);
    assertEquals("maxIterations must be >= 1", exception.getMessage());
  }

  @Test
  void toolsReturnsOnlyUserConfiguredTools() {
    var customTool =
        Tool.newBuilder()
            .withName("custom")
            .withDescription("Custom")
            .withExecutor(args -> ToolResult.success("OK"))
            .build();

    var config =
        AgentConfig.newBuilder()
            .withModel(mockModel)
            .withTool(customTool)
            .withMemory(InMemoryMemory.withDefaults())
            .withIncludeMemoryTools(true)
            .build();

    assertEquals(1, config.tools().size());
    assertEquals("custom", config.tools().getFirst().name());
  }

  @Test
  void withTools() {
    var tool1 =
        Tool.newBuilder()
            .withName("tool1")
            .withDescription("Tool 1")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();
    var tool2 =
        Tool.newBuilder()
            .withName("tool2")
            .withDescription("Tool 2")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();

    var config =
        AgentConfig.newBuilder()
            .withModel(mockModel)
            .withTools(List.of(tool1, tool2))
            .withIncludeMemoryTools(false)
            .build();

    assertEquals(2, config.tools().size());
  }

  @Test
  void copyBuilder() {
    var original =
        AgentConfig.newBuilder()
            .withName("Original")
            .withModel(mockModel)
            .withMaxIterations(5)
            .build();

    var copy = AgentConfig.newBuilder(original).withName("Copy").build();

    assertEquals("Original", original.name());
    assertEquals("Copy", copy.name());
    assertEquals(5, copy.maxIterations());
  }

  @Test
  void tracingDisabledByDefault() {
    var config = AgentConfig.newBuilder().withModel(mockModel).build();

    assertFalse(config.tracingEnabled());
    assertTrue(config.traceListeners().isEmpty());
  }

  @Test
  void tracingEnabledWithListener() {
    var received = new ArrayList<ai.singlr.core.trace.Trace>();
    var config =
        AgentConfig.newBuilder().withModel(mockModel).withTraceListener(received::add).build();

    assertTrue(config.tracingEnabled());
    assertEquals(1, config.traceListeners().size());
  }

  @Test
  void traceListenersAreImmutable() {
    var config =
        AgentConfig.newBuilder().withModel(mockModel).withTraceListener(trace -> {}).build();

    assertThrows(
        UnsupportedOperationException.class, () -> config.traceListeners().add(trace -> {}));
  }

  @Test
  void withTraceListenersList() {
    TraceListener l1 = trace -> {};
    TraceListener l2 = trace -> {};
    var config =
        AgentConfig.newBuilder().withModel(mockModel).withTraceListeners(List.of(l1, l2)).build();

    assertEquals(2, config.traceListeners().size());
  }

  @Test
  void copyBuilderPreservesTraceListeners() {
    TraceListener listener = trace -> {};
    var original =
        AgentConfig.newBuilder().withModel(mockModel).withTraceListener(listener).build();

    var copy = AgentConfig.newBuilder(original).build();

    assertTrue(copy.tracingEnabled());
    assertEquals(1, copy.traceListeners().size());
  }

  @Test
  void toolsAreImmutable() {
    var config =
        AgentConfig.newBuilder().withModel(mockModel).withIncludeMemoryTools(false).build();

    var tools = config.tools();
    try {
      tools.add(
          Tool.newBuilder()
              .withName("new")
              .withDescription("New")
              .withExecutor(args -> ToolResult.success("ok"))
              .build());
    } catch (UnsupportedOperationException e) {
      // Expected - list is immutable
    }
    assertTrue(config.tools().isEmpty());
  }

  @Test
  void promptNameAndVersionDefaultToNull() {
    var config = AgentConfig.newBuilder().withModel(mockModel).build();

    assertNull(config.promptName());
    assertNull(config.promptVersion());
  }

  @Test
  void promptNameAndVersionSet() {
    var config =
        AgentConfig.newBuilder()
            .withModel(mockModel)
            .withPromptName("agent-prompt")
            .withPromptVersion(3)
            .build();

    assertEquals("agent-prompt", config.promptName());
    assertEquals(3, config.promptVersion());
  }

  @Test
  void copyBuilderPreservesPromptLineage() {
    var original =
        AgentConfig.newBuilder()
            .withModel(mockModel)
            .withPromptName("prompt-v1")
            .withPromptVersion(2)
            .build();

    var copy = AgentConfig.newBuilder(original).build();

    assertEquals("prompt-v1", copy.promptName());
    assertEquals(2, copy.promptVersion());
  }

  @Test
  void traceDetailDefaultsToStandard() {
    var config = AgentConfig.newBuilder().withModel(mockModel).build();

    assertEquals(TraceDetail.STANDARD, config.traceDetail());
  }

  @Test
  void traceDetailSetToVerbose() {
    var config =
        AgentConfig.newBuilder().withModel(mockModel).withTraceDetail(TraceDetail.VERBOSE).build();

    assertEquals(TraceDetail.VERBOSE, config.traceDetail());
  }

  @Test
  void copyBuilderPreservesTraceDetail() {
    var original =
        AgentConfig.newBuilder().withModel(mockModel).withTraceDetail(TraceDetail.VERBOSE).build();

    var copy = AgentConfig.newBuilder(original).build();

    assertEquals(TraceDetail.VERBOSE, copy.traceDetail());
  }
}
