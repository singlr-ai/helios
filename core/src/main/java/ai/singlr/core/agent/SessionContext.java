/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.common.Ids;
import ai.singlr.core.model.InlineFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session context for agent runs. Carries the user input, prompt variables, and session metadata.
 *
 * @param userId the user this session belongs to (nullable for anonymous/stateless runs)
 * @param sessionId unique session identifier
 * @param userInput the user's message for this turn
 * @param promptVars template variable substitutions for the system prompt
 * @param metadata arbitrary key-value pairs (e.g., "groupId" for eval batches)
 * @param inlineFiles inline file attachments for multimodal input
 */
public record SessionContext(
    String userId,
    UUID sessionId,
    String userInput,
    Map<String, String> promptVars,
    Map<String, String> metadata,
    List<InlineFile> inlineFiles) {

  /** Create a session with a generated UUID v7 and the given user input. */
  public static SessionContext of(String userInput) {
    return new SessionContext(null, Ids.newId(), userInput, Map.of(), Map.of(), List.of());
  }

  /** Create a session with a generated UUID v7, user input, and prompt variables. */
  public static SessionContext of(String userInput, Map<String, String> promptVars) {
    return new SessionContext(
        null, Ids.newId(), userInput, Map.copyOf(promptVars), Map.of(), List.of());
  }

  /** Create a session with an existing session ID and user input. */
  public static SessionContext of(UUID sessionId, String userInput) {
    return new SessionContext(null, sessionId, userInput, Map.of(), Map.of(), List.of());
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String userId;
    private UUID sessionId;
    private String userInput;
    private Map<String, String> promptVars = new HashMap<>();
    private Map<String, String> metadata = new HashMap<>();
    private List<InlineFile> inlineFiles = new ArrayList<>();

    private Builder() {}

    public Builder withUserId(String userId) {
      this.userId = userId;
      return this;
    }

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

    public Builder withInlineFile(byte[] data, String mimeType) {
      this.inlineFiles.add(InlineFile.of(data, mimeType));
      return this;
    }

    public Builder withInlineFiles(List<InlineFile> inlineFiles) {
      this.inlineFiles = inlineFiles != null ? new ArrayList<>(inlineFiles) : new ArrayList<>();
      return this;
    }

    public SessionContext build() {
      if (sessionId == null) {
        sessionId = Ids.newId();
      }
      return new SessionContext(
          userId,
          sessionId,
          userInput,
          Map.copyOf(promptVars),
          Map.copyOf(metadata),
          List.copyOf(inlineFiles));
    }
  }
}
