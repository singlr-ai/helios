/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.tool.ParameterType;
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
 *   <li>Pre-flight DNS resolution: the host is resolved via {@link InetAddress#getAllByName} and
 *       every returned address is checked against the private-range set (loopback, RFC1918 site-
 *       local, link-local, multicast, IPv6 ULA fc00::/7, IPv4 carrier-grade NAT 100.64.0.0/10). Any
 *       private result rejects the request — defends against DNS rebinding where an allowlisted
 *       hostname's authoritative DNS flips to an internal IP between validation and send.
 *       <b>Residual risk</b>: the supplied {@link HttpClient} does its own DNS lookup at send time.
 *       The window is short (typically &lt; 100ms), but operators must trust the DNS behind
 *       allowlisted domains
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
    return create(httpClient, allowedDomains, maxResponseBytes, DEFAULT_RESOLVER);
  }

  /**
   * Test-only overload accepting a custom {@link HostResolver}. Lets the test harness inject a
   * deterministic IP-returning resolver so the DNS-rebinding defense can be exercised offline
   * without flakiness against real DNS. Production callers go through the three-argument overload.
   */
  static HostFunction create(
      HttpClient httpClient,
      Set<String> allowedDomains,
      long maxResponseBytes,
      HostResolver resolver) {
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
    if (resolver == null) {
      throw new IllegalArgumentException("HostResolver must not be null");
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
        List.of(
            HostParameter.required(
                "url", ParameterType.STRING, "HTTPS URL to fetch (host must be on the allowlist)")),
        params -> executeFetch(httpClient, normalized, maxResponseBytes, resolver, params));
  }

  private static Object executeFetch(
      HttpClient httpClient,
      Set<String> allowedDomains,
      long maxResponseBytes,
      HostResolver resolver,
      Map<String, Object> params)
      throws IOException, InterruptedException {
    var urlString = HostParams.requireString(params, "url");
    var uri = parseAndValidate(urlString, allowedDomains, resolver);
    var timeout = extractTimeout(params);

    var request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
    var response = httpClient.send(request, boundedStringHandler(maxResponseBytes));

    return Map.of(
        "status", response.statusCode(),
        "body", response.body() != null ? response.body() : "",
        "contentType", response.headers().firstValue("content-type").orElse(""));
  }

  /** Hostname resolver — pluggable so tests can inject deterministic results. */
  @FunctionalInterface
  interface HostResolver {
    InetAddress[] resolve(String host) throws java.net.UnknownHostException;
  }

  private static final HostResolver DEFAULT_RESOLVER = InetAddress::getAllByName;

  static URI parseAndValidate(String urlString, Set<String> allowedDomains) {
    return parseAndValidate(urlString, allowedDomains, DEFAULT_RESOLVER);
  }

  /**
   * Test-only variant accepting a custom {@link HostResolver}. Production code goes through the
   * two-argument overload which uses the JDK's {@link InetAddress#getAllByName}.
   */
  static URI parseAndValidate(String urlString, Set<String> allowedDomains, HostResolver resolver) {
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

    // DNS rebinding defense: resolve the host and refuse if any returned address is a private
    // range. This is the audit-recommended H10 hardening — without this, an allowlisted domain
    // whose authoritative DNS quickly flips to 127.0.0.1 (or a metadata IP, or any internal
    // network) could hit local services. Java's HttpClient does its own DNS lookup at send time
    // so there is still a small race window; operators must trust the DNS behind allowlisted
    // domains. Localhost-style allowlists for testing must add a no-op resolver themselves.
    verifyHostResolvesToPublicAddresses(lowerHost, resolver);

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

  /**
   * Resolve {@code host} and refuse if any returned address is in a private/reserved range. Visible
   * for testing via the {@link #isPrivateAddress} helper.
   */
  private static void verifyHostResolvesToPublicAddresses(String host, HostResolver resolver) {
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (java.net.UnknownHostException e) {
      throw new IllegalArgumentException("Cannot resolve host: " + host, e);
    }
    for (var addr : addresses) {
      if (isPrivateAddress(addr)) {
        throw new IllegalArgumentException(
            "Host "
                + host
                + " resolves to a private/reserved IP ("
                + addr.getHostAddress()
                + ") and is refused as a DNS-rebinding defense");
      }
    }
  }

  /**
   * Conservative private-range classifier. Returns {@code true} for: loopback, RFC1918 site-local
   * (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), multicast, "any local"
   * 0.0.0.0/:: wildcards, IPv4 carrier-grade NAT (100.64.0.0/10), and IPv6 ULA (fc00::/7). Returns
   * {@code false} for ordinary public addresses.
   *
   * <p>Package-private for tests; production callers go through {@link
   * #verifyHostResolvesToPublicAddresses}.
   */
  static boolean isPrivateAddress(InetAddress addr) {
    if (addr.isLoopbackAddress()
        || addr.isAnyLocalAddress()
        || addr.isLinkLocalAddress()
        || addr.isMulticastAddress()
        || addr.isSiteLocalAddress()) {
      return true;
    }
    var bytes = addr.getAddress();
    if (bytes.length == 4) {
      // IPv4 carrier-grade NAT (100.64.0.0/10): first byte 100, next byte 64..127.
      int b0 = bytes[0] & 0xFF;
      int b1 = bytes[1] & 0xFF;
      if (b0 == 100 && b1 >= 64 && b1 <= 127) {
        return true;
      }
    } else if (bytes.length == 16) {
      // IPv6 unique local addresses (fc00::/7): top 7 bits are 1111110.
      if ((bytes[0] & 0xFE) == 0xFC) {
        return true;
      }
    }
    return false;
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
