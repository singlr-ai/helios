/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.model.Model;
import ai.singlr.core.runtime.RunStore;
import ai.singlr.core.runtime.ToolCallJournal;
import ai.singlr.core.runtime.UnsafeResumePolicy;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.trace.SpanListener;
import ai.singlr.core.trace.TraceDetail;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param traceListeners listeners notified with completed traces (empty = tracing disabled unless
 *     spanListeners are configured)
 * @param spanListeners listeners notified live as individual spans start and end. When non-empty, a
 *     trace is created even if {@code traceListeners} is empty
 * @param faultTolerance fault tolerance for model calls and tool execution (defaults to
 *     passthrough)
 * @param promptName the prompt registry name used for this agent (optional, for trace lineage)
 * @param promptVersion the prompt registry version used for this agent (optional, for trace
 *     lineage)
 * @param traceDetail controls span attribute verbosity (defaults to {@link TraceDetail#STANDARD})
 * @param parallelToolExecution when true, multiple tool calls in a single response execute
 *     concurrently on virtual threads (defaults to false)
 * @param minIterations minimum tool execution iterations before the agent may complete. When the
 *     model tries to stop before this floor, the agent injects a USER guidance message and loops.
 *     Defaults to 0. Must satisfy {@code 0 <= minIterations <= maxIterations}
 * @param requiredTools tools that must be called at least once before the agent may complete. If
 *     the model tries to stop without having called every required tool, the agent injects a USER
 *     guidance message naming the missing tools and loops. Defaults to empty
 * @param iterationHook programmatic control over agent completion. Invoked after each iteration in
 *     which the model wants to stop and the built-in {@code minIterations} and {@code
 *     requiredTools} guardrails have been satisfied. Defaults to {@code null} (no hook)
 * @param runStore durable storage for {@link ai.singlr.core.runtime.AgentRun} checkpoints. Opt-in;
 *     when {@code null}, the agent loop runs without durability and crashed runs cannot be resumed.
 *     Must be paired with {@code toolCallJournal}
 * @param toolCallJournal durable journal of tool invocations within a run. Required when {@code
 *     runStore} is set
 * @param idempotentToolsOverride deployer-side override for the {@link Tool#idempotent()} flag,
 *     keyed by tool name. Lets operators correct an author's classification without rebuilding the
 *     tool. The override wins when present
 * @param unsafeResumePolicy how {@code Agent.resume(...)} handles a non-idempotent tool that was
 *     in-flight at crash. Defaults to {@link UnsafeResumePolicy#FAIL_LOUD}
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
    List<SpanListener> spanListeners,
    FaultTolerance faultTolerance,
    String promptName,
    Integer promptVersion,
    TraceDetail traceDetail,
    boolean parallelToolExecution,
    int minIterations,
    Set<String> requiredTools,
    IterationHook iterationHook,
    RunStore runStore,
    ToolCallJournal toolCallJournal,
    Map<String, Boolean> idempotentToolsOverride,
    UnsafeResumePolicy unsafeResumePolicy) {

  private static final int DEFAULT_MAX_ITERATIONS = 10;
  private static final String DEFAULT_SYSTEM_PROMPT =
      """
			You are ${name}, an AI assistant with persistent memory.

			## Core Memory
			${core_memory}

			## Instructions
			- Use memory_update to store important facts and preferences
			- Core memory persists across conversations — keep it current and concise
			- Be helpful and accurate
			""";

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(AgentConfig config) {
    return new Builder(config);
  }

  /** Returns true if any trace or span listeners are configured. */
  public boolean tracingEnabled() {
    return (traceListeners != null && !traceListeners.isEmpty())
        || (spanListeners != null && !spanListeners.isEmpty());
  }

  /**
   * Returns true if both a {@link RunStore} and a {@link ToolCallJournal} are configured — the
   * paired prerequisite for durable runs and {@code Agent.resume(...)}.
   */
  public boolean durabilityEnabled() {
    return runStore != null && toolCallJournal != null;
  }

  /**
   * Returns the effective idempotency flag for the named tool, applying any {@link
   * #idempotentToolsOverride} on top of the tool's own {@link Tool#idempotent()} default. When the
   * tool is not present in this config's tool list, the override map is consulted directly.
   */
  public boolean isToolIdempotent(String toolName) {
    if (toolName == null) {
      return false;
    }
    var override = idempotentToolsOverride.get(toolName);
    if (override != null) {
      return override;
    }
    for (var t : tools) {
      if (toolName.equals(t.name())) {
        return t.idempotent();
      }
    }
    return false;
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
    private List<SpanListener> spanListeners = new ArrayList<>();
    private FaultTolerance faultTolerance = FaultTolerance.PASSTHROUGH;
    private String promptName;
    private Integer promptVersion;
    private TraceDetail traceDetail = TraceDetail.STANDARD;
    private boolean parallelToolExecution = false;
    private int minIterations = 0;
    private Set<String> requiredTools = new LinkedHashSet<>();
    private IterationHook iterationHook;
    private RunStore runStore;
    private ToolCallJournal toolCallJournal;
    private Map<String, Boolean> idempotentToolsOverride = new HashMap<>();
    private UnsafeResumePolicy unsafeResumePolicy = UnsafeResumePolicy.FAIL_LOUD;

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
      this.spanListeners = new ArrayList<>(config.spanListeners);
      this.faultTolerance = config.faultTolerance;
      this.promptName = config.promptName;
      this.promptVersion = config.promptVersion;
      this.traceDetail = config.traceDetail;
      this.parallelToolExecution = config.parallelToolExecution;
      this.minIterations = config.minIterations;
      this.requiredTools = new LinkedHashSet<>(config.requiredTools);
      this.iterationHook = config.iterationHook;
      this.runStore = config.runStore;
      this.toolCallJournal = config.toolCallJournal;
      this.idempotentToolsOverride = new HashMap<>(config.idempotentToolsOverride);
      this.unsafeResumePolicy = config.unsafeResumePolicy;
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

    public Builder withSpanListener(SpanListener listener) {
      this.spanListeners.add(listener);
      return this;
    }

    public Builder withSpanListeners(List<SpanListener> listeners) {
      this.spanListeners.addAll(listeners);
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

    public Builder withTraceDetail(TraceDetail traceDetail) {
      this.traceDetail = traceDetail;
      return this;
    }

    public Builder withParallelToolExecution(boolean parallel) {
      this.parallelToolExecution = parallel;
      return this;
    }

    public Builder withMinIterations(int minIterations) {
      this.minIterations = minIterations;
      return this;
    }

    public Builder withRequiredTools(String... tools) {
      for (var tool : tools) {
        this.requiredTools.add(tool);
      }
      return this;
    }

    public Builder withRequiredTools(Set<String> tools) {
      this.requiredTools.addAll(tools);
      return this;
    }

    public Builder withIterationHook(IterationHook iterationHook) {
      this.iterationHook = iterationHook;
      return this;
    }

    /**
     * Wire durable run storage. Both arguments are required and must be non-null; passing a {@code
     * null} pair (the default) disables durability for this config.
     */
    public Builder withDurability(RunStore runStore, ToolCallJournal toolCallJournal) {
      this.runStore = runStore;
      this.toolCallJournal = toolCallJournal;
      return this;
    }

    /**
     * Override the idempotency flag for a named tool. Wins over the tool's own {@link
     * Tool#idempotent()} default — the deployer-side escape hatch when the author got it wrong.
     *
     * @throws IllegalArgumentException if {@code toolName} is null or blank
     */
    public Builder withIdempotentToolOverride(String toolName, boolean idempotent) {
      if (toolName == null || toolName.isBlank()) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
      this.idempotentToolsOverride.put(toolName, idempotent);
      return this;
    }

    /**
     * Replace the entire idempotency override map. Useful when populating from configuration.
     *
     * @throws NullPointerException if {@code overrides} is null; pass {@code Map.of()} to clear
     */
    public Builder withIdempotentToolsOverride(Map<String, Boolean> overrides) {
      java.util.Objects.requireNonNull(overrides, "overrides; pass Map.of() to clear");
      for (var entry : overrides.entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank()) {
          throw new IllegalArgumentException("overrides keys must be non-blank tool names");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException(
              "overrides values must be non-null booleans (key: " + entry.getKey() + ")");
        }
      }
      this.idempotentToolsOverride = new HashMap<>(overrides);
      return this;
    }

    /**
     * Configure the policy for non-idempotent in-flight tool calls during {@code
     * Agent.resume(...)}.
     *
     * @throws NullPointerException if {@code policy} is null
     */
    public Builder withUnsafeResumePolicy(UnsafeResumePolicy policy) {
      this.unsafeResumePolicy = java.util.Objects.requireNonNull(policy, "policy");
      return this;
    }

    public AgentConfig build() {
      if (model == null) {
        throw new IllegalStateException("Model is required");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }
      if (minIterations < 0) {
        throw new IllegalStateException("minIterations must be >= 0");
      }
      if (minIterations > maxIterations) {
        throw new IllegalStateException("minIterations must be <= maxIterations");
      }
      for (var tool : requiredTools) {
        if (tool == null || tool.isBlank()) {
          throw new IllegalStateException("requiredTools entries must be non-blank");
        }
      }
      if ((runStore == null) != (toolCallJournal == null)) {
        throw new IllegalStateException(
            "runStore and toolCallJournal must be set together (both null = durability off)");
      }
      if (unsafeResumePolicy == null) {
        throw new IllegalStateException("unsafeResumePolicy must not be null");
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
          List.copyOf(spanListeners),
          faultTolerance,
          promptName,
          promptVersion,
          traceDetail,
          parallelToolExecution,
          minIterations,
          Set.copyOf(requiredTools),
          iterationHook,
          runStore,
          toolCallJournal,
          Map.copyOf(idempotentToolsOverride),
          unsafeResumePolicy);
    }
  }
}
