/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RunStore}. State lives in a {@link ConcurrentHashMap} keyed by run id; suitable
 * for tests and single-process deployments without crash-recovery requirements.
 */
public class InMemoryRunStore implements RunStore {

  private final Map<UUID, AgentRun> runs = new ConcurrentHashMap<>();

  @Override
  public void checkpoint(AgentRun run) {
    Objects.requireNonNull(run, "run");
    runs.put(run.runId(), run);
  }

  @Override
  public Optional<AgentRun> find(UUID runId) {
    if (runId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(runs.get(runId));
  }

  @Override
  public List<AgentRun> findByStatus(AgentRunStatus status) {
    Objects.requireNonNull(status, "status");
    var matches = new ArrayList<AgentRun>();
    for (var run : runs.values()) {
      if (run.status() == status) {
        matches.add(run);
      }
    }
    matches.sort(Comparator.comparing(AgentRun::lastCheckpointAt).reversed());
    return List.copyOf(matches);
  }
}
