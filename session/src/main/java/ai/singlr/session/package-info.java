/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Public API of the helios-session SDK.
 *
 * <p>Top-level types ({@code AgentSession}, {@code SessionOptions}, {@code QueryEvent}, {@code
 * ResultMessage}, {@code UserMessage}, {@code SessionScope}, {@code CancellationToken}, ...) live
 * in this package. Specialised subsystems — {@code loop}, {@code hooks}, {@code tools}, {@code
 * files}, {@code execution}, {@code memory}, {@code permissions}, {@code audit}, {@code preset},
 * {@code config} — live in nested packages added in subsequent PRs.
 *
 * <p>PR 1 ships this package empty; types arrive in PR 2.
 */
package ai.singlr.session;
