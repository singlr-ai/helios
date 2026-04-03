/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

  @Test
  void estimateNullReturnsZero() {
    assertEquals(0, TokenEstimator.estimate((String) null));
  }

  @Test
  void estimateEmptyReturnsZero() {
    assertEquals(0, TokenEstimator.estimate(""));
  }

  @Test
  void estimateStringDividesByFour() {
    assertEquals(3, TokenEstimator.estimate("Hello World!")); // 12 / 4 = 3
  }

  @Test
  void estimateRoundsDown() {
    assertEquals(1, TokenEstimator.estimate("Hello")); // 5 / 4 = 1
  }

  @Test
  void estimateMessagesSystemAndUser() {
    var messages = List.of(Message.system("You are an assistant"), Message.user("Hello there!"));

    var tokens = TokenEstimator.estimate(messages);

    assertEquals("You are an assistant".length() / 4 + "Hello there!".length() / 4, tokens);
  }

  @Test
  void estimateMessagesWithToolCalls() {
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("get_weather")
            .withArguments(Map.of("city", "London"))
            .build();
    var messages =
        List.of(
            Message.system("System prompt"),
            Message.user("Weather?"),
            Message.assistant("", List.of(tc)));

    var tokens = TokenEstimator.estimate(messages);

    var expected =
        "System prompt".length() / 4
            + "Weather?".length() / 4
            + "".length() / 4
            + "get_weather".length() / 4
            + tc.arguments().toString().length() / 4;
    assertEquals(expected, tokens);
  }

  @Test
  void estimateEmptyListReturnsZero() {
    assertEquals(0, TokenEstimator.estimate(List.of()));
  }

  @Test
  void estimateToolMessage() {
    var messages = List.of(Message.tool("call_1", "get_weather", "Sunny, 25C"));

    var tokens = TokenEstimator.estimate(messages);

    assertEquals("Sunny, 25C".length() / 4, tokens);
  }
}
