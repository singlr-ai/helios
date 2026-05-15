/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Model;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Composition record bundling everything needed to construct an {@link AgentSession}.
 *
 * <p>Phase 1 carries the minimum set of fields the agent loop actually wires: the model, the
 * session id, the limits, the concurrency caps, and the clock. Subsequent phases extend the record
 * as their subsystems land — Phase 2 adds {@code tools} and {@code hooks}; Phase 3 adds {@code
 * workspace}; Phase 4 adds {@code memory}; Phase 5 adds {@code execution}; Phase 7 adds {@code
 * audit}; etc. Because v2 is clean-slate (no backwards-compat shims), the record's shape evolves
 * with the phases.
 *
 * <p>Build via {@link #newBuilder()} — every optional field has a sensible default, and {@code
 * model} is the only required input.
 *
 * @param model the LLM the session loop drives; non-null
 * @param sessionId stable, non-blank session id; auto-generated UUID if not set on the builder
 * @param limits per-session ceilings; non-null
 * @param concurrency per-session concurrency caps; non-null
 * @param clock clock supplying event timestamps and elapsed; non-null
 */
public record SessionOptions(
    Model model,
    String sessionId,
    SessionLimits limits,
    ConcurrencyLimits concurrency,
    Clock clock) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionOptions {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(concurrency, "concurrency must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Start building a {@code SessionOptions}.
   *
   * @return a fresh builder with defaults applied for every optional field
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Start building a {@code SessionOptions} pre-populated with the values from this instance.
   * Convenience for callers that want to derive a variant of an existing options bundle.
   *
   * @return a builder seeded with this record's values
   */
  public Builder toBuilder() {
    return new Builder()
        .withModel(model)
        .withSessionId(sessionId)
        .withLimits(limits)
        .withConcurrencyLimits(concurrency)
        .withClock(clock);
  }

  /**
   * Mutable builder for {@link SessionOptions}. Every {@code with*} setter returns {@code this} so
   * the API chains; {@link #build()} validates and produces the immutable record. Unset fields use
   * the defaults documented on each setter.
   */
  public static final class Builder {

    private Model model;
    private String sessionId;
    private SessionLimits limits = SessionLimits.defaults();
    private ConcurrencyLimits concurrency = ConcurrencyLimits.defaults();
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /**
     * Set the model. Required.
     *
     * @param model non-null model
     * @return this builder
     * @throws NullPointerException if {@code model} is null
     */
    public Builder withModel(Model model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    /**
     * Set the session id. Defaults to a fresh UUID if not set.
     *
     * @param sessionId non-blank id
     * @return this builder
     * @throws NullPointerException if {@code sessionId} is null
     * @throws IllegalArgumentException if {@code sessionId} is blank
     */
    public Builder withSessionId(String sessionId) {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      if (sessionId.isBlank()) {
        throw new IllegalArgumentException("sessionId must not be blank");
      }
      this.sessionId = sessionId;
      return this;
    }

    /**
     * Set the session limits. Defaults to {@link SessionLimits#defaults()}.
     *
     * @param limits non-null limits
     * @return this builder
     * @throws NullPointerException if {@code limits} is null
     */
    public Builder withLimits(SessionLimits limits) {
      this.limits = Objects.requireNonNull(limits, "limits must not be null");
      return this;
    }

    /**
     * Set the concurrency caps. Defaults to {@link ConcurrencyLimits#defaults()}.
     *
     * @param concurrency non-null caps
     * @return this builder
     * @throws NullPointerException if {@code concurrency} is null
     */
    public Builder withConcurrencyLimits(ConcurrencyLimits concurrency) {
      this.concurrency = Objects.requireNonNull(concurrency, "concurrency must not be null");
      return this;
    }

    /**
     * Set the clock. Defaults to {@link Clock#systemUTC()}. Tests pass a fixed clock for
     * deterministic timestamps.
     *
     * @param clock non-null clock
     * @return this builder
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder withClock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Build the immutable record.
     *
     * @return the options
     * @throws IllegalStateException if {@code model} was never set
     */
    public SessionOptions build() {
      if (model == null) {
        throw new IllegalStateException("model is required — call withModel before build");
      }
      var id = sessionId != null ? sessionId : "sess-" + UUID.randomUUID();
      return new SessionOptions(model, id, limits, concurrency, clock);
    }
  }
}
