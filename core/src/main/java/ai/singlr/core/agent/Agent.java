/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanBuilder;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.TraceBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main agent that orchestrates LLM calls and tool execution. Runs to completion by default, or
 * can be stepped manually.
 *
 * <p>Supports structured output via {@link OutputSchema}. When an output schema is provided, the
 * agent passes it through to the model on every call. The model may still invoke tools before
 * producing the final structured response.
 */
public class Agent {

  private final AgentConfig config;
  private final Map<String, Tool> toolMap;

  public Agent(AgentConfig config) {
    this.config = config;
    this.toolMap = new HashMap<>();
    for (var tool : config.allTools()) {
      toolMap.put(tool.name(), tool);
    }
  }

  public Result<Response> run(String userMessage) {
    return run(userMessage, Map.of());
  }

  public Result<Response> run(String userMessage, Map<String, String> promptVars) {
    var result = runLoop(userMessage, promptVars, null);
    if (result.isFailure()) {
      var failure = (Result.Failure<AgentState>) result;
      return Result.failure(failure.error(), failure.cause());
    }
    return Result.success(((Result.Success<AgentState>) result).value().finalResponse());
  }

  /**
   * Run the agent to completion with structured output.
   *
   * @param <T> the type of the structured output
   * @param userMessage the user's message
   * @param outputSchema the schema for structured output
   * @return the parsed structured response
   */
  public <T> Result<Response<T>> run(String userMessage, OutputSchema<T> outputSchema) {
    return run(userMessage, Map.of(), outputSchema);
  }

  /**
   * Run the agent to completion with structured output and prompt variables.
   *
   * @param <T> the type of the structured output
   * @param userMessage the user's message
   * @param promptVars variables to substitute in the system prompt template
   * @param outputSchema the schema for structured output
   * @return the parsed structured response
   */
  @SuppressWarnings("unchecked")
  public <T> Result<Response<T>> run(
      String userMessage, Map<String, String> promptVars, OutputSchema<T> outputSchema) {
    var result = runLoop(userMessage, promptVars, outputSchema);
    if (result.isFailure()) {
      var failure = (Result.Failure<AgentState>) result;
      return Result.failure(failure.error(), failure.cause());
    }
    var state = ((Result.Success<AgentState>) result).value();
    return Result.success((Response<T>) state.finalResponse());
  }

  /**
   * Execute a single step of the agent loop. Call model, execute any tool calls, return new state.
   *
   * @param state the current state of the agent
   */
  public Result<AgentState> step(AgentState state) {
    return step(state, null, null);
  }

  /**
   * Execute a single step with structured output.
   *
   * @param <T> the type of the structured output
   * @param state the current state of the agent
   * @param outputSchema the schema for structured output
   */
  public <T> Result<AgentState> step(AgentState state, OutputSchema<T> outputSchema) {
    return step(state, outputSchema, null);
  }

