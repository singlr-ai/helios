/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.common.Provenanced;
import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Factory for the {@code submit} host function. Captures the final output from sandbox code,
 * signaling that the task is complete.
 *
 * <p>When an {@link OutputSchema} is supplied, the host validates the submitted value against the
 * schema before committing it. Validation failures throw an exception back through the JSON-RPC
 * bridge so the model sees the error inline in its next {@code execute_code} tool result and can
 * self-correct without losing the rest of the trajectory's variables.
 */
public final class SubmitFunction {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private SubmitFunction() {}

  /**
   * Create an untyped submit host function that stores the submitted value as-is.
   *
   * @param holder atomic reference to store the submitted value
   * @return a host function that sandbox code can call as {@code submit(output)}
   */
  public static HostFunction create(AtomicReference<Object> holder) {
    return create(holder, null);
  }

  /**
   * Create a submit host function that validates the submitted value against the given schema
   * before storing it. When {@code schema} is {@code null} this behaves identically to {@link
   * #create(AtomicReference)}.
   *
   * <p>For provenanced schemas (built via {@link OutputSchema#provenancedOf(Class)}), this function
   * additionally:
   *
   * <ul>
   *   <li>Reconstructs a {@link Provenanced} value from the submitted {@code {output, provenance}}
   *       envelope using the schema's {@code innerOutputType}.
   *   <li>Verifies structural correspondence: every top-level field of {@code output} has exactly
   *       one provenance entry, no entries reference unknown fields.
   *   <li>Applies the schema's {@link ai.singlr.core.common.ProvenanceValidator} to each entry,
   *       collecting all failures into a single error message so the model can fix them in one
   *       retry.
   * </ul>
   *
   * <p>Validation errors are surfaced as a thrown {@link IllegalArgumentException}; the JSON-RPC
   * bridge converts that into a sandbox-side traceback the model reads on the next iteration. The
   * holder remains unset so the agent loop continues.
   *
   * @param holder atomic reference to store the submitted value when validation passes
   * @param schema optional output schema; when present, the submitted value must conform
   * @return a host function that sandbox code can call as {@code submit(output)}
   */
  public static HostFunction create(AtomicReference<Object> holder, OutputSchema<?> schema) {
    if (holder == null) {
      throw new IllegalArgumentException("Holder must not be null");
    }
    var description =
        schema == null
            ? "Submit the final result. Parameters: output (any). Can only be called once."
            : schema.provenanceValidator() == null
                ? "Submit the final structured result. The 'output' value must match the configured"
                    + " schema; mismatches throw an error you will see in your next iteration."
                : "Submit the final structured result with provenance. 'output' must be {output:"
                    + " ..., provenance: [...]} where each provenance entry has field, sources,"
                    + " reasoning, confidence (LOW|MEDIUM|HIGH). MEDIUM/HIGH require >=1 source.";
    return new HostFunction(
        "submit",
        description,
        List.of(
            HostParameter.required(
                "output", ParameterType.OBJECT, "The structured final result to submit")),
        params -> {
          var output = params.get("output");
          if (output == null) {
            throw new IllegalArgumentException("Parameter 'output' is required");
          }
          var toStore = output;
          if (schema != null) {
            var errors = SchemaValidator.validate(output, schema.schema());
            if (!errors.isEmpty()) {
              throw new IllegalArgumentException(formatValidationError(errors, schema.schema()));
            }
            if (schema.provenanceValidator() != null) {
              toStore = validateAndReconstruct(output, schema);
            }
          }
          if (!holder.compareAndSet(null, toStore)) {
            throw new IllegalStateException("submit() has already been called");
          }
          return Map.of("status", "accepted");
        });
  }

  @SuppressWarnings("unchecked")
  private static Provenanced<?> validateAndReconstruct(Object output, OutputSchema<?> schema) {
    if (!(output instanceof Map<?, ?> envelope)) {
      throw new IllegalArgumentException(
          "provenanced submit expects an object envelope {output, provenance}");
    }
    var raw = (Map<String, Object>) envelope;
    var rawOutputObj = raw.get("output");
    if (!(rawOutputObj instanceof Map<?, ?> outputMap)) {
      throw new IllegalArgumentException(
          "provenanced submit: 'output' must be an object describing the typed result");
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

  private static String formatValidationError(List<String> errors, JsonSchema schema) {
    var sb = new StringBuilder("Submit validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    if (schema != null && schema.required() != null && !schema.required().isEmpty()) {
      sb.append("\nRequired fields: ").append(schema.required());
    }
    sb.append("\nFix the output value and call submit(...) again.");
    return sb.toString();
  }

  private static String formatProvenanceError(List<String> errors) {
    var sb = new StringBuilder("Provenance validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    sb.append("\nFix the provenance and call submit(...) again.");
    return sb.toString();
  }
}
