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
 * <p>Spec: {@code docs/specs/agentic-coding-sdk-java-v2.md}. This is the v2 SDK that replaces
 * {@code ai.singlr.core.agent.Agent} and friends.
 *
 * <p>PR 1 (this commit) is module scaffolding only. No types are exported yet — the JPMS compiler
 * rejects {@code exports} of empty packages, so the {@code exports ai.singlr.session} line lands in
 * PR 2 alongside the first real type.
 */
module ai.singlr.session {
  requires ai.singlr.core;
  requires java.logging;
  requires java.net.http;
}
