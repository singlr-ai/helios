/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory.behavior;

import ai.singlr.core.memory.Memory;
import ai.singlr.core.memory.MemoryBlocks;
import ai.singlr.core.memory.MemoryEvent;
import ai.singlr.core.memory.MemoryListener;
import ai.singlr.core.model.Role;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reference {@link MemoryListener} that watches each turn for explicit user signals about tool
 * preferences and writes them into {@link MemoryBlocks#USER_PROFILE}. Two signal classes:
 *
 * <ul>
 *   <li><b>Avoid</b> — user said something like "don't use X", "stop using X", "X is wrong". The
 *       tool name X is added to {@code user_profile.avoided_tools}.
 *   <li><b>Prefer</b> — user said something like "use X", "prefer X", "always X". The tool name X
 *       is added to {@code user_profile.preferred_tools}.
 * </ul>
 *
 * Detection is intentionally simple — exact substring patterns against a known tool name list. The
 * extractor takes the tool name list at construction. False positives are preferable to false
 * negatives: when uncertain, the extractor does nothing.
 *
 * <p>This is a pattern demonstration; production deployments will typically write a richer
 * extractor that uses an LLM for the inference. The {@link MemoryListener} contract supports both.
 */
public final class ToolAcceptanceExtractor implements MemoryListener {

  private static final Pattern AVOID_PATTERN =
      Pattern.compile(
          "(?:don'?t|do not|stop|avoid|never)\\s+"
              + "(?:use|using|call|calling|invoke|invoking|run|running)\\s+"
              + "([a-z0-9_]+)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PREFER_PATTERN =
      Pattern.compile(
          "(?:prefer|always)\\s+(?:use|using|call|calling)\\s+([a-z0-9_]+)",
          Pattern.CASE_INSENSITIVE);

  private final Memory memory;
  private final Set<String> knownTools;

  /**
   * @param memory the memory store to write user-profile updates to; the extractor will create the
   *     {@link MemoryBlocks#USER_PROFILE} block on first use if it doesn't exist
   * @param knownTools tool names the extractor will recognize in user messages; case-insensitive
   *     match
   */
  public ToolAcceptanceExtractor(Memory memory, Set<String> knownTools) {
    if (memory == null) {
      throw new IllegalArgumentException("memory must not be null");
    }
    if (knownTools == null) {
      throw new IllegalArgumentException("knownTools must not be null");
    }
    this.memory = memory;
    this.knownTools = Set.copyOf(knownTools);
  }

  @Override
  public void onAfterTurn(MemoryEvent.AfterTurn event) {
    var userMsg = event.userMessage();
    if (userMsg == null || userMsg.role() != Role.USER || userMsg.content() == null) {
      return;
    }
    var lower = userMsg.content().toLowerCase(Locale.ROOT);

    var avoided = matchesAgainstTools(AVOID_PATTERN, lower);
    var preferred = matchesAgainstTools(PREFER_PATTERN, lower);
    if (avoided.isEmpty() && preferred.isEmpty()) {
      return;
    }

    ensureUserProfileBlock();
    if (!avoided.isEmpty()) {
      mergeIntoCsvKey("avoided_tools", avoided);
    }
    if (!preferred.isEmpty()) {
      mergeIntoCsvKey("preferred_tools", preferred);
    }
  }

  private Set<String> matchesAgainstTools(Pattern pattern, String text) {
    var matches = pattern.matcher(text);
    var hits = new LinkedHashSet<String>();
    while (matches.find()) {
      var name = matches.group(1);
      if (knownTools.stream().anyMatch(known -> known.equalsIgnoreCase(name))) {
        hits.add(name.toLowerCase(Locale.ROOT));
      }
    }
    return hits;
  }

  private void ensureUserProfileBlock() {
    if (memory.block(MemoryBlocks.USER_PROFILE).isEmpty()) {
      memory.putBlock(MemoryBlocks.userProfile().build());
    }
  }

  private void mergeIntoCsvKey(String key, Set<String> newEntries) {
    var current = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow().value(key, "");
    var combined = new LinkedHashSet<String>();
    var asString = current == null ? "" : current.toString();
    if (!asString.isBlank()) {
      for (var piece : List.of(asString.split(","))) {
        var trimmed = piece.trim();
        if (!trimmed.isEmpty()) {
          combined.add(trimmed);
        }
      }
    }
    combined.addAll(newEntries);
    memory.updateBlock(MemoryBlocks.USER_PROFILE, key, String.join(",", combined));
  }
}
