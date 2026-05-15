/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import ai.singlr.core.model.ToolCall;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PreToolUseHook;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolRegistry;
import java.util.Objects;

/**
 * The Permission system, packaged as a {@link PreToolUseHook} at priority {@code 50} so it fires
 * before user-supplied hooks (default priority {@code 100}). Translates a {@link Permission} +
 * {@link ToolRegistry} into the per-call decision and surfaces it as a {@link HookOutcome}.
 *
 * <p>Evaluation order per spec §12.4:
 *
 * <ol>
 *   <li>Resolve {@code permissionKey} from the tool + input via {@link ToolBinding}.
 *   <li>If any {@code deny} rule matches → {@link HookOutcome.Block}.
 *   <li>If {@link PermissionMode#BYPASS_PERMISSIONS} → {@link HookOutcome.Continue}.
 *   <li>If any {@code allow} rule matches → {@link HookOutcome.Continue}.
 *   <li>If any {@code ask} rule matches: surface a question (Phase 2 part 5 wires the handler;
 *       until then, falls back to {@link HookOutcome.Block} with a clear reason).
 *   <li>Default-by-category: {@link ToolCategory#READ} / {@link ToolCategory#SEARCH} → Continue (or
 *       Block under {@link PermissionMode#PLAN}); {@link ToolCategory#WRITE} / {@link
 *       ToolCategory#EXECUTION} / {@link ToolCategory#NETWORK} → Ask (Block today); {@link
 *       ToolCategory#CONTROL} / {@link ToolCategory#DELEGATION} → Continue.
 * </ol>
 *
 * <p>If the tool is not registered in the {@link ToolRegistry}, the evaluator returns {@link
 * HookOutcome.Continue} — the tool dispatch path will surface its own "tool not found" failure, and
 * the permission hook should not block on an unknown name.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. Safe to share across sessions.
 */
public final class DefaultPermissionEvaluator implements PreToolUseHook {

  private final Permission permission;
  private final ToolRegistry tools;
  private final RuleMatcher matcher;
  private final int priority;

  /**
   * Build an evaluator with the spec's default priority of 50.
   *
   * @param permission the policy to enforce; non-null
   * @param tools the tool registry (for category + permissionKey lookup); non-null
   * @throws NullPointerException if either argument is null
   */
  public DefaultPermissionEvaluator(Permission permission, ToolRegistry tools) {
    this(permission, tools, 50);
  }

  /**
   * Build an evaluator with a custom priority.
   *
   * @param permission the policy to enforce; non-null
   * @param tools the tool registry; non-null
   * @param priority non-negative priority
   * @throws NullPointerException if {@code permission} or {@code tools} is null
   * @throws IllegalArgumentException if {@code priority} is negative
   */
  public DefaultPermissionEvaluator(Permission permission, ToolRegistry tools, int priority) {
    this.permission = Objects.requireNonNull(permission, "permission must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    if (priority < 0) {
      throw new IllegalArgumentException("priority must be non-negative, got " + priority);
    }
    this.priority = priority;
    this.matcher = new RuleMatcher();
  }

  /**
   * The policy this evaluator enforces.
   *
   * @return the permission record
   */
  public Permission permission() {
    return permission;
  }

  @Override
  public int priority() {
    return priority;
  }

  @Override
  public String name() {
    return "DefaultPermissionEvaluator";
  }

  @Override
  public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");

    var bindingOpt = tools.get(call.name());
    if (bindingOpt.isEmpty()) {
      return HookOutcome.cont();
    }
    var binding = bindingOpt.orElseThrow();
    var key = binding.permissionKey(call.arguments());
    var decision = evaluate(key, binding.category());
    return switch (decision.effect()) {
      case ALLOW -> HookOutcome.cont();
      case ASK ->
          // Phase 2 part 5 will wire an AskUserQuestion handler. Until then, an ASK without a
          // handler is conservatively a Block — better to refuse than silently allow a
          // user-confirmation gate.
          HookOutcome.block(
              "permission: ASK rule without handler — Phase 2 part 5 wires this. "
                  + decision.reason());
      case DENY -> HookOutcome.block("permission: " + decision.reason());
    };
  }

  /**
   * Evaluate a tool call's permission key against the policy. Visible for testing.
   *
   * @param key the canonical permission key; non-null
   * @param category the tool's category; non-null
   * @return the resolved decision
   */
  public PermissionDecision evaluate(
      ai.singlr.session.tools.ToolPermissionKey key, ToolCategory category) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(category, "category must not be null");

    var denied = matcher.firstMatch(key, permission.deny());
    if (denied.isPresent()) {
      return PermissionDecision.deny("deny rule for " + key.toolName());
    }
    if (permission.mode() == PermissionMode.BYPASS_PERMISSIONS) {
      return PermissionDecision.allow("BYPASS_PERMISSIONS");
    }
    var allowed = matcher.firstMatch(key, permission.allow());
    if (allowed.isPresent()) {
      return PermissionDecision.allow("allow rule for " + key.toolName());
    }
    var asked = matcher.firstMatch(key, permission.ask());
    if (asked.isPresent()) {
      return PermissionDecision.ask("ask rule for " + key.toolName());
    }
    return defaultForCategory(category, permission.mode(), key.toolName());
  }

  private static PermissionDecision defaultForCategory(
      ToolCategory category, PermissionMode mode, String toolName) {
    return switch (category) {
      case READ, SEARCH ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.allow("default-allow under PLAN (" + toolName + ")")
              : PermissionDecision.allow("default-allow READ/SEARCH (" + toolName + ")");
      case WRITE ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids writes (" + toolName + ")")
              : mode == PermissionMode.ACCEPT_EDITS
                  ? PermissionDecision.allow("ACCEPT_EDITS allows writes (" + toolName + ")")
                  : PermissionDecision.ask("default-ask on WRITE (" + toolName + ")");
      case EXECUTION ->
          mode == PermissionMode.PLAN
              ? PermissionDecision.deny("PLAN mode forbids execution (" + toolName + ")")
              : PermissionDecision.ask("default-ask on EXECUTION (" + toolName + ")");
      case NETWORK -> PermissionDecision.ask("default-ask on NETWORK (" + toolName + ")");
      case CONTROL -> PermissionDecision.allow("default-allow CONTROL (" + toolName + ")");
      case DELEGATION -> PermissionDecision.allow("default-allow DELEGATION (" + toolName + ")");
    };
  }
}
