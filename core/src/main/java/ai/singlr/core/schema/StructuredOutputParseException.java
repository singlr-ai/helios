/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.List;
import java.util.Objects;

/**
 * Thrown by providers when a model's structured-output response is syntactically valid JSON but
 * does not conform to the configured {@link OutputSchema}. Carries a field-level diff produced by
 * {@link SchemaValidator}, so the agent loop can surface specific corrections to the model rather
 * than re-rolling the dice against an opaque "parse failed" message.
 *
 * <p>Distinguished from generic provider exceptions ({@code AnthropicException}, {@code
 * GeminiException}, {@code OpenAIException}) so {@link ai.singlr.core.agent.Agent} can
 * pattern-match on it: when this exception type bubbles out of a structured-output {@code
 * chat(...)} call, the agent injects {@link #correctionMessage()} as a USER turn and continues the
 * iteration loop instead of failing terminally. Other parse failures (malformed JSON, Jackson
 * type-coercion errors) still terminate the run as before.
 *
 * <p>The {@link #rawContent()} field preserves the model's original output for log-side debugging;
 * it is intentionally <em>not</em> echoed back to the model on retry, since the model has the same
 * content in its own conversation context and re-attaching it would balloon input cost on every
 * retry. The model sees only {@link #correctionMessage()} — the diff, not the haystack.
 */
public class StructuredOutputParseException extends RuntimeException {

  private final List<String> errors;
  private final String rawContent;

  /**
   * @param errors per-field validation messages produced by {@link SchemaValidator}; never empty
   * @param rawContent the model's raw response text (preserved for log-side debugging); may be
   *     {@code null} when the caller doesn't have it available
   */
  public StructuredOutputParseException(List<String> errors, String rawContent) {
    super(buildMessage(errors));
    Objects.requireNonNull(errors, "errors must not be null");
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("errors must not be empty");
    }
    this.errors = List.copyOf(errors);
    this.rawContent = rawContent;
  }

  /** Per-field validation messages, in the order {@link SchemaValidator} produced them. */
  public List<String> errors() {
    return errors;
  }

  /** The model's raw response text, preserved for log-side debugging. May be {@code null}. */
  public String rawContent() {
    return rawContent;
  }

  /**
   * The model-facing correction string the agent loop injects as the next USER message. Names every
   * failing field with its expected/actual mismatch and instructs the model to re-emit the
   * structured output. Does <em>not</em> include the raw content — see the class-level note for
   * why.
   */
  public String correctionMessage() {
    var sb =
        new StringBuilder(
            "Your structured output did not match the schema. Fix the listed fields and re-emit "
                + "the structured output:\n");
    for (var error : errors) {
      sb.append("  - ").append(error).append('\n');
    }
    return sb.toString();
  }

  private static String buildMessage(List<String> errors) {
    if (errors == null || errors.isEmpty()) {
      return "Structured output schema validation failed";
    }
    var sb = new StringBuilder("Structured output schema validation failed:");
    for (var error : errors) {
      sb.append("\n  - ").append(error);
    }
    return sb.toString();
  }
}
