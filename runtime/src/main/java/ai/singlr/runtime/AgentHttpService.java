/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.session.AgentSession;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import io.helidon.http.Status;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.sse.SseSink;
import java.io.IOException;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.databind.ObjectMapper;

/**
 * Helidon {@link HttpService} that exposes one {@link SessionRegistry}'s sessions over HTTP. The
 * five Phase 1 routes mirror the spec §15.1 sketch and are mounted by the caller under whatever
 * prefix fits the deployment ({@code routing.register("/v1", new AgentHttpService(...))}).
 *
 * <ul>
 *   <li>{@code POST /sessions} — create a fresh session; returns {@code {sessionId, eventsUrl}}
 *       with {@code 201 Created}.
 *   <li>{@code POST /sessions/{sessionId}/messages} — body {@code {text: "..."}}; queues the
 *       message and returns {@code 202 Accepted}.
 *   <li>{@code POST /sessions/{sessionId}/interrupt} — body {@code {reason: "..."}}; queues a
 *       synthetic interrupt message and returns {@code 202 Accepted}.
 *   <li>{@code GET /sessions/{sessionId}/events} — opens an SSE stream of {@link QueryEvent}s. The
 *       handler blocks the request thread until the publisher signals {@code onComplete} or the
 *       client disconnects.
 *   <li>{@code DELETE /sessions/{sessionId}} — closes and unregisters the session; returns {@code
 *       204 No Content}.
 * </ul>
 *
 * <p>The {@code optionsFactory} is the seam through which deployments choose how a new session is
 * configured: the runtime takes the generated session id and produces a fully-populated {@link
 * SessionOptions}. For Phase 1 the typical impl returns the same {@link ai.singlr.core.model.Model
 * Model} for every session.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Each request runs on its own virtual thread; {@link SessionRegistry} synchronises
 * shared session state.
 */
public final class AgentHttpService implements HttpService {

  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  private final SessionRegistry registry;
  private final Function<String, SessionOptions> optionsFactory;
  private final ObjectMapper objectMapper;
  private final String eventsPathPrefix;

  /**
   * Build a service.
   *
   * @param registry registry of live sessions; non-null
   * @param optionsFactory function that maps a generated session id to a fully-configured {@link
   *     SessionOptions}; non-null
   * @param objectMapper mapper for request/response bodies; non-null
   * @param eventsPathPrefix prefix used to build the {@code eventsUrl} returned by {@code POST
   *     /sessions} (e.g. {@code "/v1"}); non-null
   * @throws NullPointerException if any argument is null
   */
  public AgentHttpService(
      SessionRegistry registry,
      Function<String, SessionOptions> optionsFactory,
      ObjectMapper objectMapper,
      String eventsPathPrefix) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.optionsFactory = Objects.requireNonNull(optionsFactory, "optionsFactory must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.eventsPathPrefix =
        Objects.requireNonNull(eventsPathPrefix, "eventsPathPrefix must not be null");
  }

  @Override
  public void routing(HttpRules rules) {
    rules.post("/sessions", this::createHandler);
    rules.post("/sessions/{sessionId}/messages", this::messageHandler);
    rules.post("/sessions/{sessionId}/interrupt", this::interruptHandler);
    rules.get("/sessions/{sessionId}/events", this::eventsHandler);
    rules.delete("/sessions/{sessionId}", this::deleteHandler);
  }

  // ── handlers ────────────────────────────────────────────────────────────

