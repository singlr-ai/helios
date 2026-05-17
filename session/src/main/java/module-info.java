/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - Session Module.
 *
 * <p>The open-ended, streamable, steerable agentic SDK. Provides the foundation for long-running
 * tool-using agents with first-class hooks, file-editing safety, swappable execution providers,
 * filesystem-backed memory, and tamper-evident audit.
 *
 * <p>Spec: {@code docs/specs/agentic-coding-sdk-java-v2.md}. This is the v2 SDK; v1's {@code
 * core.agent.Agent} surface has been removed.
 *
 * <p>The public surface in {@code ai.singlr.session} carries the value types ({@code UserMessage},
 * {@code StopReason}, {@code SerializedError}), the concurrency primitives, the sealed event/result
 * hierarchies, the session API, hooks, file tools, execution providers, memory, audit, and the
 * preset surface. Subsystem-specific packages (e.g. {@code ai.singlr.session.loop}) are exported as
 * their first types land.
 */
module ai.singlr.session {
  requires ai.singlr.core;
  requires java.logging;
  requires java.net.http;
  requires tools.jackson.databind;

  exports ai.singlr.session;
  exports ai.singlr.session.ask;
  exports ai.singlr.session.files;
  exports ai.singlr.session.hooks;
  exports ai.singlr.session.loop;
  exports ai.singlr.session.memory;
  exports ai.singlr.session.permissions;
  exports ai.singlr.session.tools;
}
