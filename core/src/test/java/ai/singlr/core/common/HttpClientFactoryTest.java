/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.singlr.core.model.ModelConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpClientFactoryTest {

  @Test
  void createWithConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withConnectTimeout(Duration.ofSeconds(30))
            .build();

    var client = HttpClientFactory.create(config);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(30), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithNullConfig() {
    var client = HttpClientFactory.create(null);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithDefaultSettings() {
    var client = HttpClientFactory.create();

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void createWithNullConnectTimeout() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withConnectTimeout(null).build();

    var client = HttpClientFactory.create(config);

    assertNotNull(client);
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElse(null));
  }

  @Test
  void followsRedirects() {
    var client = HttpClientFactory.create();

    assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
  }
}