  Result<AgentState> step(
      AgentState state, OutputSchema<?> outputSchema, TraceBuilder traceBuilder) {
    if (state.isComplete()) {
      return Result.success(state);
    }

    if (state.iterations() >= config.maxIterations()) {
      return Result.success(
          AgentState.newBuilder(state)
              .withError("Max iterations (%d) reached".formatted(config.maxIterations()))
              .build());
    }

    SpanBuilder modelSpan = null;
    SpanBuilder toolSpan = null;

    try {
      if (traceBuilder != null) {
        modelSpan = traceBuilder.span("model.chat", SpanKind.MODEL_CALL);
        modelSpan.attribute("model", config.model().id());
      }

      var response = callModel(state.messages(), outputSchema);

      if (modelSpan != null) {
        if (response.usage() != null) {
          modelSpan.attribute("inputTokens", String.valueOf(response.usage().inputTokens()));
          modelSpan.attribute("outputTokens", String.valueOf(response.usage().outputTokens()));
        }
        modelSpan.end();
        modelSpan = null;
      }

      var newMessages = new ArrayList<>(state.messages());
      newMessages.add(response.toMessage());

      if (config.memory() != null) {
        config.memory().addMessage(response.toMessage());
      }

      if (!response.hasToolCalls()) {
        return Result.success(
            AgentState.newBuilder()
                .withMessages(newMessages)
                .withLastResponse(response)
                .withIterations(state.iterations() + 1)
                .withComplete(true)
                .build());
      }

      for (var toolCall : response.toolCalls()) {
        var tool = toolMap.get(toolCall.name());
        ToolResult toolResult;

        toolSpan = null;
        if (traceBuilder != null) {
          toolSpan = traceBuilder.span("tool." + toolCall.name(), SpanKind.TOOL_EXECUTION);
          toolSpan.attribute("toolName", toolCall.name());
          toolSpan.attribute("toolCallId", toolCall.id());
        }

        if (tool == null) {
          toolResult = ToolResult.failure("Unknown tool: " + toolCall.name());
          if (toolSpan != null) {
            toolSpan.fail("Unknown tool: " + toolCall.name());
            toolSpan = null;
          }
        } else {
          toolResult =
              config.faultTolerance() != null
                  ? config.faultTolerance().execute(() -> tool.execute(toolCall.arguments()))
                  : tool.execute(toolCall.arguments());
          if (toolSpan != null) {
            if (toolResult.success()) {
              toolSpan.end();
            } else {
              toolSpan.fail(toolResult.output());
            }
            toolSpan = null;
          }
        }

        var toolMessage = Message.tool(toolCall.id(), toolCall.name(), toolResult.output());
        newMessages.add(toolMessage);

        if (config.memory() != null) {
          config.memory().addMessage(toolMessage);
        }
      }

      return Result.success(
          AgentState.newBuilder()
              .withMessages(newMessages)
              .withLastResponse(response)
              .withIterations(state.iterations() + 1)
              .withComplete(false)
              .build());

    } catch (Exception e) {
      if (modelSpan != null) {
        modelSpan.fail(e.getMessage());
      }
      if (toolSpan != null) {
        toolSpan.fail(e.getMessage());
      }
      return Result.failure("Agent step failed: " + e.getMessage(), e);
    }
  }

  public AgentState initialState(String userMessage, Map<String, String> promptVars) {
    var messages = new ArrayList<Message>();
    var systemPrompt = buildSystemPrompt(promptVars);
    messages.add(Message.system(systemPrompt));

    if (config.memory() != null) {
      messages.addAll(config.memory().history());
    }

    var userMsg = Message.user(userMessage);
    messages.add(userMsg);

    if (config.memory() != null) {
      config.memory().addMessage(userMsg);
    }

    return AgentState.newBuilder().withMessages(messages).build();
  }

  private Result<AgentState> runLoop(
      String userMessage, Map<String, String> promptVars, OutputSchema<?> outputSchema) {
    var state = initialState(userMessage, promptVars);
    var traceBuilder =
        config.tracingEnabled() ? TraceBuilder.start(config.name(), config.traceListeners()) : null;

    while (!state.isComplete()) {
      var result = step(state, outputSchema, traceBuilder);
      if (result.isFailure()) {
        var failure = (Result.Failure<AgentState>) result;
        if (traceBuilder != null) {
          traceBuilder.fail(failure.error());
        }
        return Result.failure(failure.error(), failure.cause());
      }
      state = ((Result.Success<AgentState>) result).value();
    }

    if (state.isError()) {
      if (traceBuilder != null) {
        traceBuilder.fail(state.error());
      }
      return Result.failure(state.error());
    }

    if (traceBuilder != null) {
      traceBuilder.end();
    }
    return Result.success(state);
  }

  private Response<?> callModel(List<Message> messages, OutputSchema<?> outputSchema)
      throws Exception {
    if (outputSchema != null) {
      return config.faultTolerance() != null
          ? config
              .faultTolerance()
              .execute(() -> config.model().chat(messages, config.allTools(), outputSchema))
          : config.model().chat(messages, config.allTools(), outputSchema);
    }
    return config.faultTolerance() != null
        ? config.faultTolerance().execute(() -> config.model().chat(messages, config.allTools()))
        : config.model().chat(messages, config.allTools());
  }

  private String buildSystemPrompt(Map<String, String> extraVars) {
    var vars = new HashMap<String, String>();
    vars.put("name", config.name());

    if (config.memory() != null) {
      vars.put("core_memory", config.memory().renderCoreMemory());
    } else {
      vars.put("core_memory", "(no memory configured)");
    }

    vars.putAll(extraVars);
    return Strings.render(config.systemPrompt(), vars);
  }

  public AgentConfig config() {
    return config;
  }

  public List<Tool> tools() {
    return config.allTools();
  }
}
