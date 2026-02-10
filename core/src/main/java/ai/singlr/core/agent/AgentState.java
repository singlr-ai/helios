/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable state of an agent during execution. Each step returns a new state.
 *
 * @param messages the current message history (system + conversation)
 * @param lastResponse the last response from the model
 * @param iterations number of iterations (tool call rounds)
 * @param isComplete whether the agent has finished
 * @param error error message if the run failed
 * @param userId the user this run belongs to (null for anonymous runs)
 * @param sessionId the session this run belongs to (null for stateless runs without memory)
 */
public record AgentState(
    List<Message> messages,
    Response lastResponse,
    int iterations,
    boolean isComplete,
    String error,
    String userId,
    UUID sessionId) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(AgentState state) {
    return new Builder(state);
  }

  /** Check if the state represents a successful completion. */
  public boolean isSuccess() {
    return isComplete && error == null;
  }

  /** Check if the state represents a failure. */
  public boolean isError() {
    return error != null;
  }

  /** Get the final response (last assistant message content). */
  public Response finalResponse() {
    return lastResponse;
  }

  /** Builder for AgentState. */
  public static class Builder {
    private List<Message> messages = new ArrayList<>();
    private Response lastResponse;
    private int iterations = 0;
    private boolean isComplete = false;
    private String error;
    private String userId;
    private UUID sessionId;

    private Builder() {}

    private Builder(AgentState state) {
      this.messages = new ArrayList<>(state.messages);
      this.lastResponse = state.lastResponse;
      this.iterations = state.iterations;
      this.isComplete = state.isComplete;
      this.error = state.error;
      this.userId = state.userId;
      this.sessionId = state.sessionId;
    }

    public Builder withMessages(List<Message> messages) {
      this.messages = new ArrayList<>(messages);
      return this;
    }

    public Builder addMessage(Message message) {
      this.messages.add(message);
      return this;
    }

    public Builder withLastResponse(Response response) {
      this.lastResponse = response;
      return this;
    }

    public Builder withIterations(int iterations) {
      this.iterations = iterations;
      return this;
    }

    public Builder incrementIterations() {
      this.iterations++;
      return this;
    }

    public Builder withComplete(boolean complete) {
      this.isComplete = complete;
      return this;
    }

    public Builder withError(String error) {
      this.error = error;
      this.isComplete = true;
      return this;
    }

    public Builder withUserId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder withSessionId(UUID sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public AgentState build() {
      return new AgentState(
          List.copyOf(messages), lastResponse, iterations, isComplete, error, userId, sessionId);
    }
  }
}
