/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class FetchFunctionTest {

  private static final Set<String> ALLOWED = Set.of("api.example.com", "data.example.com");

  @Test
  void nullClientThrows() {
    assertThrows(IllegalArgumentException.class, () -> FetchFunction.create(null, ALLOWED));
  }

  @Test
  void nullDomainsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchFunction.create(stubClient(200, "ok", "text/plain"), null));
  }

  @Test
  void emptyDomainsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchFunction.create(stubClient(200, "ok", "text/plain"), Set.of()));
  }

  @Test
  void createReturnsHostFunction() {
    var fn = FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED);
    assertEquals("fetch", fn.name());
    assertTrue(fn.description().contains("api.example.com"));
    assertNotNull(fn.handler());
  }

  @Test
  @SuppressWarnings("unchecked")
  void successfulGetReturnsStatusAndBody() throws Exception {
    var fn = FetchFunction.create(stubClient(200, "{\"data\":1}", "application/json"), ALLOWED);
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://api.example.com/data"));

    assertEquals(200, result.get("status"));
    assertEquals("{\"data\":1}", result.get("body"));
    assertEquals("application/json", result.get("contentType"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void capturesRequestUri() throws Exception {
    var captured = new AtomicReference<HttpRequest>();
    var fn = FetchFunction.create(capturingClient(captured, 200, "ok", "text/plain"), ALLOWED);
    fn.handler().handle(Map.of("url", "https://api.example.com/path?q=test"));

    assertEquals(URI.create("https://api.example.com/path?q=test"), captured.get().uri());
    assertEquals("GET", captured.get().method());
  }

  @Test
  @SuppressWarnings("unchecked")
  void customTimeoutPassedToRequest() throws Exception {
    var captured = new AtomicReference<HttpRequest>();
    var fn = FetchFunction.create(capturingClient(captured, 200, "ok", "text/plain"), ALLOWED);
    fn.handler().handle(Map.of("url", "https://api.example.com/data", "timeoutMs", 5000));

    assertEquals(Optional.of(Duration.ofMillis(5000)), captured.get().timeout());
  }

  @Test
  @SuppressWarnings("unchecked")
  void defaultTimeoutWhenNotSpecified() throws Exception {
    var captured = new AtomicReference<HttpRequest>();
    var fn = FetchFunction.create(capturingClient(captured, 200, "ok", "text/plain"), ALLOWED);
    fn.handler().handle(Map.of("url", "https://api.example.com/data"));

    assertEquals(Optional.of(FetchFunction.DEFAULT_TIMEOUT), captured.get().timeout());
  }

  @Test
  @SuppressWarnings("unchecked")
  void nonNumberTimeoutUsesDefault() throws Exception {
    var captured = new AtomicReference<HttpRequest>();
    var fn = FetchFunction.create(capturingClient(captured, 200, "ok", "text/plain"), ALLOWED);
    fn.handler().handle(Map.of("url", "https://api.example.com/data", "timeoutMs", "not-a-number"));

    assertEquals(Optional.of(FetchFunction.DEFAULT_TIMEOUT), captured.get().timeout());
  }

  @Test
  @SuppressWarnings("unchecked")
  void zeroTimeoutUsesDefault() throws Exception {
    var captured = new AtomicReference<HttpRequest>();
    var fn = FetchFunction.create(capturingClient(captured, 200, "ok", "text/plain"), ALLOWED);
    fn.handler().handle(Map.of("url", "https://api.example.com/data", "timeoutMs", 0));

    assertEquals(Optional.of(FetchFunction.DEFAULT_TIMEOUT), captured.get().timeout());
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullBodyReturnsEmptyString() throws Exception {
    var fn = FetchFunction.create(stubClient(204, null, ""), ALLOWED);
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://api.example.com/data"));

    assertEquals("", result.get("body"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingContentTypeReturnsEmptyString() throws Exception {
    var fn = FetchFunction.create(stubClient(200, "data", null), ALLOWED);
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://api.example.com/data"));

    assertEquals("", result.get("contentType"));
  }

  @Test
  void httpRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("http://api.example.com/data", ALLOWED));
    assertTrue(ex.getMessage().contains("Only HTTPS"));
  }

  @Test
  void domainNotInAllowlist() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https://evil.com/steal", ALLOWED));
    assertTrue(ex.getMessage().contains("Domain not in allowlist"));
  }

  @Test
  void malformedUrlThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchFunction.parseAndValidate("not a url at all", ALLOWED));
  }

  @Test
  void urlWithNoHostThrows() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https:///path", ALLOWED));
    assertTrue(ex.getMessage().contains("must have a host"));
  }

  @Test
  void missingUrlParamThrows() {
    var fn = FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED);
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of()));
  }

  @Test
  void nonStringUrlParamThrows() {
    var fn = FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED);
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of("url", 12345)));
  }

  @Test
  void ioExceptionPropagates() {
    var fn = FetchFunction.create(failingClient(new IOException("network error")), ALLOWED);
    assertThrows(
        IOException.class,
        () -> fn.handler().handle(Map.of("url", "https://api.example.com/data")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void secondAllowedDomainWorks() throws Exception {
    var fn = FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED);
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://data.example.com/csv"));

    assertEquals(200, result.get("status"));
  }

  @Test
  void redirectFollowingClientRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FetchFunction.create(stubClientWithRedirect(HttpClient.Redirect.ALWAYS), ALLOWED));
    assertTrue(ex.getMessage().contains("followRedirects(NEVER)"));
  }

  @Test
  void redirectNormalClientRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FetchFunction.create(stubClientWithRedirect(HttpClient.Redirect.NORMAL), ALLOWED));
    assertTrue(ex.getMessage().contains("followRedirects(NEVER)"));
  }

  @Test
  void ipv4LiteralRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https://127.0.0.1/path", ALLOWED));
    assertTrue(ex.getMessage().contains("IP literals"));
  }

  @Test
  void linkLocalIpv4LiteralRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https://169.254.169.254/latest", ALLOWED));
    assertTrue(ex.getMessage().contains("IP literals"));
  }

  @Test
  void hostnameWithTrailingBracketNotTreatedAsIp() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https://example.com]/path", ALLOWED));
    assertTrue(ex.getMessage().contains("Invalid URL") || ex.getMessage().contains("Domain"));
  }

  @Test
  void ipv6LiteralRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> FetchFunction.parseAndValidate("https://[::1]/path", ALLOWED));
    assertTrue(ex.getMessage().contains("IP literals"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void uppercaseHostNormalizedToAllowlist() throws Exception {
    var fn = FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED);
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://API.EXAMPLE.COM/data"));

    assertEquals(200, result.get("status"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void uppercaseAllowlistNormalizedToHost() throws Exception {
    var fn =
        FetchFunction.create(
            stubClient(200, "ok", "text/plain"), Set.of("API.Example.com", "Data.Example.com"));
    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("url", "https://api.example.com/data"));

    assertEquals(200, result.get("status"));
  }

  @Test
  void zeroMaxBytesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED, 0L));
  }

  @Test
  void negativeMaxBytesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchFunction.create(stubClient(200, "ok", "text/plain"), ALLOWED, -1L));
  }

  @Test
  void boundedSubscriberAcceptsBodyWithinLimit() throws Exception {
    var subscriber = new FetchFunction.BoundedStringSubscriber(1024);
    var subscription = new NoopSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("hello".getBytes())));
    subscriber.onComplete();

    assertEquals("hello", subscriber.getBody().toCompletableFuture().get());
  }

  @Test
  void boundedSubscriberCancelsOnOverflow() {
    var subscriber = new FetchFunction.BoundedStringSubscriber(4);
    var subscription = new NoopSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("too-big".getBytes())));

    assertTrue(subscription.cancelled);
    var future = subscriber.getBody().toCompletableFuture();
    assertTrue(future.isCompletedExceptionally());
    var ex = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    assertTrue(ex.getCause() instanceof IOException);
    assertTrue(ex.getCause().getMessage().contains("exceeds max size"));
  }

  @Test
  void boundedSubscriberCancelsOnOverflowAcrossChunks() {
    var subscriber = new FetchFunction.BoundedStringSubscriber(6);
    var subscription = new NoopSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("abc".getBytes())));
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("defgh".getBytes())));

    assertTrue(subscription.cancelled);
    assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void boundedSubscriberIgnoresItemsAfterOverflow() {
    var subscriber = new FetchFunction.BoundedStringSubscriber(2);
    var subscription = new NoopSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("overflow".getBytes())));
    subscriber.onNext(List.of(java.nio.ByteBuffer.wrap("ignored".getBytes())));

    assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void boundedSubscriberPropagatesError() {
    var subscriber = new FetchFunction.BoundedStringSubscriber(1024);
    subscriber.onSubscribe(new NoopSubscription());
    subscriber.onError(new RuntimeException("boom"));

    var future = subscriber.getBody().toCompletableFuture();
    assertTrue(future.isCompletedExceptionally());
  }

  @Test
  void boundedHandlerReturnsNewSubscriberPerResponse() {
    var handler = FetchFunction.boundedStringHandler(1024);
    var s1 = handler.apply(null);
    var s2 = handler.apply(null);
    assertNotNull(s1);
    assertNotNull(s2);
    assertTrue(s1 != s2);
  }

  private static StubHttpClient stubClient(int status, String body, String contentType) {
    return new StubHttpClient(null, status, body, contentType, null, HttpClient.Redirect.NEVER);
  }

  private static StubHttpClient stubClientWithRedirect(HttpClient.Redirect redirect) {
    return new StubHttpClient(null, 200, "ok", "text/plain", null, redirect);
  }

  private static StubHttpClient capturingClient(
      AtomicReference<HttpRequest> captured, int status, String body, String contentType) {
    return new StubHttpClient(captured, status, body, contentType, null, HttpClient.Redirect.NEVER);
  }

  private static StubHttpClient failingClient(IOException error) {
    return new StubHttpClient(null, 0, null, null, error, HttpClient.Redirect.NEVER);
  }

  private static final class NoopSubscription implements java.util.concurrent.Flow.Subscription {
    boolean cancelled;

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private static final class StubHttpClient extends HttpClient {
    private final AtomicReference<HttpRequest> captured;
    private final int status;
    private final String body;
    private final String contentType;
    private final IOException error;
    private final Redirect redirectPolicy;

    StubHttpClient(
        AtomicReference<HttpRequest> captured,
        int status,
        String body,
        String contentType,
        IOException error,
        Redirect redirectPolicy) {
      this.captured = captured;
      this.status = status;
      this.body = body;
      this.contentType = contentType;
      this.error = error;
      this.redirectPolicy = redirectPolicy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      if (error != null) {
        throw error;
      }
      if (captured != null) {
        captured.set(request);
      }
      return (HttpResponse<T>) new StubResponse(status, body, contentType, request.uri());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return redirectPolicy;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_2;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }
  }

  private record StubResponse(int statusCode, String body, String contentType, URI uri)
      implements HttpResponse<String> {

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      if (contentType != null) {
        return HttpHeaders.of(Map.of("content-type", List.of(contentType)), (k, v) -> true);
      }
      return HttpHeaders.of(Map.of(), (k, v) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_2;
    }
  }
}
