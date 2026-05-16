/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Model;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Curated {@link SessionOptions.Builder} factories for the patterns most agentic SDK users actually
 * want — assembled from real v2 building blocks so callers do not have to learn the tool /
 * permission / memory wiring on day one.
 *
 * <p>Every factory returns a {@link SessionOptions.Builder}, never a built {@link SessionOptions},
 * so callers can layer additional configuration on top (cost calculator, hooks, custom limits) and
 * call {@code build()} themselves:
 *
 * <pre>{@code
 * var options = SessionPresets.workspace(model, Path.of("/repo"))
 *     .withCostCalculator(calc)
 *     .withHook(myHook)
 *     .build();
 * }</pre>
 *
 * <p>Presets cover the matrix that exists in v2 today; the {@code openEnded} preset for shell
 * execution will land alongside the Execute tool in Phase 5.
 */
public final class SessionPresets {

  private SessionPresets() {}

  /**
   * Minimal session: just the model, framework defaults for everything else. Equivalent to {@code
   * SessionOptions.newBuilder().withModel(model)} — kept as a preset so consumers have a uniform
   * {@code SessionPresets.*} entrypoint for every shape.
   *
   * @param model the LLM the session loop drives; non-null
   * @return a Builder pre-populated with the model
   * @throws NullPointerException if {@code model} is null
   */
  public static SessionOptions.Builder minimal(Model model) {
    Objects.requireNonNull(model, "model must not be null");
    return SessionOptions.newBuilder().withModel(model);
  }

  /**
   * Read-only inspector: the four filesystem read tools ({@code Read}, {@code Glob}, {@code Grep},
   * {@code LS}) rooted at {@code workspaceRoot}, gated by {@link Permission#planMode()} so the
   * model cannot escalate to writes or execution.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspaceRoot the directory the read tools are confined to; non-null, must exist
   * @return a Builder pre-populated with read-only tooling
   * @throws NullPointerException if {@code model} or {@code workspaceRoot} is null
   */
  public static SessionOptions.Builder readOnly(Model model, Path workspaceRoot) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return readOnly(model, WorkspaceRoot.of(workspaceRoot));
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #readOnly(Model, Path)} for callers that already hold
   * a constructed workspace.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspace the workspace the read tools are confined to; non-null
   * @return a Builder pre-populated with read-only tooling
   * @throws NullPointerException if {@code model} or {@code workspace} is null
   */
  public static SessionOptions.Builder readOnly(Model model, WorkspaceRoot workspace) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(workspace, "workspace must not be null");
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, InMemoryFileTracker.create()),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace),
                LsTool.binding(workspace)));
    return SessionOptions.newBuilder()
        .withModel(model)
        .withTools(tools)
        .withPermission(Permission.planMode());
  }

  /**
   * Full workspace agent: same read tools as {@link #readOnly(Model, WorkspaceRoot)}, plus
   * persistent memory (via {@link FileSystemMemoryBackend} rooted at the workspace), plus the
   * {@link Permission#defaultInWorkspace()} policy that allows reads / memory and asks the user
   * before writes or execution.
   *
   * <p>The {@code MemoryRead} tool is auto-registered by the session whenever a memory backend is
   * present; {@code MemoryWrite} is added to the registry explicitly here so the model can persist
   * notes between turns.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspaceRoot the directory the tools are confined to; non-null, must exist
   * @return a Builder pre-populated with workspace tooling, memory, and the default permission
   *     policy
   * @throws NullPointerException if {@code model} or {@code workspaceRoot} is null
   */
  public static SessionOptions.Builder workspace(Model model, Path workspaceRoot) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return workspace(model, WorkspaceRoot.of(workspaceRoot));
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #workspace(Model, Path)} for callers that already hold
   * a constructed workspace.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspace the workspace; non-null
   * @return a Builder pre-populated with workspace tooling, memory, and the default permission
   *     policy
   * @throws NullPointerException if {@code model} or {@code workspace} is null
   */
  public static SessionOptions.Builder workspace(Model model, WorkspaceRoot workspace) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(workspace, "workspace must not be null");
    var memoryBackend = FileSystemMemoryBackend.of(workspace);
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, InMemoryFileTracker.create()),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace),
                LsTool.binding(workspace),
                MemoryWriteTool.binding(memoryBackend)));
    return SessionOptions.newBuilder()
        .withModel(model)
        .withTools(tools)
        .withPermission(Permission.defaultInWorkspace())
        .withMemoryBackend(memoryBackend);
  }
}
