/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Ids;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session context for agent runs. Carries the user input, prompt variables, and session metadata.
 *
 * @param sessionId unique session identifier
 * @param userInput the user's message for this turn
 * @param promptVars template variable substitutions for the system prompt
 * @param metadata arbitrary key-value pairs (e.g., "groupId" for eval batches)
 */
public record SessionContext(
    UUID sessionId,
    String userInput,
    Map<String, String> promptVars,
    Map<String, String> metadata) {

  /** Create a session with a generated UUID v7 and the given user input. */
  public static SessionContext of(String userInput) {
    return new SessionContext(Ids.newId(), userInput, Map.of(), Map.of());
  }

  /** Create a session with a generated UUID v7, user input, and prompt variables. */
  public static SessionContext of(String userInput, Map<String, String> promptVars) {
    return new SessionContext(Ids.newId(), userInput, Map.copyOf(promptVars), Map.of());
  }

  /** Create a session with an existing session ID and user input. */
  public static SessionContext of(UUID sessionId, String userInput) {
    return new SessionContext(sessionId, userInput, Map.of(), Map.of());
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private UUID sessionId;
    private String userInput;
    private Map<String, String> promptVars = new HashMap<>();
    private Map<String, String> metadata = new HashMap<>();

    private Builder() {}

    public Builder withSessionId(UUID sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder withUserInput(String userInput) {
      this.userInput = userInput;
      return this;
    }

    public Builder withPromptVars(Map<String, String> promptVars) {
      this.promptVars = promptVars != null ? new HashMap<>(promptVars) : new HashMap<>();
      return this;
    }

    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
      return this;
    }

    public Builder withGroupId(String groupId) {
      this.metadata.put("groupId", groupId);
      return this;
    }

    public SessionContext build() {
      if (sessionId == null) {
        sessionId = Ids.newId();
      }
      return new SessionContext(sessionId, userInput, Map.copyOf(promptVars), Map.copyOf(metadata));
    }
  }
}
