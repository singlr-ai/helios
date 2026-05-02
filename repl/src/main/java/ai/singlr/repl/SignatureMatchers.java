/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Common matchers for {@link RlmHarness.Builder#signatureMatcher} / {@link
 * ReplConfig.Builder#withSignatureMatcher}. Each matcher is invoked as {@code matcher.test(
 * registeredInstructions, actualInstructions)} where {@code registered} is the text of a {@link
 * RequiredPredictSignature} and {@code actual} is the {@code instructions} arg the model passed to
 * {@code predict()}.
 *
 * <p>{@link #EXACT} is the framework default; the remaining matchers are escape hatches for
 * paraphrasing models. {@link #SUBSTRING} is the most common production choice — a model that
 * prefixes "INSTRUCTIONS:" or appends a stylistic clause but otherwise carries the registered text
 * still counts as having invoked the signature.
 */
public final class SignatureMatchers {

  private SignatureMatchers() {}

  /**
   * Strict equality. The model must pass the registered instructions string verbatim. The framework
   * default — used when no matcher is configured.
   */
  public static final BiPredicate<String, String> EXACT = String::equals;

  /**
   * Substring containment: the actual instructions string must contain the registered string. Works
   * well when the registered text is a stable core phrase the model wraps with prose like {@code
   * "INSTRUCTIONS: ..."} or {@code "Please act as: ..."} where the registered text appears
   * verbatim somewhere inside the actual.
   */
  public static final BiPredicate<String, String> SUBSTRING =
      (registered, actual) -> actual != null && registered != null && actual.contains(registered);

  /**
   * Prefix match: the actual instructions string must start with the registered string. Slightly
   * stricter than {@link #SUBSTRING} — appropriate when registered text is intended to be the
   * leading directive the model preserves.
   */
  public static final BiPredicate<String, String> PREFIX =
      (registered, actual) -> actual != null && registered != null && actual.startsWith(registered);

  /**
   * Regex match: returns true when the compiled pattern's {@code find()} matches the actual
   * instructions string. The pattern is the matcher; the registered string is ignored at match time
   * but still travels with the {@link RequiredPredictSignature} for error messaging. Used when a
   * signature can paraphrase across multiple lexical forms.
   *
   * @param pattern compiled regex; not modified by this method
   * @return a matcher that ignores the registered string and tests the actual instructions against
   *     the pattern
   */
  public static BiPredicate<String, String> regex(Pattern pattern) {
    if (pattern == null) {
      throw new IllegalArgumentException("pattern must not be null");
    }
    return (registered, actual) -> actual != null && pattern.matcher(actual).find();
  }
}
