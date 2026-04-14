/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PredictFunctionTest {

  @Test
  void nullModelThrows() {
    assertThrows(IllegalArgumentException.class, () -> PredictFunction.create(null));
  }

  @Test
  void createReturnsHostFunction() {
    var fn = PredictFunction.create(new FakeModel("hello"));
    assertEquals("predict", fn.name());
    assertNotNull(fn.description());
    assertNotNull(fn.handler());
  }

  @Test
  @SuppressWarnings("unchecked")
  void callWithInstructionsAndInput() throws Exception {
    var captured = new AtomicReference<List<Message>>();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            captured.set(messages);
            return Response.<Void>newBuilder()
                .withContent("model output")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var fn = PredictFunction.create(model);
    var result =
        (Map<String, Object>)
            fn.handler().handle(Map.of("instructions", "Summarize", "input", "some text"));

    assertEquals("model output", result.get("output"));

    var messages = captured.get();
    assertEquals(2, messages.size());
    assertEquals("Summarize", messages.get(0).content());
    assertEquals("some text", messages.get(1).content());
  }

  @Test
  void missingInstructionsThrows() {
    var fn = PredictFunction.create(new FakeModel("x"));
    assertThrows(
        IllegalArgumentException.class, () -> fn.handler().handle(Map.of("input", "hello")));
  }

  @Test
  void missingInputThrows() {
    var fn = PredictFunction.create(new FakeModel("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> fn.handler().handle(Map.of("instructions", "do stuff")));
  }

  @Test
  void nonStringInstructionsThrows() {
    var fn = PredictFunction.create(new FakeModel("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> fn.handler().handle(Map.of("instructions", 42, "input", "hello")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullContentReturnsEmptyString() throws Exception {
    var model = new FakeModel(null);
    var fn = PredictFunction.create(model);

    var result =
        (Map<String, Object>) fn.handler().handle(Map.of("instructions", "test", "input", "test"));

    assertEquals("", result.get("output"));
  }

  private static class FakeModel implements Model {
    private final String response;

    FakeModel(String response) {
      this.response = response;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.<Void>newBuilder()
          .withContent(response)
          .withFinishReason(FinishReason.STOP)
          .build();
    }

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public String provider() {
      return "fake";
    }
  }
}