  private void createHandler(ServerRequest req, ServerResponse resp) {
    var sessionId = "sess-" + Ids.newId();
    SessionOptions options;
    try {
      options = optionsFactory.apply(sessionId);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "optionsFactory failed for session " + sessionId, e);
      resp.status(Status.INTERNAL_SERVER_ERROR_500)
          .send(Map.of("error", "session options factory failed: " + e.getMessage()));
      return;
    }
    if (!options.sessionId().equals(sessionId)) {
      LOGGER.warning(
          "optionsFactory ignored the supplied sessionId; using factory-provided id "
              + options.sessionId());
    }
    registry.create(options);
    resp.status(Status.CREATED_201)
        .send(
            Map.of(
                "sessionId",
                options.sessionId(),
                "eventsUrl",
                eventsPathPrefix + "/sessions/" + options.sessionId() + "/events"));
  }

  private void messageHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Map<String, Object> body;
    try {
      body = readJsonBody(req);
    } catch (JacksonRuntimeException e) {
      resp.status(Status.BAD_REQUEST_400).send(Map.of("error", "invalid JSON body"));
      return;
    }
    var text = body.get("text");
    if (!(text instanceof String s) || Strings.isBlank(s)) {
      resp.status(Status.BAD_REQUEST_400)
          .send(Map.of("error", "'text' field must be a non-blank string"));
      return;
    }
    try {
      sessionOpt.get().send(UserMessage.text(s));
    } catch (IllegalStateException e) {
      resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }

  private void interruptHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Map<String, Object> body;
    try {
      body = readJsonBody(req);
    } catch (JacksonRuntimeException e) {
      resp.status(Status.BAD_REQUEST_400).send(Map.of("error", "invalid JSON body"));
      return;
    }
    var reason = body.get("reason");
    if (!(reason instanceof String r) || Strings.isBlank(r)) {
      resp.status(Status.BAD_REQUEST_400)
          .send(Map.of("error", "'reason' field must be a non-blank string"));
      return;
    }
    try {
      sessionOpt.get().interrupt(r);
    } catch (IllegalStateException e) {
      resp.status(Status.CONFLICT_409).send(Map.of("error", e.getMessage()));
      return;
    }
    resp.status(Status.ACCEPTED_202).send();
  }

  private void eventsHandler(ServerRequest req, ServerResponse resp) {
    var sessionOpt = findSession(req, resp);
    if (sessionOpt.isEmpty()) {
      return;
    }
    var session = sessionOpt.get();
    var sink = resp.sink(SseSink.TYPE);
    var done = new CountDownLatch(1);
    var subscription = new AtomicReference<Flow.Subscription>();
    session
        .events()
        .subscribe(
            new Flow.Subscriber<QueryEvent>() {
              @Override
              public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(QueryEvent event) {
                try {
                  sink.emit(
                      SseEvent.builder()
                          .name(eventName(event))
                          .data(objectMapper.writeValueAsString(event))
                          .build());
                } catch (Exception ex) {
                  if (!isDisconnect(ex)) {
                    LOGGER.log(
                        Level.WARNING, "SSE emit failed for session " + session.sessionId(), ex);
                  }
                  var s = subscription.get();
                  if (s != null) {
                    s.cancel();
                  }
                  done.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
                LOGGER.log(
                    Level.WARNING,
                    "events publisher errored for session " + session.sessionId(),
                    t);
                done.countDown();
              }

              @Override
              public void onComplete() {
                done.countDown();
              }
            });
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        sink.close();
      } catch (Exception ignored) {
        // sink may already be closed by Helidon if the client disconnected
      }
    }
  }

  private void deleteHandler(ServerRequest req, ServerResponse resp) {
    var sessionId = req.path().pathParameters().get("sessionId");
    var removed = registry.close(sessionId);
    if (!removed) {
      resp.status(Status.NOT_FOUND_404).send(Map.of("error", "session not found"));
      return;
    }
    resp.status(Status.NO_CONTENT_204).send();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private Optional<AgentSession> findSession(ServerRequest req, ServerResponse resp) {
    var sessionId = req.path().pathParameters().get("sessionId");
    var sessionOpt = registry.get(sessionId);
    if (sessionOpt.isEmpty()) {
      resp.status(Status.NOT_FOUND_404).send(Map.of("error", "session not found"));
    }
    return sessionOpt;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJsonBody(ServerRequest req) {
    try {
      return (Map<String, Object>) req.content().as(Map.class);
    } catch (RuntimeException e) {
      throw new JacksonRuntimeException("failed to parse request body", e);
    }
  }

  private static String eventName(QueryEvent event) {
    return event.getClass().getSimpleName();
  }

  static boolean isDisconnect(Throwable ex) {
    var current = ex;
    while (current != null) {
      if (current instanceof SocketException || current instanceof IOException) {
        var msg = current.getMessage();
        if (msg != null
            && (msg.contains("Broken pipe")
                || msg.contains("Connection reset")
                || msg.contains("Socket closed"))) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
