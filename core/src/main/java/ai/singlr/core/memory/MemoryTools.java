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

  /** Create memory tools bound to the given memory instance, user, and session. */
  public static List<Tool> boundTo(Memory memory, String userId, UUID sessionId) {
    return List.of(memoryUpdate(memory), memoryRead(memory));
  }

  /** Update a key-value pair in a core memory block with maxSize enforcement. */
  public static Tool memoryUpdate(Memory memory) {
    return Tool.newBuilder()
        .withName("memory_update")
        .withDescription(
            """
            Update a key-value pair in a core memory block.
            Use this to store new information or update existing facts.
            """)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("block")
                .withType(ParameterType.STRING)
                .withDescription(
                    "Name of the memory block (e.g., 'identity', 'user_profile', 'working_memory')")
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
              var value = requireString(args, "value");
              if (value == null) {
                return ToolResult.failure("Parameter 'value' is required and must be a string");
              }

              var memBlockOpt = memory.block(block);
              if (memBlockOpt.isEmpty()) {
                return ToolResult.failure("Memory block '%s' not found".formatted(block));
              }
              var memBlock = memBlockOpt.get();

              var updated = memBlock.withValue(key, value);
              if (updated.render().length() > memBlock.maxSize()) {
                return ToolResult.failure(
                    "Update would exceed block size limit (%d chars). Condense the value."
                        .formatted(memBlock.maxSize()));
              }

              memory.updateBlock(block, key, value);
              return ToolResult.success("Updated %s.%s".formatted(block, key));
            })
        .build();
  }

  /** Read the current contents of a core memory block. */
  public static Tool memoryRead(Memory memory) {
    return Tool.newBuilder()
        .withName("memory_read")
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
              var memBlockOpt = memory.block(block);
              if (memBlockOpt.isEmpty()) {
                return ToolResult.failure("Memory block '%s' not found".formatted(block));
              }
              var memBlock = memBlockOpt.get();
              return ToolResult.success(memBlock.render(), memBlock.data());
            })
        .build();
  }
}
