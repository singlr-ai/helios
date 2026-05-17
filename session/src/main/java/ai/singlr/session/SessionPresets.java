/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Model;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>Three curated presets ship today: {@link #minimal(Model)}, {@link #readOnly(Model, Path)} /
 * {@link #readOnly(Model, WorkspaceRoot)}, {@link #workspace(Model, Path)} / {@link
 * #workspace(Model, WorkspaceRoot)}, and {@link #openEnded(Model, Path, ExecutionProvider)} /
 * {@link #openEnded(Model, WorkspaceRoot, ExecutionProvider)} — the last enables code execution via
 * an explicit {@link ExecutionProvider}.
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

  /**
   * Open-ended agent: workspace tools (Read / Glob / Grep / LS + MemoryWrite) plus the {@link
   * ExecuteTool} dispatched through the supplied {@link ExecutionProvider}. Permission policy is
   * {@link Permission#defaultInWorkspace()} which already asks before {@code Execute}, so the user
   * confirms each execution by default.
   *
   * <p>Use {@link #openEnded(Model, WorkspaceRoot, ExecutionProvider)} if you already hold a {@code
   * WorkspaceRoot}. The execution provider is required and must not be {@code null} — if you want
   * to opt out of execution, use {@link #workspace(Model, Path)} instead.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspaceRoot the directory the workspace tools are confined to; non-null, must exist
   * @param executionProvider the provider routing {@code Execute} dispatches; non-null
   * @return a Builder pre-populated with workspace tooling, the {@code Execute} tool, memory, the
   *     default permission policy, and the supplied execution provider
   * @throws NullPointerException if any argument is null
   */
  public static SessionOptions.Builder openEnded(
      Model model, Path workspaceRoot, ExecutionProvider executionProvider) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return openEnded(model, WorkspaceRoot.of(workspaceRoot), executionProvider);
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #openEnded(Model, Path, ExecutionProvider)} for
   * callers that already hold a constructed workspace.
   *
   * @param model the LLM the session loop drives; non-null
   * @param workspace the workspace; non-null
   * @param executionProvider the provider routing {@code Execute} dispatches; non-null
   * @return a Builder pre-populated with workspace tooling plus the {@code Execute} tool
   * @throws NullPointerException if any argument is null
   */
  public static SessionOptions.Builder openEnded(
      Model model, WorkspaceRoot workspace, ExecutionProvider executionProvider) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(executionProvider, "executionProvider must not be null");
    var memoryBackend = FileSystemMemoryBackend.of(workspace);
    var bindings = new ArrayList<ToolBinding>(6);
    bindings.add(ReadTool.binding(workspace, InMemoryFileTracker.create()));
    bindings.add(GlobTool.binding(workspace));
    bindings.add(GrepTool.binding(workspace));
    bindings.add(LsTool.binding(workspace));
    bindings.add(MemoryWriteTool.binding(memoryBackend));
    bindings.add(ExecuteTool.binding(executionProvider));
    return SessionOptions.newBuilder()
        .withModel(model)
        .withTools(new ToolRegistry(bindings))
        .withPermission(Permission.defaultInWorkspace())
        .withMemoryBackend(memoryBackend)
        .withExecutionProvider(executionProvider);
  }
}
