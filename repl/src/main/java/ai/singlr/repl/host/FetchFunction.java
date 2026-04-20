/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * Factory for the {@code fetch} host function. Performs HTTP GET requests through the host,
 * enforcing a domain allowlist to prevent SSRF.
 *
 * <p>Security model:
 *
 * <ul>
 *   <li>Only domains explicitly in the allowlist are reachable. Hostnames are normalized to
 *       lower-case on both sides before comparison (DNS is case-insensitive)
 *   <li>IP literals (IPv4 and IPv6) are rejected — every request must resolve a hostname, so the
 *       allowlist cannot be bypassed by pointing directly at a private or metadata IP
 *   <li>The supplied {@link HttpClient} must be configured with {@link Redirect#NEVER} — otherwise
 *       an allowlisted endpoint could redirect to a non-allowlisted host
 *   <li>Only HTTP GET is supported
 *   <li>HTTPS is required — plain HTTP is rejected
 *   <li>Response bodies are capped at {@link #DEFAULT_MAX_RESPONSE_BYTES} (overridable); a hostile
 *       allowlisted endpoint cannot OOM the host by returning a huge payload
 * </ul>
 */
public final class FetchFunction {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  static final long DEFAULT_MAX_RESPONSE_BYTES = 10L * 1024 * 1024;

  private FetchFunction() {}

  /**
   * Create a fetch host function with the given HTTP client and domain allowlist.
   *
   * @param httpClient the HTTP client to use for requests; must have {@link Redirect#NEVER}
   * @param allowedDomains the set of allowed domains (normalized to lower-case)
   * @return a host function that sandbox code can call as {@code fetch(url)}
   */
  public static HostFunction create(HttpClient httpClient, Set<String> allowedDomains) {
    return create(httpClient, allowedDomains, DEFAULT_MAX_RESPONSE_BYTES);
  }

  /**
   * Create a fetch host function with a custom response size cap.
   *
   * @param httpClient the HTTP client to use for requests; must have {@link Redirect#NEVER}
   * @param allowedDomains the set of allowed domains (normalized to lower-case)
   * @param maxResponseBytes upper bound on response body size; requests exceeding this are
   *     cancelled and surfaced as {@link IOException}
   * @return a host function that sandbox code can call as {@code fetch(url)}
   */
  public static HostFunction create(
      HttpClient httpClient, Set<String> allowedDomains, long maxResponseBytes) {
    if (httpClient == null) {
      throw new IllegalArgumentException("HttpClient must not be null");
    }
    if (httpClient.followRedirects() != Redirect.NEVER) {
      throw new IllegalArgumentException(
          "HttpClient must be configured with followRedirects(NEVER) — redirects would bypass the"
              + " domain allowlist");
    }
    if (allowedDomains == null || allowedDomains.isEmpty()) {
      throw new IllegalArgumentException("Allowed domains must not be null or empty");
    }
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("Max response bytes must be positive");
    }
    var normalized =
        allowedDomains.stream()
            .map(d -> d.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    return new HostFunction(
        "fetch",
        "Fetch a URL via HTTP GET. Parameters: url (string). Allowed domains: "
            + String.join(", ", normalized)
            + ".",
        params -> executeFetch(httpClient, normalized, maxResponseBytes, params));
  }

  private static Object executeFetch(
      HttpClient httpClient,
      Set<String> allowedDomains,
      long maxResponseBytes,
      Map<String, Object> params)
      throws IOException, InterruptedException {
    var urlString = HostParams.requireString(params, "url");
    var uri = parseAndValidate(urlString, allowedDomains);
    var timeout = extractTimeout(params);

    var request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
    var response = httpClient.send(request, boundedStringHandler(maxResponseBytes));

    return Map.of(
        "status", response.statusCode(),
        "body", response.body() != null ? response.body() : "",
        "contentType", response.headers().firstValue("content-type").orElse(""));
  }

  static URI parseAndValidate(String urlString, Set<String> allowedDomains) {
    URI uri;
    try {
      uri = URI.create(urlString);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid URL: " + urlString, e);
    }

    var host = uri.getHost();
    if (host == null) {
      throw new IllegalArgumentException("URL must have a host: " + urlString);
    }

    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("Only HTTPS URLs are allowed, got: " + uri.getScheme());
    }

    var lowerHost = host.toLowerCase(Locale.ROOT);
    if (isIpLiteral(lowerHost)) {
      throw new IllegalArgumentException("IP literals are not allowed in URL host: " + host);
    }
    if (!allowedDomains.contains(lowerHost)) {
      throw new IllegalArgumentException(
          "Domain not in allowlist: " + host + ". Allowed: " + allowedDomains);
    }

    return uri;
  }

  private static boolean isIpLiteral(String host) {
    var stripped =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    try {
      InetAddress.ofLiteral(stripped);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Duration extractTimeout(Map<String, Object> params) {
    var value = params.get("timeoutMs");
    if (value instanceof Number n && n.longValue() > 0) {
      return Duration.ofMillis(n.longValue());
    }
    return DEFAULT_TIMEOUT;
  }

  static HttpResponse.BodyHandler<String> boundedStringHandler(long maxBytes) {
    return info -> new BoundedStringSubscriber(maxBytes);
  }

  static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {

    private final long maxBytes;
    private final CompletableFuture<String> future = new CompletableFuture<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    private long received;

    BoundedStringSubscriber(long maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<String> getBody() {
      return future;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      this.subscription = s;
      s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (future.isDone()) {
        return;
      }
      for (var b : items) {
        var chunk = b.remaining();
        if (received + chunk > maxBytes) {
          subscription.cancel();
          future.completeExceptionally(
              new IOException("Response body exceeds max size: " + maxBytes + " bytes"));
          return;
        }
        var tmp = new byte[chunk];
        b.get(tmp);
        buffer.write(tmp, 0, chunk);
        received += chunk;
      }
    }

    @Override
    public void onError(Throwable err) {
      future.completeExceptionally(err);
    }

    @Override
    public void onComplete() {
      future.complete(buffer.toString(StandardCharsets.UTF_8));
    }
  }
}
