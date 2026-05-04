/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

/**
 * Whole-output semantic validator applied at submit time. Runs after JSON Schema validation has
 * confirmed the output is structurally well-formed, so the validator can assume a fully-typed value
 * rather than a raw map.
 *
 * <p>Distinct from {@link ProvenanceValidator}: that one validates each {@link FieldProvenance}
 * entry of a provenanced output (calibration: confidence levels match citation requirements).
 * {@code SubmitValidator} validates the parsed output as a whole, regardless of whether the schema
 * is provenanced. Use it for content checks structural validation cannot express — minimum word
 * counts, forbidden phrasing, every claim having a non-empty source, presence of a required
 * keyword, etc.
 *
 * <p>When this validator returns {@link ValidationResult#failure(String)}, the submit path throws
 * back through JSON-RPC. The model sees the message inline in its next iteration and retries within
 * the existing iteration / LLM-call budget — the same machinery structural validation failures use.
 * Retries respect {@code maxIterations} and {@code maxLlmCalls}, so a model that cannot satisfy the
 * validator eventually surfaces as a {@code FAILED} run status rather than looping forever.
 *
 * <p>Operator-thrown exceptions inside the validator are caught by the submit path and converted to
 * a validation failure with message {@code "submit validator threw: <message>"}, so a buggy
 * predicate doesn't tombstone the agent run.
 *
 * <p>Composition via {@link #andThen(SubmitValidator)} runs validators left-to-right and
 * short-circuits on the first failure — same semantics as {@link
 * ProvenanceValidator#andThen(ProvenanceValidator)}.
 *
 * @param <O> the parsed output type the validator inspects
 */
@FunctionalInterface
public interface SubmitValidator<O> {

  /**
   * Validate the parsed output.
   *
   * @param output the typed output the model submitted; never {@code null}
   * @return success or a failure carrying a model-readable correction message
   */
  ValidationResult validate(O output);

  /**
   * Compose this validator with another. The returned validator runs {@code this} first; if it
   * fails, that failure is returned. Otherwise the {@code next} validator runs.
   *
   * @param next the validator to apply when {@code this} succeeds
   * @return a composed validator
   */
  default SubmitValidator<O> andThen(SubmitValidator<O> next) {
    if (next == null) {
      throw new IllegalArgumentException("next must not be null");
    }
    return output -> {
      var first = validate(output);
      return first.ok() ? next.validate(output) : first;
    };
  }
}
