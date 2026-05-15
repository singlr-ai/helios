/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Built-in {@code AskUserQuestion} tool. Wraps a {@link QuestionGateway} into the standard tool
 * contract so the model emits a structured question and receives the user's choice as a tool
 * result.
 *
 * <p>The model supplies:
 *
 * <ul>
 *   <li>{@code question} (required, string) — the full question text.
 *   <li>{@code header} (optional, string ≤ 12 chars) — chip-style label for the question.
 *   <li>{@code options} (required, array of objects) — 2–4 entries; each {@code {label,
 *       description}}.
 *   <li>{@code multiSelect} (optional, boolean) — whether multiple selections are permitted.
 *       Defaults to false.
 * </ul>
 *
 * <p>The tool generates a fresh {@code questionId} per call, calls {@link
 * QuestionGateway#ask(AskUserQuestionRequest)}, blocks the executing virtual thread on the gateway,
 * and returns a {@link ToolResult} whose output is a line per selection plus any free-text the user
 * supplied. {@link ToolCategory#CONTROL} — default-allow under the permission system.
 */
public final class AskUserQuestionTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "AskUserQuestion";

  private AskUserQuestionTool() {}

  /**
   * Build a binding bound to the given gateway.
   *
   * @param gateway the session's gateway; non-null
   * @return a fresh binding
   * @throws NullPointerException if {@code gateway} is null
   */
  public static ToolBinding binding(QuestionGateway gateway) {
    Objects.requireNonNull(gateway, "gateway must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Asks the user a structured question. The user picks one or more "
                    + "labelled options; the response carries the chosen label(s).")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("question")
                        .withType(ParameterType.STRING)
                        .withDescription("The full question shown to the user.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("header")
                        .withType(ParameterType.STRING)
                        .withDescription("Short chip-style label, max 12 chars. Optional.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("options")
                        .withType(ParameterType.ARRAY)
                        .withDescription(
                            "2–4 choices, each {label, description}. Labels are what the user picks.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("multiSelect")
                        .withType(ParameterType.BOOLEAN)
                        .withDescription("Permit multiple selections. Defaults to false.")
                        .withRequired(false)
                        .build()))
            .withIdempotent(false)
            .withExecutor(args -> execute(gateway, args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.CONTROL)
        .withPermissionKeyExtractor(args -> ToolPermissionKey.of(NAME))
        .build();
  }

  private static ToolResult execute(QuestionGateway gateway, Map<String, Object> args) {
    var question = stringArg(args, "question", "");
    if (question.isBlank()) {
      return ToolResult.failure("AskUserQuestion: missing required 'question'");
    }
    var header = stringArg(args, "header", "");
    var multiSelect = boolArg(args, "multiSelect", false);
    List<AskUserQuestionOption> options;
    try {
      options = parseOptions(args.get("options"));
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("AskUserQuestion: " + e.getMessage());
    }
    AskUserQuestionRequest request;
    try {
      request =
          new AskUserQuestionRequest(
              "q-" + UUID.randomUUID(), header, question, options, multiSelect);
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("AskUserQuestion: " + e.getMessage());
    }
    try {
      var response = gateway.ask(request);
      return ToolResult.success(format(response));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("AskUserQuestion: interrupted while waiting for answer");
    } catch (CancellationException e) {
      return ToolResult.failure("AskUserQuestion: cancelled before answer");
    }
  }

  private static List<AskUserQuestionOption> parseOptions(Object raw) {
    if (raw == null) {
      throw new IllegalArgumentException("missing required 'options'");
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException(
          "'options' must be an array, got " + raw.getClass().getSimpleName());
    }
    var out = new ArrayList<AskUserQuestionOption>(list.size());
    for (var entry : list) {
      if (!(entry instanceof Map<?, ?> m)) {
        throw new IllegalArgumentException(
            "each option must be an object with label + description");
      }
      var labelObj = m.get("label");
      var descObj = m.get("description");
      if (!(labelObj instanceof String label)) {
        throw new IllegalArgumentException("option 'label' is required and must be a string");
      }
      var description = descObj instanceof String s ? s : "";
      out.add(new AskUserQuestionOption(label, description));
    }
    return out;
  }

  private static String format(AskUserQuestionResponse response) {
    var sb = new StringBuilder();
    sb.append("user selected:\n");
    for (var label : response.selectedLabels()) {
      sb.append("- ").append(label).append('\n');
    }
    if (!response.customText().isEmpty()) {
      sb.append("custom text:\n").append(response.customText()).append('\n');
    }
    return sb.toString();
  }

  private static String stringArg(Map<String, Object> args, String name, String defaultValue) {
    var v = args.get(name);
    return v instanceof String s ? s : defaultValue;
  }

  private static boolean boolArg(Map<String, Object> args, String name, boolean defaultValue) {
    var v = args.get(name);
    if (v instanceof Boolean b) {
      return b;
    }
    return defaultValue;
  }
}
