/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mutable registry of host functions. Can be frozen to prevent further modifications after sandbox
 * startup.
 */
public final class HostFunctionRegistry {

  /**
   * Names reserved by the framework. The sandbox's {@code HostBridge} statically owns these
   * signatures, so custom host functions cannot be registered against them — the prelude
   * synthesizer skips them, the trajectory-trace tracking wrapper excludes them from the
   * called-host-function count, and the system prompt does not advertise them as user-supplied.
   *
   * <p>Canonical single source of truth — every component that filters reserved names reads this
   * constant.
   */
  public static final Set<String> RESERVED_NAMES =
      Set.of("predict", "submit", "fetch", "query", "getInput", "__getInput", "__call");

  private final Map<String, HostFunction> functions = new LinkedHashMap<>();
  private volatile boolean frozen;

  /**
   * Register a host function.
   *
   * @param function the function to register
   * @throws IllegalStateException if the registry is frozen or a function with the same name exists
   */
  public void register(HostFunction function) {
    if (function == null) {
      throw new IllegalArgumentException("Host function must not be null");
    }
    if (frozen) {
      throw new IllegalStateException("Registry is frozen");
    }
    if (functions.containsKey(function.name())) {
      throw new IllegalStateException("Host function already registered: " + function.name());
    }
    functions.put(function.name(), function);
  }

  /**
   * Look up a function by name.
   *
   * @param name the function name
   * @return the function, or {@code null} if not found
   */
  public HostFunction get(String name) {
    return functions.get(name);
  }

  /**
   * All registered functions.
   *
   * @return unmodifiable view of the registered functions
   */
  public Collection<HostFunction> all() {
    return Collections.unmodifiableCollection(functions.values());
  }

  /** Number of registered functions. */
  public int size() {
    return functions.size();
  }

  /** Freeze the registry to prevent further modifications. */
  public void freeze() {
    this.frozen = true;
  }

  /** Whether the registry is frozen. */
  public boolean isFrozen() {
    return frozen;
  }
}
