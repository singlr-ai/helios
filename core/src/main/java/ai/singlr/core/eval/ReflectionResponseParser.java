/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import ai.singlr.core.common.Strings;
import java.util.regex.Pattern;

/**
 * Post-processes a reflection LM's free-text response to extract just the revised prompt. LMs
 * frequently wrap their answer in preamble ("Here is the revised prompt:") or code fences ("```") —
 * strip those deterministically so the caller can decide whether a schema-constrained retry is
 * warranted.
 *
 * <p>All methods are pure functions. No model calls.
 */
final class ReflectionResponseParser {

  private static final Pattern FENCE_OPEN =
      Pattern.compile("^\\s*```[a-zA-Z]*\\s*\n", Pattern.MULTILINE);
  private static final Pattern FENCE_CLOSE = Pattern.compile("\n\\s*```\\s*$");

  // Common leading boilerplate the model tends to emit before the actual prompt.
  private static final Pattern PREAMBLE =
      Pattern.compile(
          "^\\s*(here(?:\\s+is|'s)\\s+(?:the\\s+)?(?:revised|improved|updated)\\s+prompt\\s*[:\\-]?\\s*\n+|"
              + "(?:revised|improved|updated)\\s+prompt\\s*[:\\-]\\s*\n+)",
          Pattern.CASE_INSENSITIVE);

  private ReflectionResponseParser() {}

  /**
   * Strip preamble, code fences, and surrounding whitespace from the LM's raw response.
   *
   * @param raw the raw response text; {@code null} is treated as blank
   * @return the cleaned text; may be blank if nothing meaningful remained
   */
  static String cleanFreeText(String raw) {
    if (Strings.isBlank(raw)) {
      return "";
    }
    var s = raw.strip();
    s = PREAMBLE.matcher(s).replaceFirst("");
    var openMatcher = FENCE_OPEN.matcher(s);
    if (openMatcher.find() && openMatcher.start() == 0) {
      s = openMatcher.replaceFirst("");
      s = FENCE_CLOSE.matcher(s).replaceFirst("");
    }
    return s.strip();
  }

  /**
   * Decide whether the cleaned response is usable. A response is rejected when it's blank or
   * shorter than {@code minLengthFraction} of the parent — extreme shrinkage is almost always the
   * model refusing or echoing nothing.
   *
   * @param cleaned the post-processed candidate response
   * @param parent the parent prompt, for size comparison
   * @param minLengthFraction lower bound on cleaned-length / parent-length; e.g. 0.25
   */
  static boolean isAcceptable(String cleaned, String parent, double minLengthFraction) {
    if (Strings.isBlank(cleaned)) {
      return false;
    }
    if (Strings.isBlank(parent)) {
      // No parent to compare against — non-blank is good enough.
      return true;
    }
    if (minLengthFraction <= 0) {
      return true;
    }
    var ratio = (double) cleaned.length() / parent.length();
    return ratio >= minLengthFraction;
  }
}
