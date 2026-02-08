/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - Core Module.
 *
 * <p>Provides the foundational components for building intelligent agents:
 *
 * <ul>
 *   <li>Agent orchestration and state management
 *   <li>Model abstraction with ServiceLoader-based provider discovery
 *   <li>Memory system with core blocks and archival storage
 *   <li>Tool definition and execution framework
 *   <li>Fault tolerance with retry, circuit breaker, and timeout
 *   <li>Versioned prompt management with template rendering
 * </ul>
 */
module ai.singlr.core {
  requires java.net.http;

  exports ai.singlr.core.agent;
  exports ai.singlr.core.common;
  exports ai.singlr.core.fault;
  exports ai.singlr.core.memory;
  exports ai.singlr.core.model;
  exports ai.singlr.core.prompt;
  exports ai.singlr.core.schema;
  exports ai.singlr.core.tool;
  exports ai.singlr.core.trace;
  exports ai.singlr.core.workflow;

  uses ai.singlr.core.model.ModelProvider;
  uses ai.singlr.core.trace.TraceListener;
}
