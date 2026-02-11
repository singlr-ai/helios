/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.model.Model;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an agent.
 *
 * @param name the agent's name
 * @param model the LLM to use
 * @param systemPrompt the system prompt template (use ${var} for placeholders)
 * @param tools additional tools (memory tools are added automatically)
 * @param memory the memory instance
 * @param maxIterations maximum tool execution iterations (prevents infinite loops)
 * @param includeMemoryTools whether to include built-in memory tools
 * @param traceListeners listeners notified with completed traces (empty = tracing disabled)
 * @param faultTolerance fault tolerance for model calls and tool execution (defaults to
 *     passthrough)
 * @param promptName the prompt registry name used for this agent (optional, for trace lineage)
 * @param promptVersion the prompt registry version used for this agent (optional, for trace
 *     lineage)
 */
public record AgentConfig(
    String name,
    Model model,
    String systemPrompt,
    List<Tool> tools,
    Memory memory,
    int maxIterations,
    boolean includeMemoryTools,
    List<TraceListener> traceListeners,
    FaultTolerance faultTolerance,
    String promptName,
    Integer promptVersion) {

  private static final int DEFAULT_MAX_ITERATIONS = 10;
  private static final String DEFAULT_SYSTEM_PROMPT =
      """
			You are ${name}, an AI assistant with persistent memory.

			## Core Memory
			${core_memory}

			## Instructions
			- Use memory tools to store important information
			- Use archival memory for long-term storage
			- Be helpful and accurate
			""";

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(AgentConfig config) {
    return new Builder(config);
  }

  /** Returns true if trace listeners are configured. */
  public boolean tracingEnabled() {
    return traceListeners != null && !traceListeners.isEmpty();
  }

  public static class Builder {
    private String name = "Assistant";
    private Model model;
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private List<Tool> tools = new ArrayList<>();
    private Memory memory;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private boolean includeMemoryTools = true;
    private List<TraceListener> traceListeners = new ArrayList<>();
    private FaultTolerance faultTolerance = FaultTolerance.PASSTHROUGH;
    private String promptName;
    private Integer promptVersion;

    private Builder() {}

    private Builder(AgentConfig config) {
      this.name = config.name;
      this.model = config.model;
      this.systemPrompt = config.systemPrompt;
      this.tools = new ArrayList<>(config.tools);
      this.memory = config.memory;
      this.maxIterations = config.maxIterations;
      this.includeMemoryTools = config.includeMemoryTools;
      this.traceListeners = new ArrayList<>(config.traceListeners);
      this.faultTolerance = config.faultTolerance;
      this.promptName = config.promptName;
      this.promptVersion = config.promptVersion;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withModel(Model model) {
      this.model = model;
      return this;
    }

    public Builder withSystemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
      return this;
    }

    public Builder withTool(Tool tool) {
      this.tools.add(tool);
      return this;
    }

    public Builder withTools(List<Tool> tools) {
      this.tools.addAll(tools);
      return this;
    }

    public Builder withMemory(Memory memory) {
      this.memory = memory;
      return this;
    }

    public Builder withMaxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    public Builder withIncludeMemoryTools(boolean include) {
      this.includeMemoryTools = include;
      return this;
    }

    public Builder withTraceListener(TraceListener listener) {
      this.traceListeners.add(listener);
      return this;
    }

    public Builder withTraceListeners(List<TraceListener> listeners) {
      this.traceListeners.addAll(listeners);
      return this;
    }

    public Builder withFaultTolerance(FaultTolerance faultTolerance) {
      this.faultTolerance = faultTolerance;
      return this;
    }

    public Builder withPromptName(String promptName) {
      this.promptName = promptName;
      return this;
    }

    public Builder withPromptVersion(Integer promptVersion) {
      this.promptVersion = promptVersion;
      return this;
    }

    public AgentConfig build() {
      if (model == null) {
        throw new IllegalStateException("Model is required");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      return new AgentConfig(
          name,
          model,
          systemPrompt,
          List.copyOf(tools),
          memory,
          maxIterations,
          includeMemoryTools,
          List.copyOf(traceListeners),
          faultTolerance,
          promptName,
          promptVersion);
    }
  }
}
