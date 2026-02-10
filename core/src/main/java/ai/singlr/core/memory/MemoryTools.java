/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Factory for memory-related tools that allow agents to self-edit memory. Inspired by Letta's
 * memory tools.
 */
public final class MemoryTools {

  private MemoryTools() {}

  private static String requireString(Map<String, Object> args, String name) {
    var value = args.get(name);
    return value instanceof String s ? s : null;
  }

  /** Create all memory tools bound to the given memory instance and session. */
  public static List<Tool> boundTo(Memory memory, UUID sessionId) {
    return List.of(
        coreMemoryUpdate(memory),
        coreMemoryReplace(memory),
        coreMemoryRead(memory),
        archivalInsert(memory),
        archivalSearch(memory),
        conversationSearch(memory, sessionId));
  }

  /** Update a specific key in a core memory block. */
  public static Tool coreMemoryUpdate(Memory memory) {
    return Tool.newBuilder()
        .withName("core_memory_update")
        .withDescription(
            """
				Update a specific key-value pair in a core memory block.
				Use this to store new information or update existing facts.
				""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("block")
                .withType(ParameterType.STRING)
                .withDescription("Name of the memory block (e.g., 'persona', 'user')")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("key")
                .withType(ParameterType.STRING)
                .withDescription("The key to update")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("value")
                .withType(ParameterType.STRING)
                .withDescription("The new value")
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              var block = requireString(args, "block");
              if (block == null) {
                return ToolResult.failure("Parameter 'block' is required and must be a string");
              }
              var key = requireString(args, "key");
              if (key == null) {
                return ToolResult.failure("Parameter 'key' is required and must be a string");
              }
              var value = args.get("value");

              var memBlock = memory.block(block);
              if (memBlock == null) {
                return ToolResult.failure("Memory block '%s' not found".formatted(block));
              }

              memory.updateBlock(block, key, value);
              return ToolResult.success("Updated %s.%s".formatted(block, key));
            })
        .build();
  }

  /** Replace all data in a core memory block. */
  public static Tool coreMemoryReplace(Memory memory) {
    return Tool.newBuilder()
        .withName("core_memory_replace")
        .withDescription(
            """
				Replace all contents of a core memory block.
				Use this when you need to rewrite the entire block.
				""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("block")
                .withType(ParameterType.STRING)
                .withDescription("Name of the memory block")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("content")
                .withType(ParameterType.OBJECT)
                .withDescription("The new content as key-value pairs")
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              var block = requireString(args, "block");
              if (block == null) {
                return ToolResult.failure("Parameter 'block' is required and must be a string");
              }
              var content = args.get("content");

              var memBlock = memory.block(block);
              if (memBlock == null) {
                return ToolResult.failure("Memory block '%s' not found".formatted(block));
              }

              if (content instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) map;
                memory.replaceBlock(block, data);
                return ToolResult.success("Replaced contents of %s".formatted(block));
              }

              return ToolResult.failure("Content must be an object");
            })
        .build();
  }

  /** Read the current contents of a core memory block. */
  public static Tool coreMemoryRead(Memory memory) {
    return Tool.newBuilder()
        .withName("core_memory_read")
        .withDescription("Read the current contents of a core memory block.")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("block")
                .withType(ParameterType.STRING)
                .withDescription("Name of the memory block to read")
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              var block = requireString(args, "block");
              if (block == null) {
                return ToolResult.failure("Parameter 'block' is required and must be a string");
              }
              var memBlock = memory.block(block);
              if (memBlock == null) {
                return ToolResult.failure("Memory block '%s' not found".formatted(block));
              }
              return ToolResult.success(memBlock.render(), memBlock.data());
            })
        .build();
  }

  /** Store information in archival memory for long-term retention. */
  public static Tool archivalInsert(Memory memory) {
    return Tool.newBuilder()
        .withName("archival_memory_insert")
        .withDescription(
            """
				Store information in archival memory for long-term retention.
				Use this for information that doesn't fit in core memory but may be useful later.
				""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("content")
                .withType(ParameterType.STRING)
                .withDescription("The content to store")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("tags")
                .withType(ParameterType.ARRAY)
                .withDescription("Optional tags for categorization")
                .withRequired(false)
                .withItems(ToolParameter.newBuilder().withType(ParameterType.STRING).build())
                .build())
        .withExecutor(
            args -> {
              var content = requireString(args, "content");
              if (content == null) {
                return ToolResult.failure("Parameter 'content' is required and must be a string");
              }
              var tags = args.get("tags");

              var metadata = tags != null ? Map.of("tags", tags) : Map.<String, Object>of();
              memory.archive(content, metadata);

              return ToolResult.success("Stored in archival memory");
            })
        .build();
  }

  /** Search archival memory. */
  public static Tool archivalSearch(Memory memory) {
    return Tool.newBuilder()
        .withName("archival_memory_search")
        .withDescription(
            """
				Search archival memory for relevant information.
				Returns matching entries from long-term storage.
				""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("query")
                .withType(ParameterType.STRING)
                .withDescription("Search query")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("limit")
                .withType(ParameterType.INTEGER)
                .withDescription("Maximum number of results (default: 5)")
                .withRequired(false)
                .withDefaultValue(5)
                .build())
        .withExecutor(
            args -> {
              var query = requireString(args, "query");
              if (query == null) {
                return ToolResult.failure("Parameter 'query' is required and must be a string");
              }
              var limit = args.get("limit");
              var maxResults = limit instanceof Number n ? n.intValue() : 5;

              var results = memory.searchArchive(query, maxResults);
              if (results.isEmpty()) {
                return ToolResult.success("No results found");
              }

              var output =
                  results.stream().map(e -> "- " + e.content()).collect(Collectors.joining("\n"));

              return ToolResult.success(output, results);
            })
        .build();
  }

  /** Search conversation history for the bound session. */
  public static Tool conversationSearch(Memory memory, UUID sessionId) {
    return Tool.newBuilder()
        .withName("conversation_search")
        .withDescription(
            """
				Search through past conversation history.
				Useful for recalling previous discussions.
				""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("query")
                .withType(ParameterType.STRING)
                .withDescription("Search query")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("limit")
                .withType(ParameterType.INTEGER)
                .withDescription("Maximum number of results (default: 10)")
                .withRequired(false)
                .withDefaultValue(10)
                .build())
        .withExecutor(
            args -> {
              var query = requireString(args, "query");
              if (query == null) {
                return ToolResult.failure("Parameter 'query' is required and must be a string");
              }
              var limit = args.get("limit");
              var maxResults = limit instanceof Number n ? n.intValue() : 10;

              var results = memory.searchHistory(sessionId, query, maxResults);
              if (results.isEmpty()) {
                return ToolResult.success("No matching messages found");
              }

              var output =
                  results.stream()
                      .map(m -> "[%s] %s".formatted(m.role(), m.content()))
                      .collect(Collectors.joining("\n"));

              return ToolResult.success(output, results);
            })
        .build();
  }
}
