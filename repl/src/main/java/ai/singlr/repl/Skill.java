/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.common.Strings;
import ai.singlr.repl.host.HostFunction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A composable bundle of "instructions + env tips + tools" the model can pull on top of an RLM run.
 * Mirrors Trampoline's {@code Skill} primitive, with a separate {@code envTips} field inspired by
 * Prime Intellect's per-environment numbered strategy steps.
 *
 * <p>Two text fields, two purposes:
 *
 * <ul>
 *   <li>{@link #instructions()} — what the skill IS and what tools it bundles. Rendered under a
 *       {@code "## Skill: <name>"} header. Stable description; rarely tuned.
 *   <li>{@link #envTips()} — HOW to use the skill: numbered protocol steps, decomposition tips, or
 *       other strategy-shaped guidance that's worth A/B-testing per skill. Rendered under a {@code
 *       "## Strategy: <name>"} header. Easy to drop or replace per skill without touching the
 *       skill's instructions or tools.
 * </ul>
 *
 * <p>Splitting these matters because Prime Intellect's RLM benchmarks showed tips lift performance
 * for some models and hurt it for others — they need to be a per-skill knob, not a global
 * commitment. Keeping them in a separate field makes A/B tests trivial: drop tips for one skill
 * without touching others.
 *
 * <p>Skills compose via {@link #merge}; merge raises if two skills try to register the same tool
 * name, so accidental shadowing surfaces at startup, not runtime.
 *
 * @param name human-readable skill identifier; used in merged section headers
 * @param instructions text the harness appends under {@code "## Skill: <name>"}. Teaches the model
 *     what the bundled tools are for and when to use them. May be blank
 * @param envTips numbered or prose strategy tips for using this skill. Rendered under {@code "##
 *     Strategy: <name>"}. May be blank — leave empty when no skill-specific protocol applies
 * @param tools host functions the skill exposes inside the sandbox. Names must not collide with
 *     reserved framework names ({@code predict}, {@code submit}) or with other skills' tools when
 *     merged
 */
public record Skill(String name, String instructions, String envTips, List<HostFunction> tools) {

  public Skill {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Skill name must not be null or blank");
    }
    if (instructions == null) {
      instructions = "";
    }
    if (envTips == null) {
      envTips = "";
    }
    tools = tools == null ? List.of() : List.copyOf(tools);
    for (var tool : tools) {
      if ("predict".equals(tool.name()) || "submit".equals(tool.name())) {
        throw new IllegalArgumentException(
            "Skill '"
                + name
                + "' cannot register reserved tool name '"
                + tool.name()
                + "' — predict and submit are owned by the framework");
      }
    }
  }

  /** Convenience: skill with instructions and tools, no envTips. */
  public Skill(String name, String instructions, List<HostFunction> tools) {
    this(name, instructions, "", tools);
  }

  /**
   * Merge several skills into one. Concatenates instructions under {@code "## Skill: <name>"}
   * headers and envTips under {@code "## Strategy: <name>"} headers, preserving skill order and
   * keeping the two sections distinct so they can be A/B-tested independently downstream. Tool
   * lists are concatenated; raises {@link IllegalArgumentException} if any two skills register a
   * tool with the same name.
   *
   * <p>The merged Skill's {@link #instructions()} carries every block (both skill bodies AND every
   * skill's strategy tips, in source order) so callers that flatten Skill into a system-prompt
   * strategy field get all sections at once. {@link #envTips()} on the merged Skill is empty — it's
   * been folded into instructions to make the wire-up simple for harness consumers.
   *
   * @param skills the skills to merge in order
   * @return a single skill named {@code "merged"} carrying all sections and tools
   */
  public static Skill merge(List<Skill> skills) {
    if (skills == null || skills.isEmpty()) {
      return new Skill("merged", "", "", List.of());
    }
    var combined = new StringBuilder();
    var allTools = new ArrayList<HostFunction>();
    var seenToolNames = new LinkedHashMap<String, String>();
    for (var skill : skills) {
      if (!skill.instructions().isBlank()) {
        appendSection(combined, "## Skill: " + skill.name(), skill.instructions());
      }
      if (!skill.envTips().isBlank()) {
        appendSection(combined, "## Strategy: " + skill.name(), skill.envTips());
      }
      for (var tool : skill.tools()) {
        var existingOwner = seenToolNames.put(tool.name(), skill.name());
        if (existingOwner != null) {
          throw new IllegalArgumentException(
              "Tool name conflict: skills '"
                  + existingOwner
                  + "' and '"
                  + skill.name()
                  + "' both register tool '"
                  + tool.name()
                  + "'");
        }
        allTools.add(tool);
      }
    }
    return new Skill("merged", combined.toString(), "", allTools);
  }

  private static void appendSection(StringBuilder sb, String header, String body) {
    if (sb.length() > 0) {
      sb.append("\n\n");
    }
    sb.append(header).append('\n').append(body.strip());
  }
}
