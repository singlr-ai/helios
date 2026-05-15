/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolArgs;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@code LS} tool. Lists the immediate entries of a workspace directory, sorted
 * directories-first, name-ascending.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code path} (optional, default {@code "."}) — workspace-relative or absolute directory
 *       path.
 * </ul>
 *
 * <p>Hidden entries (names starting with {@code "."}) are included; symlinks are reported as {@code
 * "@"} entries without following them. Output is plain text, one entry per line, with a trailing
 * {@code "/"} for directories.
 */
public final class LsTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "LS";

  private LsTool() {}

  /**
   * Build a tool binding bound to the given workspace.
   *
   * @param workspace the path-jail workspace; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if {@code workspace} is null
   */
  public static ToolBinding binding(WorkspaceRoot workspace) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Lists the immediate entries of a directory in the workspace, "
                    + "directories first, then files. Hidden entries included.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("path")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Workspace-relative or absolute directory path. Defaults to '.'.")
                        .withRequired(false)
                        .build()))
            .withIdempotent(true)
            .withExecutor(args -> execute(workspace, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(args -> new ToolPermissionKey(NAME, ToolArgs.pathArg(args)))
        .build();
  }

  private static ToolResult execute(WorkspaceRoot workspace, Map<String, Object> args) {
    var pathArg = ToolArgs.pathArg(args);
    try {
      var resolved = workspace.resolveSafe(pathArg);
      if (!Files.isDirectory(resolved)) {
        return ToolResult.failure("LS: not a directory: " + workspace.relativize(resolved));
      }
      var entries = new ArrayList<Entry>();
      try (var stream = Files.newDirectoryStream(resolved)) {
        for (Path entry : stream) {
          entries.add(Entry.of(entry));
        }
      } catch (DirectoryIteratorException e) {
        throw e.getCause();
      }
      entries.sort(
          Comparator.<Entry>comparingInt(e -> e.kind.ordinal()).thenComparing(e -> e.name));
      var out = new StringBuilder();
      for (var entry : entries) {
        out.append(entry.render()).append('\n');
      }
      return ToolResult.success(out.toString());
    } catch (WorkspaceRoot.WorkspaceEscapeException e) {
      return ToolResult.failure("LS: " + e.getMessage());
    } catch (IOException e) {
      return ToolResult.failure("LS: I/O error listing " + pathArg + ": " + e.getMessage());
    }
  }

  private record Entry(EntryKind kind, String name) {
    static Entry of(Path p) {
      var name = p.getFileName().toString();
      EntryKind kind;
      if (Files.isSymbolicLink(p)) {
        kind = EntryKind.SYMLINK;
      } else if (Files.isDirectory(p)) {
        kind = EntryKind.DIRECTORY;
      } else {
        kind = EntryKind.FILE;
      }
      return new Entry(kind, name);
    }

    String render() {
      return switch (kind) {
        case DIRECTORY -> name + "/";
        case SYMLINK -> name + "@";
        case FILE -> name;
      };
    }
  }

  private enum EntryKind {
    DIRECTORY,
    FILE,
    SYMLINK
  }
}
