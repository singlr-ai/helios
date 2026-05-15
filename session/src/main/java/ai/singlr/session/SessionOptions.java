/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Model;
import ai.singlr.session.hooks.Hook;
import ai.singlr.session.memory.MemoryBackend;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * @param tools the tool registry the loop advertises to the model and dispatches against; non-null,
 *     defaults to {@link ToolRegistry#empty()}
 * @param hooks the hooks fired at lifecycle phases (pre/post-model-turn, pre/post-tool-use,
 *     pre-stop, on-user-message, on-stream-event); non-null, defaults to {@link List#of()}
 * @param permission optional permission policy; when present, an internal {@code
 *     DefaultPermissionEvaluator} runs as a {@code PreToolUseHook} at priority 50 (before
 *     user-supplied hooks) and gates each tool dispatch through the policy
 * @param memoryBackend optional persistent memory backend; when present, the session auto-registers
 *     the {@code MemoryRead} tool so the model can view {@code /memories/...} files
 */
public record SessionOptions(
    Model model,
    String sessionId,
    SessionLimits limits,
    ConcurrencyLimits concurrency,
    Clock clock,
    ToolRegistry tools,
    List<Hook> hooks,
    Optional<Permission> permission,
    Optional<MemoryBackend> memoryBackend) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionOptions {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(concurrency, "concurrency must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(hooks, "hooks must not be null");
    hooks = List.copyOf(hooks);
    Objects.requireNonNull(permission, "permission must not be null");
    Objects.requireNonNull(memoryBackend, "memoryBackend must not be null");
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
        .withClock(clock)
        .withTools(tools)
        .withHooks(hooks)
        .withPermission(permission.orElse(null))
        .withMemoryBackend(memoryBackend.orElse(null));
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
    private ToolRegistry tools = ToolRegistry.empty();
    private List<Hook> hooks = List.of();
    private Permission permission;
    private MemoryBackend memoryBackend;

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
      if (Strings.isBlank(sessionId)) {
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
     * Set the tool registry the loop advertises to the model and dispatches against. Defaults to an
     * empty registry — Phase 2 text-only sessions leave this alone.
     *
     * @param tools non-null registry
     * @return this builder
     * @throws NullPointerException if {@code tools} is null
     */
    public Builder withTools(ToolRegistry tools) {
      this.tools = Objects.requireNonNull(tools, "tools must not be null");
      return this;
    }

    /**
     * Set the full hook list. Replaces any previously-added hooks. Defaults to empty.
     *
     * @param hooks non-null list of hooks
     * @return this builder
     * @throws NullPointerException if {@code hooks} is null or contains null elements
     */
    public Builder withHooks(List<Hook> hooks) {
      Objects.requireNonNull(hooks, "hooks must not be null");
      for (var h : hooks) {
        Objects.requireNonNull(h, "hooks must not contain null");
      }
      this.hooks = List.copyOf(hooks);
      return this;
    }

    /**
     * Append a single hook. Useful when registering hooks one at a time.
     *
     * @param hook non-null hook
     * @return this builder
     * @throws NullPointerException if {@code hook} is null
     */
    public Builder withHook(Hook hook) {
      Objects.requireNonNull(hook, "hook must not be null");
      var copy = new java.util.ArrayList<Hook>(hooks);
      copy.add(hook);
      this.hooks = List.copyOf(copy);
      return this;
    }

    /**
     * Set (or clear) the permission policy. Pass {@code null} to clear. When non-null, an internal
     * {@code DefaultPermissionEvaluator} runs as a {@code PreToolUseHook} at priority 50 before
     * user-supplied hooks.
     *
     * @param permission nullable policy
     * @return this builder
     */
    public Builder withPermission(Permission permission) {
      this.permission = permission;
      return this;
    }

    /**
     * Set (or clear) the memory backend. Pass {@code null} to clear. When non-null, the session
     * auto-registers a {@code MemoryRead} tool wired to this backend.
     *
     * @param memoryBackend nullable backend
     * @return this builder
     */
    public Builder withMemoryBackend(MemoryBackend memoryBackend) {
      this.memoryBackend = memoryBackend;
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
      var id = sessionId != null ? sessionId : "sess-" + Ids.newId();
      return new SessionOptions(
          model,
          id,
          limits,
          concurrency,
          clock,
          tools,
          hooks,
          Optional.ofNullable(permission),
          Optional.ofNullable(memoryBackend));
    }
  }
}
