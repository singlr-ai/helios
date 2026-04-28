/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtractFallbackTest {

  public record Summary(String headline, int wordCount) {}

  private static <T> Model fixedParsedModel(T parsed) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException("untyped chat not used");
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        return (Response<U>)
            Response.newBuilder(Object.class)
                .withContent("ok")
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

  private static Model nullParsedModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new UnsupportedOperationException("untyped chat not used");
      }

      @Override
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        return Response.newBuilder(schema.type())
            .withContent("just some prose, no structured output")
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
  void attemptReturnsParsedOnSuccess() {
    var expected = new Summary("done", 42);
    var result =
        ExtractFallback.attempt(
            fixedParsedModel(expected), OutputSchema.of(Summary.class), "trajectory: ...");
    assertTrue(result.isSuccess());
    assertEquals(expected, ((ai.singlr.core.common.Result.Success<Summary>) result).value());
  }

  @Test
  void attemptFailsWhenModelReturnsNoParsedValue() {
    var result =
        ExtractFallback.attempt(
            nullParsedModel(), OutputSchema.of(Summary.class), "trajectory: ...");
    assertFalse(result.isSuccess());
    assertTrue(
        ((ai.singlr.core.common.Result.Failure<Summary>) result).error().contains("no parsed"));
  }

  @Test
  void attemptValidatesNullModel() {
    var result = ExtractFallback.attempt(null, OutputSchema.of(Summary.class), "trajectory: ...");
    assertFalse(result.isSuccess());
    assertTrue(((ai.singlr.core.common.Result.Failure<Summary>) result).error().contains("model"));
  }

  @Test
  void attemptValidatesNullSchema() {
    var result = ExtractFallback.attempt(fixedParsedModel("x"), null, "trajectory: ...");
    assertFalse(result.isSuccess());
    assertTrue(((ai.singlr.core.common.Result.Failure<Object>) result).error().contains("schema"));
  }

  @Test
  void attemptValidatesBlankSummary() {
    var blank =
        ExtractFallback.attempt(fixedParsedModel("x"), OutputSchema.of(Summary.class), "  ");
    assertFalse(blank.isSuccess());
    var nullSummary =
        ExtractFallback.attempt(fixedParsedModel("x"), OutputSchema.of(Summary.class), null);
    assertFalse(nullSummary.isSuccess());
  }

  @Test
  void attemptPropagatesAgentFailure() {
    var failingModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model boom");
          }

          @Override
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            throw new RuntimeException("model boom");
          }

          @Override
          public String id() {
            return "boom";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var result =
        ExtractFallback.attempt(failingModel, OutputSchema.of(Summary.class), "trajectory: ...");
    assertFalse(result.isSuccess());
    assertTrue(
        ((ai.singlr.core.common.Result.Failure<Summary>) result)
            .error()
            .contains("extract-fallback failed"));
  }

  @Test
  void attemptCallsModelWithSchemaAndUserInput() {
    var captured = new AtomicReference<List<Message>>();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            captured.set(messages);
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withContent("done")
                    .withParsed(new Summary("h", 1))
                    .withFinishReason(FinishReason.STOP)
                    .build();
          }

          @Override
          public String id() {
            return "m";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var summary = "iteration 1: var x = predict(...)\nx = 'meaningful answer'";
    ExtractFallback.attempt(model, OutputSchema.of(Summary.class), summary);

    var msgs = captured.get();
    assertEquals(2, msgs.size(), "system + user");
    assertEquals(ai.singlr.core.model.Role.SYSTEM, msgs.get(0).role());
    assertTrue(
        msgs.get(0).content().contains("submit()"),
        "system prompt should reference the submit-not-called scenario");
    assertEquals(ai.singlr.core.model.Role.USER, msgs.get(1).role());
    assertEquals(summary, msgs.get(1).content());
  }

  @Test
  void summarizeEmptyHistoryProducesEmptyString() {
    assertEquals("", ExtractFallback.summarize(null));
    assertEquals("", ExtractFallback.summarize(List.of()));
  }

  @Test
  void summarizeRendersToolCallsAsCodeAndOutputBlocks() {
    var messages =
        List.of(
            Message.system("you are an agent"),
            Message.user("do something"),
            Message.assistant(
                "I'll fetch the data",
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("execute_code")
                        .withArguments(Map.of("code", "var x = 42; print(x);"))
                        .build())),
            Message.tool("c1", "execute_code", "42"),
            Message.assistant(
                null,
                List.of(
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("execute_code")
                        .withArguments(Map.of("code", "var y = x * 2; print(y);"))
                        .build())),
            Message.tool("c2", "execute_code", "84"));

    var summary = ExtractFallback.summarize(messages);

    assertTrue(summary.contains("Iteration 1"));
    assertTrue(summary.contains("Iteration 2"));
    assertTrue(summary.contains("Reasoning: I'll fetch the data"));
    assertTrue(summary.contains("var x = 42"));
    assertTrue(summary.contains("Output:\n42"));
    assertTrue(summary.contains("Output:\n84"));
  }

  @Test
  void summarizeRendersFinalAssistantTextAsThought() {
    var messages = List.of(Message.user("hi"), Message.assistant("here is my final answer text"));
    var summary = ExtractFallback.summarize(messages);
    assertTrue(summary.contains("Final thought: here is my final answer text"));
  }

  @Test
  void summarizeFallsBackToAllArgsWhenNoCodeKey() {
    var messages =
        List.of(
            Message.assistant(
                null,
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("custom_tool")
                        .withArguments(Map.of("query", "hello", "limit", 5))
                        .build())));
    var summary = ExtractFallback.summarize(messages);
    assertTrue(summary.contains("custom_tool"));
    assertTrue(summary.contains("query"));
    assertTrue(summary.contains("hello"));
  }

  @Test
  void summarizeIgnoresSystemAndUserMessages() {
    var messages = List.of(Message.system("system stuff"), Message.user("user stuff"));
    var summary = ExtractFallback.summarize(messages);
    assertFalse(summary.contains("system stuff"));
    assertFalse(summary.contains("user stuff"));
  }
}
