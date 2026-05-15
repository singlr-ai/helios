/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.common.Strings;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome returned by a {@link Hook} for the non-observe phases (every phase except {@link
 * OnStreamEventHook}). Five subtypes carry the five interpretations the agent loop applies.
 *
 * <p>Semantics depend on phase — see the table in spec §9.3 for the full matrix. Short version:
 *
 * <ul>
 *   <li>{@link Continue} — let the loop proceed.
 *   <li>{@link MutateInput} — rewrite the input the loop was about to use (tool args or user
 *       message text). Not applicable to every phase.
 *   <li>{@link Block} — refuse the action, emit a blocked event. Currently used by {@code
 *       PreToolUseHook} and {@code OnUserMessageHook}; spec §9.3 marks it n/a for other phases.
 *   <li>{@link Inject} — inject a synthetic user message before the next turn instead of proceeding
 *       with the current action.
 *   <li>{@link Stop} — terminate the session with the given result string.
 * </ul>
 *
 * <p>The {@link #cont()} / {@link #mutate(Map)} / {@link #block(String)} / {@link #inject(String)}
 * / {@link #stop(String)} factories are the ergonomic surface for hook authors.
 */
public sealed interface HookOutcome
    permits HookOutcome.Continue,
        HookOutcome.MutateInput,
        HookOutcome.Block,
        HookOutcome.Inject,
        HookOutcome.Stop {

  /**
   * Singleton {@link Continue} outcome. Cheaper than allocating a fresh record per hook fire.
   *
   * @return the shared continue value
   */
  static HookOutcome cont() {
    return Continue.INSTANCE;
  }

  /**
   * Mutation outcome carrying a fresh input map. Used by {@code PreToolUseHook} to rewrite tool
   * arguments and {@code OnUserMessageHook} to rewrite user-message text (key {@code "text"}).
   *
   * @param newInput the replacement input; non-null
   * @return a fresh {@link MutateInput}
   * @throws NullPointerException if {@code newInput} is null
   */
  static HookOutcome mutate(Map<String, Object> newInput) {
    return new MutateInput(newInput);
  }

  /**
   * Block outcome that records a human-readable reason.
   *
   * @param reason non-blank explanation
   * @return a fresh {@link Block}
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  static HookOutcome block(String reason) {
    return new Block(reason);
  }

  /**
   * Inject outcome that queues the given text as a synthetic user message.
   *
   * @param userMessage non-blank text
   * @return a fresh {@link Inject}
   * @throws NullPointerException if {@code userMessage} is null
   * @throws IllegalArgumentException if {@code userMessage} is blank
   */
  static HookOutcome inject(String userMessage) {
    return new Inject(userMessage);
  }

  /**
   * Stop outcome that terminates the session with the given result.
   *
   * @param result non-blank terminal result string
   * @return a fresh {@link Stop}
   * @throws NullPointerException if {@code result} is null
   * @throws IllegalArgumentException if {@code result} is blank
   */
  static HookOutcome stop(String result) {
    return new Stop(result);
  }

  /** Proceed with the action the hook was inspecting. */
  record Continue() implements HookOutcome {

    static final Continue INSTANCE = new Continue();
  }

  /**
   * Rewrite the input the loop was about to use.
   *
   * @param newInput the replacement input; non-null
   */
  record MutateInput(Map<String, Object> newInput) implements HookOutcome {

    /**
     * Canonical constructor; defensively copies {@code newInput}.
     *
     * @throws NullPointerException if {@code newInput} is null
     */
    public MutateInput {
      Objects.requireNonNull(newInput, "newInput must not be null");
      newInput = Map.copyOf(newInput);
    }
  }

  /**
   * Refuse the action and surface a human-readable reason.
   *
   * @param reason non-blank reason text
   */
  record Block(String reason) implements HookOutcome {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if {@code reason} is null
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public Block {
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /**
   * Inject a synthetic user message before the next turn.
   *
   * @param userMessage non-blank message text
   */
  record Inject(String userMessage) implements HookOutcome {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if {@code userMessage} is null
     * @throws IllegalArgumentException if {@code userMessage} is blank
     */
    public Inject {
      Objects.requireNonNull(userMessage, "userMessage must not be null");
      if (Strings.isBlank(userMessage)) {
        throw new IllegalArgumentException("userMessage must not be blank");
      }
    }
  }

  /**
   * Terminate the session with a custom result.
   *
   * @param result non-blank terminal result text
   */
  record Stop(String result) implements HookOutcome {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if {@code result} is null
     * @throws IllegalArgumentException if {@code result} is blank
     */
    public Stop {
      Objects.requireNonNull(result, "result must not be null");
      if (Strings.isBlank(result)) {
        throw new IllegalArgumentException("result must not be blank");
      }
    }
  }
}
