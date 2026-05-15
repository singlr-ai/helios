/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - Helidon SE Runtime Module.
 *
 * <p>Exposes {@link ai.singlr.session.AgentSession} instances over HTTP and SSE via a Helidon SE
 * {@code WebServer}. The runtime is the deployment shell that fronts the session SDK; the SDK
 * itself never depends on Helidon.
 */
module ai.singlr.runtime {
  requires ai.singlr.session;
  requires ai.singlr.core;
  requires io.helidon.webserver;
  requires io.helidon.webserver.sse;
  requires io.helidon.http;
  requires io.helidon.http.media;
  requires io.helidon.http.sse;
  requires io.helidon.common;
  requires tools.jackson.databind;
  requires java.logging;
  requires java.net.http;

  exports ai.singlr.runtime;
}
