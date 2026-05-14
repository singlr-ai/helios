/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks candidates by per-instance validation scores and maintains the Pareto-non-dominated set.
 *
 * <p>GEPA-style optimizers keep every candidate that wins on at least one validation instance, not
 * just the global aggregate winner. This prevents collapse to a local optimum and surfaces
 * complementary strategies — a candidate that nails the hard examples may aggregate lower than a
 * generalist but still belongs on the frontier.
 *
 * <p>Dominance: candidate {@code A} dominates {@code B} iff {@code A}'s per-instance scores are ≥
 * {@code B}'s on every instance and strictly greater on at least one. The frontier is the set of
 * candidates not dominated by any other.
 *
 * <p>Coverage sampling: a candidate's sampling weight equals the number of validation instances on
 * which it sits on the upper envelope (the per-instance maximum across all candidates). Candidates
 * that uniquely win on more instances are sampled more often than ones that share their best
 * instances with siblings — biases reflection toward complementary diversity rather than redundant
 * strength.
 *
 * <p>Thread-safe via a single {@link ReentrantReadWriteLock}: the workload is read-mostly (many
 * dominator queries per write during a GEPA loop), and the lock ordering keeps adds from racing on
 * the cached frontier indices.
 *
 * <p>NaN policy: {@link #add} rejects any score array containing NaN. Silent NaN propagation makes
 * optimizer convergence diagnostics worthless — every dominance comparison becomes false, the
 * frontier silently swells, and there's no way to tell after the fact. Fail loud at the boundary.
 *
 * @param <C> candidate type (e.g. {@code String} prompt, {@code AgentConfig}, custom record)
 */
public final class ParetoFrontier<C> {

  private final int validationSetSize;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final List<Entry<C>> entries = new ArrayList<>();
  private List<Integer> cachedFrontierIndices;

  /**
   * Construct an empty frontier for the given validation set size.
   *
   * @param validationSetSize number of validation instances each candidate is scored on; every
   *     {@link #add} call's score array must match this length
   */
  public ParetoFrontier(int validationSetSize) {
    if (validationSetSize < 1) {
      throw new IllegalArgumentException("validationSetSize must be >= 1");
    }
    this.validationSetSize = validationSetSize;
  }

  /**
   * Record a candidate's per-instance scores. The frontier-index cache is invalidated; the next
   * read recomputes it.
   *
   * @param candidate the candidate to track
   * @param perInstanceScores per-instance scores; length must equal {@link #validationSetSize()}
   * @return {@code true} if this candidate joined the frontier (was not dominated by any prior
   *     candidate at the time of insertion)
   * @throws IllegalArgumentException if {@code perInstanceScores.length} is wrong or any score is
   *     NaN
   */
  public boolean add(C candidate, double[] perInstanceScores) {
    if (perInstanceScores == null) {
      throw new IllegalArgumentException("perInstanceScores must not be null");
    }
    if (perInstanceScores.length != validationSetSize) {
      throw new IllegalArgumentException(
          "perInstanceScores.length="
              + perInstanceScores.length
              + " but validationSetSize="
              + validationSetSize);
    }
    for (var s : perInstanceScores) {
      if (Double.isNaN(s)) {
        throw new IllegalArgumentException("perInstanceScores contains NaN");
      }
    }
    var copy = perInstanceScores.clone();
    lock.writeLock().lock();
    try {
      var newIndex = entries.size();
      entries.add(new Entry<>(candidate, copy));
      cachedFrontierIndices = null;
      return isOnFrontier(newIndex, copy);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Candidates currently on the Pareto frontier, in insertion order. Recomputes lazily when adds
   * have invalidated the cache.
   */
  public List<C> dominators() {
    lock.readLock().lock();
    try {
      var indices = frontierIndices();
      var out = new ArrayList<C>(indices.size());
      for (var i : indices) {
        out.add(entries.get(i).candidate());
      }
      return List.copyOf(out);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Sample a candidate from the frontier weighted by how many validation instances it sits on the
   * upper envelope of (i.e. matches the per-instance max across all candidates).
   *
   * @param rng random source supplied by the caller for reproducibility
   * @throws IllegalStateException if the frontier is empty
   */
  public C sampleByCoverage(Random rng) {
    if (rng == null) {
      throw new IllegalArgumentException("rng must not be null");
    }
    lock.readLock().lock();
    try {
      if (entries.isEmpty()) {
        throw new IllegalStateException("ParetoFrontier is empty — no candidates to sample");
      }
      var envelope = envelopeLocked();
      var indices = frontierIndices();
      var weights = new int[indices.size()];
      var total = 0;
      for (var k = 0; k < indices.size(); k++) {
        var idx = indices.get(k);
        var scores = entries.get(idx).perInstanceScores();
        var w = 0;
        for (var i = 0; i < validationSetSize; i++) {
          if (scores[i] == envelope[i]) {
            w++;
          }
        }
        weights[k] = w;
        total += w;
      }
      if (total == 0) {
        // Should not happen: every frontier member sits on the envelope on at least one instance
        // by construction. Defensive fall-through: pick the first frontier member.
        return entries.get(indices.get(0)).candidate();
      }
      var pick = rng.nextInt(total);
      var running = 0;
      for (var k = 0; k < weights.length; k++) {
        running += weights[k];
        if (pick < running) {
          return entries.get(indices.get(k)).candidate();
        }
      }
      return entries.get(indices.get(indices.size() - 1)).candidate();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Single best candidate by aggregate score, for callers that don't care about diversity. */
  public Optional<C> bestSingle() {
    lock.readLock().lock();
    try {
      if (entries.isEmpty()) {
        return Optional.empty();
      }
      var bestIdx = 0;
      var bestSum = sum(entries.get(0).perInstanceScores());
      for (var i = 1; i < entries.size(); i++) {
        var s = sum(entries.get(i).perInstanceScores());
        if (s > bestSum) {
          bestSum = s;
          bestIdx = i;
        }
      }
      return Optional.of(entries.get(bestIdx).candidate());
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Aggregate score = sum of per-instance scores. */
  public double aggregateScore(C candidate) {
    lock.readLock().lock();
    try {
      for (var e : entries) {
        if (e.candidate() == candidate || (candidate != null && candidate.equals(e.candidate()))) {
          return sum(e.perInstanceScores());
        }
      }
      throw new IllegalArgumentException("candidate not present in frontier");
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Upper envelope: per-instance best score seen so far across all candidates. */
  public double[] envelope() {
    lock.readLock().lock();
    try {
      return envelopeLocked();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** All candidates ever added, in insertion order. For audit / replay. */
  public List<C> allCandidates() {
    lock.readLock().lock();
    try {
      var out = new ArrayList<C>(entries.size());
      for (var e : entries) {
        out.add(e.candidate());
      }
      return List.copyOf(out);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Number of validation instances each candidate is scored on. */
  public int validationSetSize() {
    return validationSetSize;
  }

  /** Snapshot of the frontier suitable for serialization / replay. */
  public Snapshot<C> snapshot() {
    lock.readLock().lock();
    try {
      return new Snapshot<>(validationSetSize, List.copyOf(entries));
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Restore a frontier from a {@link #snapshot()}. Round-trip is exact on all public methods. */
  public static <C> ParetoFrontier<C> restore(Snapshot<C> snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    var f = new ParetoFrontier<C>(snapshot.validationSetSize());
    f.lock.writeLock().lock();
    try {
      for (var e : snapshot.entries()) {
        f.entries.add(new Entry<>(e.candidate(), e.perInstanceScores().clone()));
      }
    } finally {
      f.lock.writeLock().unlock();
    }
    return f;
  }

  private double[] envelopeLocked() {
    var out = new double[validationSetSize];
    if (entries.isEmpty()) {
      return out;
    }
    Arrays.fill(out, Double.NEGATIVE_INFINITY);
    for (var e : entries) {
      var s = e.perInstanceScores();
      for (var i = 0; i < validationSetSize; i++) {
        if (s[i] > out[i]) {
          out[i] = s[i];
        }
      }
    }
    return out;
  }

  private List<Integer> frontierIndices() {
    var cached = cachedFrontierIndices;
    if (cached != null) {
      return cached;
    }
    var indices = new ArrayList<Integer>();
    for (var i = 0; i < entries.size(); i++) {
      if (isOnFrontier(i, entries.get(i).perInstanceScores())) {
        indices.add(i);
      }
    }
    var snapshot = List.copyOf(indices);
    cachedFrontierIndices = snapshot;
    return snapshot;
  }

  private boolean isOnFrontier(int candidateIndex, double[] candidateScores) {
    for (var j = 0; j < entries.size(); j++) {
      if (j == candidateIndex) {
        continue;
      }
      if (dominates(entries.get(j).perInstanceScores(), candidateScores)) {
        return false;
      }
    }
    return true;
  }

  private static boolean dominates(double[] a, double[] b) {
    var strictlyGreater = false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] < b[i]) {
        return false;
      }
      if (a[i] > b[i]) {
        strictlyGreater = true;
      }
    }
    return strictlyGreater;
  }

  private static double sum(double[] xs) {
    var s = 0.0;
    for (var x : xs) {
      s += x;
    }
    return s;
  }

  public record Snapshot<C>(int validationSetSize, List<Entry<C>> entries) {
    public Snapshot {
      if (validationSetSize < 1) {
        throw new IllegalArgumentException("validationSetSize must be >= 1");
      }
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }

  public record Entry<C>(C candidate, double[] perInstanceScores) {
    public Entry {
      if (perInstanceScores == null) {
        throw new IllegalArgumentException("perInstanceScores must not be null");
      }
      perInstanceScores = perInstanceScores.clone();
    }
  }
}
