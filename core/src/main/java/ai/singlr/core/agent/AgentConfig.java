/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.events.EventSink;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.memory.MemoryRefreshPolicy;
import ai.singlr.core.model.Model;
import ai.singlr.core.runtime.Durability;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.trace.TraceDetail;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * @param eventSinks unified event-stream sinks receiving {@link ai.singlr.core.events.HeliosEvent}s
 *     for every run hook (run lifecycle, iteration boundaries, assistant text and thinking, tool
 *     calls, span open/close, memory writes, sub-agent delegation, compaction). The single
 *     observability SPI for Helios. When non-empty, a trace is materialized so {@code RunCompleted
 *     / RunFailed} can carry it
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
 * @param durability crash-safe execution config. {@code null} = no durability (default); set via
 *     {@link Builder#withDurability(Durability)} with {@link Durability#inMemory()}, {@code
 *     PgDurability.of(pgConfig)}, or {@link Durability#newBuilder()}
 * @param structuredOutputRetry when true (default), a {@link
 *     ai.singlr.core.schema.StructuredOutputParseException} thrown from a structured-output {@code
 *     model.chat(...)} call is converted into a corrective USER message (carrying the field-level
 *     diff produced by {@link ai.singlr.core.schema.SchemaValidator}) and the agent loop continues
 *     instead of failing. The next iteration re-invokes the model with the correction visible.
 *     Bounded by {@code maxIterations}. Set false to recover the pre-1.3.x hard-fail-on-parse
 *     behavior
 * @param memoryRefreshPolicy when {@link MemoryRefreshPolicy#PER_ITERATION} (default), the system
 *     prompt re-renders {@code ${core_memory}} every iteration when memory has changed — responsive
 *     but cache-hostile. {@link MemoryRefreshPolicy#PER_SESSION} freezes the system prompt at
 *     session start, preserving Anthropic prefix caches at the cost of mid-run memory write
 *     visibility
 * @param contextCompactor the strategy that decides how the message list is compacted when context
 *     fills up. Defaults to {@link DefaultContextCompactor}; pass an alternative implementation to
 *     swap strategies wholesale or {@link NoOpContextCompactor#INSTANCE} to disable compaction
 */
public record AgentConfig(
    String name,
    Model model,
    String systemPrompt,
    List<Tool> tools,
    Memory memory,
    int maxIterations,
    boolean includeMemoryTools,
    FaultTolerance faultTolerance,
    String promptName,
    Integer promptVersion,
    TraceDetail traceDetail,
    boolean parallelToolExecution,
    int minIterations,
    Set<String> requiredTools,
    IterationHook iterationHook,
    Durability durability,
    boolean structuredOutputRetry,
    MemoryRefreshPolicy memoryRefreshPolicy,
    ContextCompactor contextCompactor,
    List<EventSink> eventSinks) {

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

  /**
   * Returns true if at least one {@link ai.singlr.core.events.EventSink} is registered. Event sinks
   * receive the terminal Trace via {@link ai.singlr.core.events.HeliosEvent.RunCompleted} / {@link
   * ai.singlr.core.events.HeliosEvent.RunFailed} plus per-span lifecycle events, so their presence
   * drives trace materialization.
   */
  public boolean tracingEnabled() {
    return eventSinks != null && !eventSinks.isEmpty();
  }

  /**
   * Returns true if a {@link Durability} bundle is configured — the prerequisite for crash-safe
   * runs and {@code Agent.resume(...)}.
   */
  public boolean durabilityEnabled() {
    return durability != null;
  }

  /**
   * Returns the effective idempotency flag for the named tool, applying any deployer-supplied
   * override from {@link Durability#idempotentToolsOverride()} on top of the tool's own {@link
   * Tool#idempotent()} default.
   */
  public boolean isToolIdempotent(String toolName) {
    if (toolName == null) {
      return false;
    }
    if (durability != null) {
      var override = durability.idempotentOverride(toolName);
      if (override != null) {
        return override;
      }
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
    private FaultTolerance faultTolerance = FaultTolerance.PASSTHROUGH;
    private String promptName;
    private Integer promptVersion;
    private TraceDetail traceDetail = TraceDetail.STANDARD;
    private boolean parallelToolExecution = false;
    private int minIterations = 0;
    private Set<String> requiredTools = new LinkedHashSet<>();
    private IterationHook iterationHook;
    private Durability durability;
    private boolean structuredOutputRetry = true;
    private MemoryRefreshPolicy memoryRefreshPolicy = MemoryRefreshPolicy.PER_ITERATION;
    private ContextCompactor contextCompactor;
    private List<EventSink> eventSinks = new ArrayList<>();

    private Builder() {}

    private Builder(AgentConfig config) {
      this.name = config.name;
      this.model = config.model;
      this.systemPrompt = config.systemPrompt;
      this.tools = new ArrayList<>(config.tools);
      this.memory = config.memory;
      this.maxIterations = config.maxIterations;
      this.includeMemoryTools = config.includeMemoryTools;
      this.faultTolerance = config.faultTolerance;
      this.promptName = config.promptName;
      this.promptVersion = config.promptVersion;
      this.traceDetail = config.traceDetail;
      this.parallelToolExecution = config.parallelToolExecution;
      this.minIterations = config.minIterations;
      this.requiredTools = new LinkedHashSet<>(config.requiredTools);
      this.iterationHook = config.iterationHook;
      this.durability = config.durability;
      this.structuredOutputRetry = config.structuredOutputRetry;
      this.memoryRefreshPolicy = config.memoryRefreshPolicy;
      this.contextCompactor = config.contextCompactor;
      this.eventSinks =
          config.eventSinks == null ? new ArrayList<>() : new ArrayList<>(config.eventSinks);
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

    /**
     * Registers an {@link EventSink} to receive the unified Helios event stream for this agent's
     * run. Multiple sinks are supported; each receives every event in run order. This is the single
     * observability SPI for Helios.
     */
    public Builder withEventSink(EventSink sink) {
      this.eventSinks.add(sink);
      return this;
    }

    /** Bulk variant of {@link #withEventSink}. */
    public Builder withEventSinks(List<EventSink> sinks) {
      this.eventSinks.addAll(sinks);
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
     * Opt into crash-safe execution. Pass {@link Durability#inMemory()} for tests, {@code
     * PgDurability.of(pgConfig)} for Postgres-backed production, or {@link Durability#newBuilder()}
     * for custom configuration. Pass {@code null} to disable durability (the default).
     */
    public Builder withDurability(Durability durability) {
      this.durability = durability;
      return this;
    }

    /**
     * Toggle in-loop recovery from structured-output schema mismatches. When {@code true} (the
     * default), a {@link ai.singlr.core.schema.StructuredOutputParseException} thrown from {@code
     * model.chat(..., outputSchema)} is caught by the agent loop, converted into a corrective USER
     * turn carrying the {@link ai.singlr.core.schema.SchemaValidator} diff, and the loop continues
     * up to {@code maxIterations}. When {@code false}, the run fails terminally on the first parse
     * mismatch — match this for callers that prefer hard-fail semantics or that already drive their
     * own parse-correction protocol.
     */
    public Builder withStructuredOutputRetry(boolean enabled) {
      this.structuredOutputRetry = enabled;
      return this;
    }

    /**
     * Choose when {@code ${core_memory}} is re-rendered into the system prompt. {@link
     * MemoryRefreshPolicy#PER_ITERATION} is the default (responsive; cache-invalidating); switch to
     * {@link MemoryRefreshPolicy#PER_SESSION} for cache-sensitive deployments that don't need
     * mid-run memory writes to flow into the prompt immediately.
     */
    public Builder withMemoryRefreshPolicy(MemoryRefreshPolicy policy) {
      if (policy == null) {
        throw new IllegalArgumentException("policy must not be null");
      }
      this.memoryRefreshPolicy = policy;
      return this;
    }

    /**
     * Swap the compaction strategy. Pass any {@link ContextCompactor} implementation — e.g. {@link
     * NoOpContextCompactor#INSTANCE} to disable compaction wholesale, or a custom {@link
     * DefaultContextCompactor} with tuned thresholds. When unset, the agent builds a {@link
     * DefaultContextCompactor} with default settings using the configured model.
     */
    public Builder withContextCompactor(ContextCompactor compactor) {
      this.contextCompactor = compactor;
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
      var compactor =
          contextCompactor != null ? contextCompactor : new DefaultContextCompactor(model, tools);
      return new AgentConfig(
          name,
          model,
          systemPrompt,
          List.copyOf(tools),
          memory,
          maxIterations,
          includeMemoryTools,
          faultTolerance,
          promptName,
          promptVersion,
          traceDetail,
          parallelToolExecution,
          minIterations,
          Set.copyOf(requiredTools),
          iterationHook,
          durability,
          structuredOutputRetry,
          memoryRefreshPolicy,
          compactor,
          List.copyOf(eventSinks));
    }
  }
}
