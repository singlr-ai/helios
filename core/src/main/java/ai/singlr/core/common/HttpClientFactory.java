/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import ai.singlr.core.model.ModelConfig;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Factory for creating configured HttpClient instances.
 *
 * <p>Creates HttpClient instances with connection timeout from ModelConfig. Response timeout is
 * applied per-request via HttpRequest.Builder.timeout().
 */
public final class HttpClientFactory {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private HttpClientFactory() {}

  /**
   * Create an HttpClient configured with timeouts from ModelConfig.
   *
   * @param config the model configuration containing timeout settings
   * @return a new HttpClient instance
   */
  public static HttpClient create(ModelConfig config) {
    Duration connectTimeout =
        config != null && config.connectTimeout() != null
            ? config.connectTimeout()
            : DEFAULT_CONNECT_TIMEOUT;

    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Create an HttpClient with default settings.
   *
   * @return a new HttpClient instance with default timeouts
   */
  public static HttpClient create() {
    return create(null);
  }
}
