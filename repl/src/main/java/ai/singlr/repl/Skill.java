/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.host.HostFunction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A composable bundle of "instructions + tools" the model can pull on top of an RLM run. Mirrors
 * Trampoline's {@code Skill} primitive, minus the Pyodide-specific {@code packages} and {@code
 * modules} fields (Helios doesn't have a pre-eval-at-sandbox-startup hook today; if a need surfaces
 * we can extend).
 *
 * <p>The shape lets you ship reusable capability packs — a PDF skill, a spreadsheet skill, a
 * domain-specific data-access skill — without forcing every harness consumer to wire instructions +
 * host functions separately. Skills compose via {@link #merge}; merge raises if two skills try to
 * register the same tool name, so accidental shadowing surfaces at startup, not runtime.
 *
 * @param name human-readable skill identifier; used in merged instruction headers
 * @param instructions text the harness appends to the system prompt under a {@code "## Skill:
 *     <name>"} header. Teaches the model what the bundled tools are for and when to use them
 * @param tools host functions the skill exposes inside the sandbox. Names must not collide with
 *     reserved framework names ({@code predict}, {@code submit}) or with other skills' tools when
 *     merged
 */
public record Skill(String name, String instructions, List<HostFunction> tools) {

  public Skill {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Skill name must not be null or blank");
    }
    if (instructions == null) {
      instructions = "";
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

  /**
   * Merge several skills into one. Concatenates instructions under {@code "## Skill: <name>"}
   * headers and concatenates tool lists. Raises {@link IllegalArgumentException} if any two skills
   * register a tool with the same name, so name shadowing is caught at composition time.
   *
   * @param skills the skills to merge in order
   * @return a single skill named {@code "merged"} carrying all instructions and tools
   */
  public static Skill merge(List<Skill> skills) {
    if (skills == null || skills.isEmpty()) {
      return new Skill("merged", "", List.of());
    }
    var instructions = new StringBuilder();
    var allTools = new ArrayList<HostFunction>();
    var seenToolNames = new LinkedHashMap<String, String>();
    for (var skill : skills) {
      if (!skill.instructions().isBlank()) {
        if (instructions.length() > 0) {
          instructions.append("\n\n");
        }
        instructions
            .append("## Skill: ")
            .append(skill.name())
            .append('\n')
            .append(skill.instructions().strip());
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
    return new Skill("merged", instructions.toString(), allTools);
  }
}
