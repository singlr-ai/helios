/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SignatureMatchersTest {

  @Test
  void exactMatchesIdenticalStrings() {
    assertTrue(SignatureMatchers.EXACT.test("foo", "foo"));
    assertFalse(SignatureMatchers.EXACT.test("foo", "FOO"));
    assertFalse(SignatureMatchers.EXACT.test("foo", "foobar"));
  }

  @Test
  void substringMatchesContainedRegistered() {
    assertTrue(SignatureMatchers.SUBSTRING.test("DA review", "INSTRUCTIONS: DA review carefully"));
    assertTrue(SignatureMatchers.SUBSTRING.test("foo", "foo"));
    assertFalse(SignatureMatchers.SUBSTRING.test("DA review", "performed devil's advocacy"));
  }

  @Test
  void substringRejectsNullActual() {
    assertFalse(SignatureMatchers.SUBSTRING.test("foo", null));
  }

  @Test
  void prefixMatchesLeadingRegistered() {
    assertTrue(SignatureMatchers.PREFIX.test("DA:", "DA: take the opposing view"));
    assertFalse(
        SignatureMatchers.PREFIX.test("DA:", "  DA: take the opposing view"),
        "leading whitespace must NOT match prefix");
  }

  @Test
  void regexMatchesPattern() {
    // Pattern: case-insensitive "devil", non-greedy any chars, "advocate" as a whole word.
    // Matches "Devil's Advocate" and "DEVIL ADVOCATE perspective"; rejects "devil's advocacy"
    // (no word boundary after "advocate" in "advocacy").
    var matcher = SignatureMatchers.regex(Pattern.compile("(?i)devil.*?advocate\\b"));
    assertTrue(matcher.test("ignored", "Be the Devil's Advocate"));
    assertTrue(matcher.test("ignored", "DEVIL ADVOCATE perspective"));
    assertFalse(matcher.test("ignored", "play devil's advocacy"));
  }

  @Test
  void regexRejectsNullPattern() {
    assertThrows(IllegalArgumentException.class, () -> SignatureMatchers.regex(null));
  }

  @Test
  void regexIgnoresRegisteredString() {
    // Per the contract: regex matchers test only against the actual; registered string is
    // along-for-the-ride for error messaging. Confirming here so users don't expect EXACT-style
    // semantics from regex.
    var matcher = SignatureMatchers.regex(Pattern.compile("foo"));
    assertTrue(matcher.test("totally-different-string", "foobar"));
  }
}
