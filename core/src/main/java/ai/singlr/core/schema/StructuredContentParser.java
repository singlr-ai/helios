/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Shared structured-output parser. Three providers (Anthropic, Gemini, OpenAI) used to duplicate
 * this code byte-for-byte; the shared implementation lives here so future fixes (Unicode BOM
 * handling, additional fence shapes, schema-error message tweaks) get applied once.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Trim the content. If null/blank, return null (matches prior provider behaviour).
 *   <li>Parse the JSON to {@code Map<String,Object>} via the caller-supplied {@link JsonAdapter}.
 *   <li>Run {@link SchemaValidator} against the deserialized map. On mismatch throw a {@link
 *       StructuredOutputParseException} carrying the field-level diff.
 *   <li>Type-coerce the map into the schema's output class. For provenanced schemas use {@link
 *       OutputSchema#reconstructProvenanced(Map, java.util.function.Function)}.
 *   <li>On JSON-syntax failure retry once after stripping markdown fences. If that also fails, wrap
 *       via the caller-supplied {@code exceptionFactory} (so each provider keeps its own exception
 *       type at the call boundary).
 * </ol>
 *
 * <p>Core has no Jackson dependency; providers wire their {@code ObjectMapper}-equivalent via
 * {@link JsonAdapter} so this class stays JSON-library-agnostic.
 */
public final class StructuredContentParser {

  private StructuredContentParser() {}

  /**
   * JSON operations the parser needs. Providers wrap their {@code ObjectMapper} (Jackson 3.x in all
   * three current providers) so core stays free of the dependency.
   */
  public interface JsonAdapter {

    /**
     * Parse a JSON object into a string-keyed map. Implementations should propagate
     * library-specific exceptions; {@link StructuredContentParser#parse} catches and falls back to
     * the markdown-strip retry.
     *
     * @throws Exception when the JSON is syntactically invalid
     */
    Map<String, Object> toMap(String json) throws Exception;

    /**
     * Coerce a deserialized map into a typed record/POJO. For Jackson this is {@code
     * objectMapper.convertValue(map, type)}.
     *
     * @throws Exception when coercion fails (e.g., type mismatch on a record component)
     */
    <T> T fromMap(Map<String, Object> map, Class<T> type) throws Exception;
  }

  /**
   * Parse {@code content} against {@code schema} using the supplied {@code adapter}. The {@code
   * exceptionFactory} produces the provider-specific exception thrown on JSON-syntax or
   * type-coercion failure; schema mismatches always surface as {@link
   * StructuredOutputParseException} regardless of the factory.
   *
   * @param <T> the typed output
   * @param content the raw model response — may be {@code null}/blank to indicate "no structured
   *     output produced"
   * @param schema the output schema describing the expected shape
   * @param adapter provider-supplied JSON adapter
   * @param exceptionFactory builds the provider's wrapping exception; receives {@code (message,
   *     cause)} and must return a {@link RuntimeException}
   * @return the typed output, or {@code null} when {@code content} was null/blank
   */
  public static <T> T parse(
      String content,
      OutputSchema<T> schema,
      JsonAdapter adapter,
      BiFunction<String, Exception, RuntimeException> exceptionFactory) {
    if (content == null || content.isBlank()) {
      return null;
    }
    var trimmed = content.trim();
    try {
      return parseToType(trimmed, schema, adapter);
    } catch (StructuredOutputParseException schemaMismatch) {
      throw schemaMismatch;
    } catch (Exception firstAttempt) {
      var stripped = stripMarkdownWrapper(trimmed);
      if (stripped.equals(trimmed)) {
        throw exceptionFactory.apply("Failed to parse structured output: " + content, firstAttempt);
      }
      try {
        return parseToType(stripped, schema, adapter);
      } catch (StructuredOutputParseException schemaMismatch) {
        throw schemaMismatch;
      } catch (Exception e) {
        throw exceptionFactory.apply("Failed to parse structured output: " + content, e);
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <T> T parseToType(String json, OutputSchema<T> schema, JsonAdapter adapter)
      throws Exception {
    Map<String, Object> raw = adapter.toMap(json);
    var errors = SchemaValidator.validate(raw, schema.schema());
    if (!errors.isEmpty()) {
      throw new StructuredOutputParseException(errors, json);
    }
    if (schema.innerOutputType() == null) {
      return adapter.fromMap(raw, schema.type());
    }
    return (T)
        OutputSchema.reconstructProvenanced(
            raw,
            m -> {
              try {
                return adapter.fromMap((Map<String, Object>) m, schema.innerOutputType());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }

  /**
   * Strip one layer of {@code ```} / {@code ```json} markdown fences. Returns the input unchanged
   * when no fences are present, so callers can compare {@code stripped.equals(input)} to detect a
   * no-op.
   */
  public static String stripMarkdownWrapper(String json) {
    var result = json;
    if (result.startsWith("```json")) {
      result = result.substring(7);
    } else if (result.startsWith("```")) {
      result = result.substring(3);
    }
    if (result.endsWith("```")) {
      result = result.substring(0, result.length() - 3);
    }
    return result.trim();
  }
}
