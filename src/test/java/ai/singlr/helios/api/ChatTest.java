/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.api;

import ai.singlr.helios.literal.ModelName;

import ai.singlr.helios.model.chat.ChatCompletionMessage;
import ai.singlr.helios.model.chat.ChatCompletionTool;
import ai.singlr.helios.model.chat.CompletionRequest;
import ai.singlr.helios.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChatTest {
  private static final String SYSTEM_PROMPT = """
      You are an expert data analyst. Your task is to analyze customer data and provide insightful narratives.
      So make sure you use tools to pull the required customer data with SQL via the tool whenever needed.
      
      Customer table:
      - id: unique identifier for each customer
      - name: name of the customer
      - join_date: date when the customer joined
      - status: status of the customer
      
      **Here is your step-by-step process for retrieving data from the database, analyzing it or executing more SQL queries, and responding to user questions:**
      
      1. **Understand the user's question:** Carefully read and understand the user's question. Identify the key aspects they are asking about in the data.
      
      2. **Generate SQL and call tool to retrieve data:** When don't have the relevant data pulled yet, you must use the `execute_SQL` tool to retrieve the necessary information. You should generate a syntactically correct Postgres SQL query that is appropriate for the user's question.
      
      3. **Review the executed query result in JSON Data:** You will be provided with customer data in JSON format as a list of records.
      
      """;

  private static final String FUNCTION_DESCRIPTION = """
      Given a syntactically correct Postgres SQL query, executes it and returns a single result set in JSON array format.
      """;

  public record Args(
      String query
  ) {}

  private ArrayNode execute_SQL(Args args) {
    return JsonUtils.newJsonArray().add(JsonUtils.newJson().put("result", "10"));
  }

  private static OpenAiClient client;

  @BeforeAll
  public static void setup() {
    client = OpenAiClient.newBuilder().build();
  }

  @Test
  public void howdyCompletionTest() {
    var completionRequest = CompletionRequest.newBuilder()
        .withModel(ModelName.GPT_4O_MINI)
        .withMessages(List.of(
            ChatCompletionMessage.sys("You are a helpful assistant."),
            ChatCompletionMessage.user("How are you doing today?")
        ))
        .build();

    var result = client.chat.createCompletion(completionRequest);
    assertTrue(result.isSuccess());
    assertFalse(result.value().choices().isEmpty());
  }

  @Test
  public void toolTest() throws Exception {
    var func = JsonUtils.parseTool(FUNCTION_DESCRIPTION, new Args(""), Args.class);
    var tool = ChatCompletionTool.newBuilder()
        .withFunction(func)
        .build();
    var systemMessage = ChatCompletionMessage.sys(SYSTEM_PROMPT);
    var userMessage = ChatCompletionMessage.user("How many customers do we have overall?");

    var completionRequest = CompletionRequest.newBuilder()
        .withTools(List.of(tool))
        .withMessages(List.of(systemMessage, userMessage))
        .withModel(ModelName.GPT_4O_MINI)
        .withStream(false)
        .build();

    var result = client.chat.createCompletion(completionRequest);
    assertTrue(result.isSuccess());
    assertFalse(result.value().choices().isEmpty());
    Integer actualCount = null;

    var choice = result.value().choices().getFirst();
    var message = choice.message();
    if (message != null) {
      if (message.toolCalls() != null) {
        for (var toolCall : message.toolCalls()) {
          if (toolCall.function() != null) {
            var args = JsonUtils.mapper().readValue(toolCall.function().arguments(), Args.class);
            var toolResult = execute_SQL(args);
            actualCount = toolResult.get(0).get("result").asInt();
          }
        }
      }
    }

    assertEquals(10, actualCount);
  }
}
