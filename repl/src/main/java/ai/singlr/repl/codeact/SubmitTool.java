/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.common.Provenanced;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.common.ValidationResult;
import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.SchemaValidator;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Factory for the {@code Submit} tool: the model finalizes a CodeAct or RLM-style session by
 * invoking this tool with an {@code output} argument matching the session's {@link OutputSchema}.
 * On accept, the parsed typed value is stashed in a {@link SubmittedValueHolder} so the loop
 * integration can carry it through as the session's terminal value.
 *
 * <p>Validation pipeline mirrors the v1 {@code SubmitFunction} contract so existing RLM call sites
 * port cleanly:
 *
 * <ol>
 *   <li>JSON Schema validation via {@link SchemaValidator#validate(Object, JsonSchema)}.
 *   <li>For provenanced schemas: reconstruct {@link Provenanced} using the schema's {@code
 *       innerOutputType}, verify structural correspondence (every output field has exactly one
 *       provenance entry, no orphans, no duplicates), and apply the schema's {@link
 *       ai.singlr.core.common.ProvenanceValidator}.
 *   <li>For schemas carrying a {@link SubmitValidator}: convert to the typed value and run the
 *       validator; operator-thrown exceptions are caught and surfaced as a validation failure so a
 *       buggy predicate cannot tombstone the run.
 *   <li>{@link SubmittedValueHolder#submit(Object)} writes the parsed value; a {@code false} return
 *       (someone already submitted) is surfaced as a tool-result failure.
 * </ol>
 *
 * <p>Validation failures land as {@link ToolResult#failure} with a message detailing the per-field
 * issues. The model reads the failure on its next turn and self-corrects within the existing
 * iteration budget. Successful submits return a small {@code "accepted"} text the model can echo if
 * it wants to.
 *
 * <p>Loop termination — refusing further turns once a submit has succeeded — is the responsibility
 * of the wiring layer (a {@code PostToolUseHook} returning {@link
 * ai.singlr.session.hooks.HookOutcome.Stop Stop} after a successful submit, plus a {@link
 * ai.singlr.session.hooks.PreStopHook PreStopHook} that requires submit before allowing stop). This
 * factory only owns the validate-and-store half.
 */
public final class SubmitTool {

  /** Stable tool name; matched by hooks and audit. */
  public static final String NAME = "Submit";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private SubmitTool() {}

  /**
   * Build a {@link Tool} that validates the {@code output} argument against {@code schema} and
   * stores the parsed value in {@code holder}.
   *
   * @param schema the session's typed output schema; non-null
   * @param holder the holder to write to on successful submit; non-null
   * @return the tool
   * @throws NullPointerException if either argument is null
   */
  public static Tool create(OutputSchema<?> schema, SubmittedValueHolder holder) {
    Objects.requireNonNull(schema, "schema must not be null");
    Objects.requireNonNull(holder, "holder must not be null");
    return Tool.newBuilder()
        .withName(NAME)
        .withDescription(describe(schema))
        .withParameter(
            ToolParameter.newBuilder()
                .withName("output")
                .withType(ParameterType.OBJECT)
                .withDescription("The structured final result to submit, matching the schema.")
                .withRequired(true)
                .build())
        .withExecutor((args, ctx) -> dispatch(args, schema, holder))
        .build();
  }

  /**
   * Build a {@link ToolBinding} that pairs the {@code Submit} tool with the {@link
   * ToolCategory#CONTROL} category — the spec's classification for finalization tools, which the
   * permission system treats as default-allow (no user prompt).
   *
   * @param schema the typed output schema; non-null
   * @param holder the submission holder; non-null
   * @return a ready-to-register binding
   * @throws NullPointerException if either argument is null
   */
  public static ToolBinding binding(OutputSchema<?> schema, SubmittedValueHolder holder) {
    return ToolBinding.newBuilder(create(schema, holder))
        .withCategory(ToolCategory.CONTROL)
        .build();
  }

  private static String describe(OutputSchema<?> schema) {
    if (schema.provenanceValidator() != null) {
      return "Submit the final structured result with provenance. 'output' must be {output: ...,"
          + " provenance: [...]} where each provenance entry has field, sources, reasoning,"
          + " confidence (LOW|MEDIUM|HIGH). MEDIUM/HIGH require at least one source.";
    }
    return "Submit the final structured result. The 'output' value must match the configured"
        + " schema; mismatches return a correction inline so you can retry.";
  }

  private static ToolResult dispatch(
      Map<String, Object> args, OutputSchema<?> schema, SubmittedValueHolder holder) {
    var output = args.get("output");
    if (output == null) {
      return ToolResult.failure("Submit: parameter 'output' is required");
    }
    var errors = SchemaValidator.validate(output, schema.schema());
    if (!errors.isEmpty()) {
      return ToolResult.failure(formatSchemaError(errors, schema.schema()));
    }
    Object toStore = output;
    if (schema.provenanceValidator() != null) {
      try {
        toStore = validateAndReconstruct(output, schema);
      } catch (IllegalArgumentException e) {
        return ToolResult.failure(e.getMessage());
      }
    }
    if (schema.submitValidator() != null) {
      try {
        toStore = applySubmitValidator(toStore, schema);
      } catch (IllegalArgumentException e) {
        return ToolResult.failure(e.getMessage());
      }
    }
    if (!holder.submit(toStore)) {
      return ToolResult.failure(
          "Submit: a prior submit already accepted a result; further submissions are ignored.");
    }
    return ToolResult.success("accepted");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object applySubmitValidator(Object current, OutputSchema<?> schema) {
    Object parsed;
    if (current instanceof Provenanced<?>) {
      parsed = current;
    } else {
      try {
        parsed = MAPPER.convertValue(current, schema.type());
      } catch (RuntimeException convertEx) {
        throw new IllegalArgumentException(
            "Submit validation failed:\n  - could not parse output as "
                + schema.type().getSimpleName()
                + ": "
                + convertEx.getMessage()
                + "\nFix the output value and call Submit again.",
            convertEx);
      }
    }
    SubmitValidator validator = schema.submitValidator();
    ValidationResult result;
    try {
      result = validator.validate(parsed);
    } catch (RuntimeException validatorEx) {
      result = ValidationResult.failure("submit validator threw: " + validatorEx.getMessage());
    }
    if (result == null) {
      throw new IllegalArgumentException(
          "Submit validation failed:\n  - validator returned null"
              + "\nFix the output value and call Submit again.");
    }
    if (!result.ok()) {
      throw new IllegalArgumentException(
          "Submit validation failed:\n  - "
              + result.message()
              + "\nFix the output value and call Submit again.");
    }
    return parsed;
  }

  @SuppressWarnings("unchecked")
  private static Provenanced<?> validateAndReconstruct(Object output, OutputSchema<?> schema) {
    if (!(output instanceof Map<?, ?> envelope)) {
      throw new IllegalArgumentException(
          "Submit: provenanced submit expects an object envelope {output, provenance}");
    }
    var raw = (Map<String, Object>) envelope;
    var rawOutputObj = raw.get("output");
    if (!(rawOutputObj instanceof Map<?, ?> outputMap)) {
      throw new IllegalArgumentException(
          "Submit: provenanced submit: 'output' must be an object describing the typed result");
    }
    var declaredFields = new LinkedHashSet<String>();
    for (var k : outputMap.keySet()) {
      declaredFields.add(String.valueOf(k));
    }
    var reconstructed =
        OutputSchema.reconstructProvenanced(
            raw, value -> MAPPER.convertValue(value, schema.innerOutputType()));
    var errors = new ArrayList<String>();
    var seen = new LinkedHashSet<String>();
    for (var entry : reconstructed.provenance()) {
      if (!declaredFields.contains(entry.field())) {
        errors.add(
            "provenance entry references unknown field '"
                + entry.field()
                + "' (output has: "
                + declaredFields
                + ")");
        continue;
      }
      if (!seen.add(entry.field())) {
        errors.add("duplicate provenance entry for field '" + entry.field() + "'");
        continue;
      }
      var result = schema.provenanceValidator().validate(entry);
      if (!result.ok()) {
        errors.add(result.message());
      }
    }
    var missing = new LinkedHashSet<>(declaredFields);
    missing.removeAll(seen);
    for (var m : missing) {
      errors.add("missing provenance entry for output field '" + m + "'");
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(formatProvenanceError(errors));
    }
    return reconstructed;
  }

  private static String formatSchemaError(List<String> errors, JsonSchema schema) {
    var sb = new StringBuilder("Submit validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    if (schema != null && schema.required() != null && !schema.required().isEmpty()) {
      sb.append("\nRequired fields: ").append(schema.required());
    }
    sb.append("\nFix the output value and call Submit again.");
    return sb.toString();
  }

  private static String formatProvenanceError(List<String> errors) {
    var sb = new StringBuilder("Provenance validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    sb.append("\nFix the provenance and call Submit again.");
    return sb.toString();
  }
}
