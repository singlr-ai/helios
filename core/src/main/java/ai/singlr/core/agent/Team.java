/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Result;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.TraceDetail;
import ai.singlr.core.trace.TraceListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A multi-agent team where a leader agent delegates tasks to worker agents. Workers are exposed to
 * the leader as callable tools — when the leader calls a worker tool, the framework runs the worker
 * agent and returns its response.
 *
 * <p>The leader orchestrates naturally through the existing agent loop: it decides which workers to
 * call, synthesizes their results, and produces a final response.
 *
 * <pre>{@code
 * var team = Team.newBuilder()
 *     .withName("content-team")
 *     .withModel(model)
 *     .withSystemPrompt("You lead a content creation team.")
 *     .withWorker("researcher", "Finds information", researcherAgent)
 *     .withWorker("writer", "Writes content", writerAgent)
 *     .build();
 *
 * Result<Response> result = team.run("Write a blog post about Java");
 * }</pre>
 */
public class Team {

  private final String name;
  private final Agent leader;
  private final Map<String, Agent> workers;

  private Team(String name, Agent leader, Map<String, Agent> workers) {
    this.name = name;
    this.leader = leader;
    this.workers = workers;
  }

  /**
   * Run the team to completion with a simple user input.
   *
   * @param userInput the user's message
   * @return the leader's final response
   */
  public Result<Response> run(String userInput) {
    return leader.run(userInput);
  }

  /**
   * Run the team to completion within a session.
   *
   * @param session the session context
   * @return the leader's final response
   */
  public Result<Response> run(SessionContext session) {
    return leader.run(session);
  }

  /**
   * Stream the team leader's response for a simple user input.
   *
   * @param userInput the user's message
   * @return closeable iterator of stream events from the leader
   */
  public CloseableIterator<StreamEvent> runStream(String userInput) {
    return leader.runStream(userInput);
  }

  /**
   * Stream the team leader's response within a session.
   *
   * @param session the session context
   * @return closeable iterator of stream events from the leader
   */
  public CloseableIterator<StreamEvent> runStream(SessionContext session) {
    return leader.runStream(session);
  }

  /**
   * Run the team to completion within a session, with structured output.
   *
   * @param <T> the type of the structured output
   * @param session the session context
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  public <T> Result<Response<T>> run(SessionContext session, OutputSchema<T> outputSchema) {
    return leader.run(session, outputSchema);
  }

  /** The team name. */
  public String name() {
    return name;
  }

  /** The leader agent. */
  public Agent leader() {
    return leader;
  }

  /** The worker agents, keyed by name. */
  public Map<String, Agent> workers() {
    return workers;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** A worker agent with its delegation name and description. */
  public record Worker(String name, String description, Agent agent) {}

  public static class Builder {
    private String name = "Team";
    private Model model;
    private String systemPrompt = "You are a team leader. Delegate tasks to your team members.";
    private final List<Worker> workers = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private Memory memory;
    private int maxIterations = 10;
    private boolean includeMemoryTools = true;
    private final List<TraceListener> traceListeners = new ArrayList<>();
    private FaultTolerance faultTolerance = FaultTolerance.PASSTHROUGH;
    private TraceDetail traceDetail = TraceDetail.STANDARD;

    private Builder() {}

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

    /**
     * Add a worker agent to the team.
     *
     * @param name the tool name the leader uses to delegate to this worker
     * @param description what this worker does (shown to the leader model)
     * @param agent the worker agent
     */
    public Builder withWorker(String name, String description, Agent agent) {
      this.workers.add(new Worker(name, description, agent));
      return this;
    }

    /** Add a direct tool available to the leader (not a worker delegation). */
    public Builder withTool(Tool tool) {
      this.tools.add(tool);
      return this;
    }

    /** Add direct tools available to the leader. */
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

    public Builder withTraceDetail(TraceDetail traceDetail) {
      this.traceDetail = traceDetail;
      return this;
    }

    public Team build() {
      if (model == null) {
        throw new IllegalStateException("Model is required");
      }
      if (workers.isEmpty()) {
        throw new IllegalStateException("At least one worker is required");
      }
      if (maxIterations < 1) {
        throw new IllegalStateException("maxIterations must be >= 1");
      }

      var toolNames = new HashSet<String>();
      for (var tool : tools) {
        toolNames.add(tool.name());
      }
      for (var worker : workers) {
        if (toolNames.contains(worker.name())) {
          throw new IllegalStateException(
              "Worker name '%s' conflicts with a direct tool name".formatted(worker.name()));
        }
      }

      var allTools = new ArrayList<>(tools);
      var workerMap = new LinkedHashMap<String, Agent>();

      for (var worker : workers) {
        workerMap.put(worker.name(), worker.agent());
        allTools.add(buildDelegationTool(worker));
      }

      var enrichedPrompt = appendWorkerDescriptions(systemPrompt);

      var config =
          AgentConfig.newBuilder()
              .withName(name)
              .withModel(model)
              .withSystemPrompt(enrichedPrompt)
              .withTools(allTools)
              .withMemory(memory)
              .withMaxIterations(maxIterations)
              .withIncludeMemoryTools(includeMemoryTools)
              .withTraceListeners(traceListeners)
              .withFaultTolerance(faultTolerance)
              .withTraceDetail(traceDetail)
              .build();

      return new Team(name, new Agent(config), Map.copyOf(workerMap));
    }

    private Tool buildDelegationTool(Worker worker) {
      return Tool.newBuilder()
          .withName(worker.name())
          .withDescription(worker.description())
          .withParameter(
              ToolParameter.newBuilder()
                  .withName("task")
                  .withType(ParameterType.STRING)
                  .withDescription("The task to delegate to this team member")
                  .withRequired(true)
                  .build())
          .withExecutor(
              args -> {
                var task = (String) args.get("task");
                var result = worker.agent().run(task);
                return switch (result) {
                  case Result.Success<Response> s -> ToolResult.success(s.value().content());
                  case Result.Failure<Response> f -> ToolResult.failure(f.error());
                };
              })
          .build();
    }

    private String appendWorkerDescriptions(String prompt) {
      var sb = new StringBuilder(prompt);
      sb.append("\n\n## Team Members\n");
      sb.append("You can delegate tasks to these team members by calling their tool:\n");
      for (var worker : workers) {
        sb.append("- ")
            .append(worker.name())
            .append(": ")
            .append(worker.description())
            .append("\n");
      }
      sb.append(
          "\nDelegate to the right specialist for each subtask."
              + " You can call multiple members and synthesize their results.");
      return sb.toString();
    }
  }
}
