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
 * Session context for agent runs. Ties a conversation to a session ID and carries optional metadata
 * (e.g., group ID for evals).
 *
 * @param sessionId unique session identifier
 * @param metadata arbitrary key-value pairs (e.g., "groupId" for eval batches)
 */
public record SessionContext(UUID sessionId, Map<String, String> metadata) {

  /** Create a new session with a generated UUID v7 and empty metadata. */
  public static SessionContext create() {
    return new SessionContext(Ids.newId(), Map.of());
  }

  /** Wrap an existing session ID with empty metadata. */
  public static SessionContext of(UUID sessionId) {
    return new SessionContext(sessionId, Map.of());
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private UUID sessionId;
    private Map<String, String> metadata = new HashMap<>();

    private Builder() {}

    public Builder withSessionId(UUID sessionId) {
      this.sessionId = sessionId;
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
      return new SessionContext(sessionId, Map.copyOf(metadata));
    }
  }
}
