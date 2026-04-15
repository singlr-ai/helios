/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Factory for the {@code fetch} host function. Performs HTTP GET requests through the host,
 * enforcing a domain allowlist to prevent SSRF.
 *
 * <p>Security model:
 *
 * <ul>
 *   <li>Only domains explicitly in the allowlist are reachable
 *   <li>Only HTTP GET is supported (no POST, PUT, DELETE)
 *   <li>HTTPS is required — plain HTTP is rejected
 *   <li>The {@link HttpClient} is provided by the application developer, allowing custom TLS,
 *       proxy, and timeout configuration
 * </ul>
 */
public final class FetchFunction {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private FetchFunction() {}

  /**
   * Create a fetch host function with the given HTTP client and domain allowlist.
   *
   * @param httpClient the HTTP client to use for requests
   * @param allowedDomains the set of allowed domains (e.g., {@code Set.of("api.example.com")})
   * @return a host function that sandbox code can call as {@code fetch(url)}
   */
  public static HostFunction create(HttpClient httpClient, Set<String> allowedDomains) {
    if (httpClient == null) {
      throw new IllegalArgumentException("HttpClient must not be null");
    }
    if (allowedDomains == null || allowedDomains.isEmpty()) {
      throw new IllegalArgumentException("Allowed domains must not be null or empty");
    }
    var domains = Set.copyOf(allowedDomains);
    return new HostFunction(
        "fetch",
        "Fetch a URL via HTTP GET. Parameters: url (string). Allowed domains: "
            + String.join(", ", domains)
            + ".",
        params -> executeFetch(httpClient, domains, params));
  }

  private static Object executeFetch(
      HttpClient httpClient, Set<String> allowedDomains, Map<String, Object> params)
      throws IOException, InterruptedException {
    var urlString = requireString(params, "url");
    var uri = parseAndValidate(urlString, allowedDomains);
    var timeout = extractTimeout(params);

    var request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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

    if (uri.getHost() == null) {
      throw new IllegalArgumentException("URL must have a host: " + urlString);
    }

    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("Only HTTPS URLs are allowed, got: " + uri.getScheme());
    }

    if (!allowedDomains.contains(uri.getHost())) {
      throw new IllegalArgumentException(
          "Domain not in allowlist: " + uri.getHost() + ". Allowed: " + allowedDomains);
    }

    return uri;
  }

  private static Duration extractTimeout(Map<String, Object> params) {
    var value = params.get("timeoutMs");
    if (value instanceof Number n && n.longValue() > 0) {
      return Duration.ofMillis(n.longValue());
    }
    return DEFAULT_TIMEOUT;
  }

  private static String requireString(Map<String, Object> params, String key) {
    var value = params.get(key);
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Parameter '" + key + "' is required and must be a string");
  }
}
