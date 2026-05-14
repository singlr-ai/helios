/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Public API of the helios-session SDK.
 *
 * <p>Top-level types ({@code AgentSession}, {@code SessionOptions}, {@code QueryEvent}, {@code
 * ResultMessage}, {@code UserMessage}, {@code SessionScope}, ...) live in this package. The
 * cooperative {@code CancellationToken} that {@code SessionScope} carries lives in {@code
 * ai.singlr.core.runtime} so tools and non-session callers can depend on it without importing the
 * session module. Specialised subsystems — {@code loop}, {@code hooks}, {@code tools}, {@code
 * files}, {@code execution}, {@code memory}, {@code permissions}, {@code audit}, {@code preset},
 * {@code config} — live in nested packages added as their first types land.
 */
package ai.singlr.session;
