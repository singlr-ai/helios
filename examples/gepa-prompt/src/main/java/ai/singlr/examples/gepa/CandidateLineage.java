/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Records parent → child relationships for every candidate the optimizer proposes, in proposal
 * order. The lineage is useful for post-mortem ("which seed did the best candidate descend from?")
 * and for live UIs that render the candidate tree.
 *
 * <p>Insertion-ordered ({@link LinkedHashMap}) so iteration over {@link #entries} replays proposal
 * order. Not thread-safe — the optimizer's outer loop is sequential by design.
 */
public final class CandidateLineage {

  /**
   * One entry in the lineage.
   *
   * @param candidateId the proposed candidate's ID
   * @param parentId the parent candidate's ID, or {@code null} for the seed candidate
   * @param candidate the proposed candidate value (typically the prompt string)
   */
  public record Entry(UUID candidateId, UUID parentId, String candidate) {}

  private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();

  /**
   * Record a candidate. Seed candidates pass {@code null} for {@code parentId}; reflective children
   * pass the parent's ID.
   *
   * @throws IllegalArgumentException if {@code candidateId} is already present
   */
  public void record(UUID candidateId, UUID parentId, String candidate) {
    if (candidateId == null) {
      throw new IllegalArgumentException("candidateId must not be null");
    }
    if (entries.containsKey(candidateId)) {
      throw new IllegalArgumentException("candidateId already recorded: " + candidateId);
    }
    entries.put(candidateId, new Entry(candidateId, parentId, candidate));
  }

  /** Number of recorded candidates. */
  public int size() {
    return entries.size();
  }

  /** All entries in proposal order. */
  public Map<UUID, Entry> entries() {
    return Collections.unmodifiableMap(entries);
  }

  /** The {@link Entry} for {@code candidateId}, if recorded. */
  public Optional<Entry> get(UUID candidateId) {
    return Optional.ofNullable(entries.get(candidateId));
  }
}
