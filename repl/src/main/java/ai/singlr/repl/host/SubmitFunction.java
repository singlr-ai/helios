/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
            : "Submit the final structured result. The 'output' value must match the configured "
                + "schema; mismatches throw an error you will see in your next iteration.";
    return new HostFunction(
        "submit",
        description,
        params -> {
          var output = params.get("output");
          if (output == null) {
            throw new IllegalArgumentException("Parameter 'output' is required");
          }
          if (schema != null) {
            var errors = SchemaValidator.validate(output, schema.schema());
            if (!errors.isEmpty()) {
              throw new IllegalArgumentException(formatValidationError(errors, schema.schema()));
            }
          }
          if (!holder.compareAndSet(null, output)) {
            throw new IllegalStateException("submit() has already been called");
          }
          return Map.of("status", "accepted");
        });
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
}
