/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolArgs;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code Read} tool. Reads a file from the workspace, returns line-numbered content, and
 * records the read via {@link FileTracker} so Phase 3 edit tools can detect stale files.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code path} (required) — workspace-relative or absolute path.
 *   <li>{@code offset} (optional, integer; default 1) — 1-based line at which output begins.
 *   <li>{@code limit} (optional, integer; default 2000) — maximum number of lines emitted.
 * </ul>
 *
 * <p>The output is rendered with 6-digit right-padded line numbers separated by a tab — compatible
 * with the {@code Read} tool format Claude Code uses.
 *
 * <p>{@link ToolBinding} wires this as {@link ToolCategory#READ} with a path-aware permission key
 * so the permission system can match path globs.
 */
public final class ReadTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Read";

  private static final int DEFAULT_LIMIT = 2000;

  private ReadTool() {}

  /**
   * Build a tool binding bound to the given workspace + tracker.
   *
   * @param workspace the path-jail workspace; non-null
   * @param tracker per-session read/write ledger; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if either argument is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace, FileTracker tracker) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(tracker, "tracker must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Reads a file from the workspace. Returns line-numbered content. "
                    + "Use 'offset' (1-based) and 'limit' to page through large files.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription("Workspace-relative or absolute path to the file.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("offset")
                        .withType(ParameterType.INTEGER)
                        .withDescription("1-based line at which output begins. Defaults to 1.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("limit")
                        .withType(ParameterType.INTEGER)
                        .withDescription(
                            "Maximum number of lines emitted. Defaults to " + DEFAULT_LIMIT + ".")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor((args, ctx) -> execute(ctx, workspace, tracker, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(NAME, ToolArgs.stringArg(args, "path")))
        .build();
  }

  private static ToolResult execute(
      ToolContext ctx, WorkspaceRoot workspace, FileTracker tracker, Map<String, Object> args) {
    ctx.cancellation().throwIfCancelled();
    var pathArg = ToolArgs.stringArg(args, "path");
    if (pathArg.isEmpty()) {
      return ToolResult.failure("Read: missing required 'path' argument");
    }
    try {
      var resolved = workspace.resolveSafe(pathArg);
      if (!Files.isRegularFile(resolved)) {
        return ToolResult.failure("Read: not a regular file: " + workspace.relativize(resolved));
      }
      var fingerprint = FileFingerprint.of(resolved);
      tracker.recordRead(resolved, fingerprint);
      var content = Files.readString(resolved, StandardCharsets.UTF_8);
      var lines = content.split("\n", -1);
      var offset = ToolArgs.intArg(args, "offset", 1);
      var limit = ToolArgs.intArg(args, "limit", DEFAULT_LIMIT);
      if (offset < 1) {
        return ToolResult.failure("Read: 'offset' must be >= 1, got " + offset);
      }
      if (limit < 1) {
        return ToolResult.failure("Read: 'limit' must be >= 1, got " + limit);
      }
      var out = new StringBuilder();
      var end = Math.min(lines.length, offset - 1 + limit);
      for (int i = offset - 1; i < end; i++) {
        out.append(String.format("%6d\t%s%n", i + 1, lines[i]));
      }
      if (end < lines.length) {
        out.append("[truncated at line ")
            .append(end)
            .append(" of ")
            .append(lines.length)
            .append("]\n");
      }
      return ToolResult.success(out.toString());
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("Read: " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error reading " + pathArg + ": " + e.getMessage());
    }
  }
}
