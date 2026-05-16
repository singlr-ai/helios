/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionPresetsTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static Set<String> toolNames(SessionOptions opts) {
    return opts.tools().bindings().stream()
        .map(b -> b.tool().name())
        .collect(Collectors.toUnmodifiableSet());
  }

  // ── minimal ─────────────────────────────────────────────────────────────

  @Test
  void minimalReturnsBuilderWithModelAndDefaults() {
    var opts = SessionPresets.minimal(stubModel()).build();
    assertEquals("stub", opts.model().id());
    assertTrue(opts.tools().bindings().isEmpty(), "no tools by default");
    assertTrue(opts.permission().isEmpty(), "no permission policy by default");
    assertTrue(opts.memoryBackend().isEmpty(), "no memory backend by default");
    assertSame(CostCalculator.ZERO, opts.costCalculator());
  }

  @Test
  void minimalRejectsNullModel() {
    var ex = assertThrows(NullPointerException.class, () -> SessionPresets.minimal(null));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void minimalReturnedBuilderIsChainable() {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = SessionPresets.minimal(stubModel()).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }

  // ── readOnly ────────────────────────────────────────────────────────────

  @Test
  void readOnlyWiresReadInspectionTools(@TempDir Path tmp) {
    var opts = SessionPresets.readOnly(stubModel(), tmp).build();
    assertEquals(Set.of("Read", "Glob", "Grep", "LS"), toolNames(opts));
  }

  @Test
  void readOnlyUsesPlanModePermission(@TempDir Path tmp) {
    var opts = SessionPresets.readOnly(stubModel(), tmp).build();
    var perm = opts.permission().orElseThrow();
    assertEquals(PermissionMode.PLAN, perm.mode());
  }

  @Test
  void readOnlyHasNoMemoryBackend(@TempDir Path tmp) {
    var opts = SessionPresets.readOnly(stubModel(), tmp).build();
    assertTrue(opts.memoryBackend().isEmpty());
  }

  @Test
  void readOnlyPathOverloadDelegatesToWorkspaceOverload(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var fromPath = SessionPresets.readOnly(stubModel(), tmp).build();
    var fromWorkspace = SessionPresets.readOnly(stubModel(), ws).build();
    assertEquals(toolNames(fromPath), toolNames(fromWorkspace));
    assertEquals(
        fromPath.permission().orElseThrow().mode(),
        fromWorkspace.permission().orElseThrow().mode());
  }

  @Test
  void readOnlyRejectsNullModel(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertEquals(
        "model must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.readOnly(null, tmp))
            .getMessage());
    assertEquals(
        "model must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.readOnly(null, ws))
            .getMessage());
  }

  @Test
  void readOnlyRejectsNullWorkspace() {
    assertEquals(
        "workspaceRoot must not be null",
        assertThrows(
                NullPointerException.class, () -> SessionPresets.readOnly(stubModel(), (Path) null))
            .getMessage());
    assertEquals(
        "workspace must not be null",
        assertThrows(
                NullPointerException.class,
                () -> SessionPresets.readOnly(stubModel(), (WorkspaceRoot) null))
            .getMessage());
  }

  @Test
  void readOnlyReturnedBuilderIsChainable(@TempDir Path tmp) {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = SessionPresets.readOnly(stubModel(), tmp).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }

  // ── workspace ───────────────────────────────────────────────────────────

  @Test
  void workspaceWiresReadToolsPlusMemoryWrite(@TempDir Path tmp) {
    var opts = SessionPresets.workspace(stubModel(), tmp).build();
    assertEquals(Set.of("Read", "Glob", "Grep", "LS", "MemoryWrite"), toolNames(opts));
  }

  @Test
  void workspaceUsesDefaultInWorkspacePermission(@TempDir Path tmp) {
    var opts = SessionPresets.workspace(stubModel(), tmp).build();
    var perm = opts.permission().orElseThrow();
    // defaultInWorkspace lives in DEFAULT mode and equals Permission.defaultInWorkspace().
    assertEquals(Permission.defaultInWorkspace().mode(), perm.mode());
  }

  @Test
  void workspaceRegistersMemoryBackend(@TempDir Path tmp) {
    var opts = SessionPresets.workspace(stubModel(), tmp).build();
    assertTrue(opts.memoryBackend().isPresent());
  }

  @Test
  void workspacePathOverloadDelegatesToWorkspaceOverload(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var fromPath = SessionPresets.workspace(stubModel(), tmp).build();
    var fromWorkspace = SessionPresets.workspace(stubModel(), ws).build();
    assertEquals(toolNames(fromPath), toolNames(fromWorkspace));
  }

  @Test
  void workspaceRejectsNullModel(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    assertEquals(
        "model must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.workspace(null, tmp))
            .getMessage());
    assertEquals(
        "model must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.workspace(null, ws))
            .getMessage());
  }

  @Test
  void workspaceRejectsNullWorkspace() {
    assertEquals(
        "workspaceRoot must not be null",
        assertThrows(
                NullPointerException.class,
                () -> SessionPresets.workspace(stubModel(), (Path) null))
            .getMessage());
    assertEquals(
        "workspace must not be null",
        assertThrows(
                NullPointerException.class,
                () -> SessionPresets.workspace(stubModel(), (WorkspaceRoot) null))
            .getMessage());
  }

  @Test
  void workspaceReturnedBuilderIsChainable(@TempDir Path tmp) {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = SessionPresets.workspace(stubModel(), tmp).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }
}
