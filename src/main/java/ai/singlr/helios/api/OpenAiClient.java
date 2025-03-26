/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.literal.Constants;
import ai.singlr.helios.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static java.time.temporal.ChronoUnit.MILLIS;

public class OpenAiClient {

  private static final Logger LOGGER = Logger.getLogger(OpenAiClient.class.getName());

  public final HttpClient httpClient;
  public final String basePath;
  public final Audio audio;
  public final Chat chat;
  public final Map<String, String> defaultHeaders;

  private OpenAiClient(Builder builder) {
    this.httpClient = builder.httpClient;
    this.basePath = builder.basePath;
    this.audio = Audio.newAudio(this);
    this.chat = Chat.newChat(this);

    defaultHeaders = Map.of(
        Constants.USER_AGENT_KEY, Constants.SINGULAR_USER_AGENT,
        Constants.AUTHORIZATION_KEY, Constants.BEARER_PREFIX + builder.apiKey,
        Constants.CONTENT_TYPE_KEY, Constants.JSON_CONTENT_TYPE
    );

    LOGGER.info("OpenAi client initialized");
  }

  public URI fullUri(String path) {
    return URI.create(basePath + path);
  }

  public HttpRequest.Builder buildMultiPartFormData(
      HttpRequest.Builder builder,
      String boundary,
      Map<String, Object> data) throws IOException {

    List<byte[]> byteArrays = new ArrayList<>();
    byte[] separator = ("--" + boundary + "\r\nContent-Disposition: form-data; name=").getBytes(StandardCharsets.UTF_8);

    for (Map.Entry<String, Object> entry : data.entrySet()) {
      byteArrays.add(separator);
      if (entry.getValue() instanceof Path path) {
        String mimeType = Files.probeContentType(path);
        byteArrays.add(String.format(
            "\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n",
            entry.getKey(),
            path.getFileName(),
            mimeType).getBytes(StandardCharsets.UTF_8));
        byteArrays.add(Files.readAllBytes(path));
        byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));

      } else {
        byteArrays.add(String.format(
            "\"%s\"\r\n\r\n%s\r\n",
            entry.getKey(),
            entry.getValue()).getBytes(StandardCharsets.UTF_8));
      }
    }

    byteArrays.add(("--" + boundary + "--").getBytes(StandardCharsets.UTF_8));

    builder.header("Content-Type", "multipart/form-data;boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArrays(byteArrays));
    return builder;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private HttpClient httpClient;
    private String apiKey;
    private long timeout = 10000L;
    private String basePath = "https://api.openai.com/v1";

    public Builder withApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder withTimeout(long timeout) {
      this.timeout = timeout;
      return this;
    }

    public Builder withBasePath(String basePath) {
      this.basePath = basePath;
      return this;
    }

    /**
     * Allows for full configuration of the {@link HttpClient}.
     *
     * @param httpClient the {@link HttpClient} to clone.
     */
    public Builder withClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public OpenAiClient build() {
      if (StringUtils.isBlank(apiKey)) {
        apiKey = System.getenv("OPENAI_API_KEY");
      }

      StringUtils.requireNonBlank(apiKey, "Open AI API key must be provided. Typically through the 'OPENAI_API_KEY' environment variable.");
      StringUtils.requireNonBlank(basePath, "Base path must be provided.");

      if (httpClient == null) {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.of(timeout, MILLIS))
            .version(HttpClient.Version.HTTP_2)
            .build();
      }

      return new OpenAiClient(this);
    }
  }
}
