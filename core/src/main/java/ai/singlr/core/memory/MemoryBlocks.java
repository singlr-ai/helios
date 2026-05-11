/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

/**
 * Canonical block names and pre-shaped builders for the three semantic surfaces every memory-aware
 * agent should have. Using these constants is not required — {@link Memory#putBlock} accepts any
 * block name — but they encode the conventions tooling (consolidators, behavior extractors, the
 * built-in compactor summary template) expects.
 *
 * <p>The three surfaces mirror the proven split from Letta and from Hermes Agent's MEMORY.md /
 * USER.md / SOUL.md separation:
 *
 * <ul>
 *   <li>{@link #IDENTITY} — what the agent IS. The persona / role / tone. Typically authored once
 *       at agent construction and left alone for the lifetime of the deployment.
 *   <li>{@link #USER_PROFILE} — who the USER is. Stable cross-session facts about the human (name,
 *       timezone, role, preferences). Behavior extractors write here.
 *   <li>{@link #WORKING_MEMORY} — what we're DOING right now. Current task state, recent decisions,
 *       open questions. Volatile relative to the other two; consolidators can clear it across
 *       sessions.
 * </ul>
 */
public final class MemoryBlocks {

  /** Block name for agent identity / persona. */
  public static final String IDENTITY = "identity";

  /** Block name for stable cross-session facts about the user. */
  public static final String USER_PROFILE = "user_profile";

  /** Block name for current task state — open questions, recent decisions, partial computations. */
  public static final String WORKING_MEMORY = "working_memory";

  /** Recommended max size (in rendered chars) for {@link #IDENTITY}. */
  public static final int DEFAULT_IDENTITY_MAX_SIZE = 1500;

  /** Recommended max size for {@link #USER_PROFILE}. */
  public static final int DEFAULT_USER_PROFILE_MAX_SIZE = 2500;

  /** Recommended max size for {@link #WORKING_MEMORY}. */
  public static final int DEFAULT_WORKING_MEMORY_MAX_SIZE = 3000;

  private MemoryBlocks() {}

  /**
   * Build an {@link #IDENTITY} block pre-shaped with the canonical name, a descriptive label, and
   * the recommended max size. The caller adds key/value pairs via {@link MemoryBlock.Builder}.
   */
  public static MemoryBlock.Builder identity() {
    return MemoryBlock.newBuilder()
        .withName(IDENTITY)
        .withDescription(
            "Agent identity — persona, role, tone. Stable for the lifetime of the deployment.")
        .withMaxSize(DEFAULT_IDENTITY_MAX_SIZE);
  }

  /**
   * Build a {@link #USER_PROFILE} block pre-shaped with the canonical name, a descriptive label,
   * and the recommended max size. This is where behavior extractors and consolidators write durable
   * facts about the user.
   */
  public static MemoryBlock.Builder userProfile() {
    return MemoryBlock.newBuilder()
        .withName(USER_PROFILE)
        .withDescription(
            "Stable cross-session facts about the user — preferences, role, timezone, learned signals.")
        .withMaxSize(DEFAULT_USER_PROFILE_MAX_SIZE);
  }

  /**
   * Build a {@link #WORKING_MEMORY} block pre-shaped with the canonical name, a descriptive label,
   * and the recommended max size. Treat contents as volatile — consolidators may rewrite or clear
   * this block between sessions.
   */
  public static MemoryBlock.Builder workingMemory() {
    return MemoryBlock.newBuilder()
        .withName(WORKING_MEMORY)
        .withDescription(
            "Current task state — open questions, recent decisions, partial computations. Volatile.")
        .withMaxSize(DEFAULT_WORKING_MEMORY_MAX_SIZE);
  }
}
