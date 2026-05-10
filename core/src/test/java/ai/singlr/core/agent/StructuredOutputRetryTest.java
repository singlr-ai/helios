/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the structured-output parse-failure retry path. Providers throw {@link
 * StructuredOutputParseException} when the model's JSON response doesn't conform to the configured
 * schema; the agent loop converts that into a corrective USER turn and continues iterating instead
 * of failing terminally.
 */
class StructuredOutputRetryTest {

  private record Weather(String city, int temperature) {}

  /**
   * Build a fake model whose typed chat throws a {@link StructuredOutputParseException} on the
   * first {@code throwTurns} calls and returns a parsed {@link Weather} thereafter.
   */
  private static Model bouncingModel(
      int throwTurns, AtomicInteger callCount, Weather successValue) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> Response<T> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
        var n = callCount.getAndIncrement();
        if (n < throwTurns) {
          throw new StructuredOutputParseException(
              List.of("field 'city' is required but missing"),
              "{\"temperature\":" + (18 + n) + "}");
        }
        var parsed = outputSchema.type().cast(successValue);
        return Response.<T>newBuilder(outputSchema.type())
            .withParsed(parsed)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void parseFailureRetriesAndRecovers() {
    var calls = new AtomicInteger(0);
    var model = bouncingModel(1, calls, new Weather("London", 18));
    var memory = InMemoryMemory.withDefaults();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("RetryAgent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .withMaxIterations(3)
                .build());

    var session =
        SessionContext.newBuilder()
            .withUserId("u")
            .withSessionId(java.util.UUID.randomUUID())
            .withUserInput("Weather?")
            .build();
    var result = agent.run(session, OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess(), "agent must recover after one parse failure");
    @SuppressWarnings("unchecked")
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertNotNull(response.parsed());
    assertEquals("London", response.parsed().city());
    assertEquals(2, calls.get(), "model should be called twice — once failing, once succeeding");

    var history = memory.history(session.userId(), session.sessionId());
    var injected =
        history.stream()
            .filter(m -> m.role() == Role.USER)
            .filter(
                m ->
                    m.metadata() != null
                        && "structuredOutputParse".equals(m.metadata().get("helios.injected")))
            .findFirst();
    assertTrue(injected.isPresent(), "memory must contain the injected correction USER turn");
    assertTrue(
        injected.get().content().contains("field 'city'"),
        "injected correction must carry the field-level diff verbatim");
    assertFalse(
        injected.get().content().contains("temperature"),
        "injected correction must not echo the raw bad JSON content");
  }

  @Test
  void retryDisabledFailsImmediately() {
    var calls = new AtomicInteger(0);
    var model = bouncingModel(1, calls, new Weather("X", 0));
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("NoRetryAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withMaxIterations(3)
                .withStructuredOutputRetry(false)
                .build());

    var result = agent.run(SessionContext.of("Weather?"), OutputSchema.of(Weather.class));

    assertTrue(result.isFailure(), "with retry disabled, parse failure must terminate");
    assertEquals(1, calls.get(), "model should be called exactly once before the run fails");
  }

  @Test
  void parseFailureExhaustsIterationsWhenModelNeverRecovers() {
    var calls = new AtomicInteger(0);
    var model = bouncingModel(Integer.MAX_VALUE, calls, new Weather("never", 0));
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("ExhaustAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withMaxIterations(2)
                .build());

    var result = agent.run(SessionContext.of("Weather?"), OutputSchema.of(Weather.class));

    assertTrue(result.isFailure(), "max-iterations with always-bad output must fail the run");
    var failure = (Result.Failure<?>) result;
    assertTrue(
        failure.error().contains("Max iterations"),
        "failure must surface the max-iterations exhaustion message");
    assertEquals(2, calls.get(), "model must be called exactly maxIterations times");
  }

  @Test
  void retryEnabledByDefault() {
    var config =
        AgentConfig.newBuilder()
            .withModel(new ai.singlr.core.test.MockModel("ok"))
            .withIncludeMemoryTools(false)
            .build();
    assertTrue(config.structuredOutputRetry());
  }

  @Test
  void retryFlagPersistsThroughBuilderRoundTrip() {
    var initial =
        AgentConfig.newBuilder()
            .withModel(new ai.singlr.core.test.MockModel("ok"))
            .withIncludeMemoryTools(false)
            .withStructuredOutputRetry(false)
            .build();
    var roundTripped = AgentConfig.newBuilder(initial).build();
    assertFalse(roundTripped.structuredOutputRetry());
  }
}
