/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.common.Strings;

/**
 * A {@code predict()} call shape the model is required to invoke before the harness allows it to
 * stop. Used by {@link ReplConfig#requiredPredictSignatures()} and consumed by {@link RlmHarness}'s
 * iteration hook.
 *
 * <p>Detection is by exact equality on the {@code instructions} string passed to {@code predict()}
 * — no fuzzy match, no substring. Users typically build a {@code Map<String, String>} of {@code
 * name → instructions} and use the same map values both here and in the model's strategy ("call
 * predict(specialists.get(\"devils_advocate\"), ...)"), so the strings line up verbatim.
 *
 * <p>The corrective injection on miss is shaped from {@link #name()} and {@link #remediation()}: if
 * the harness exits a turn without seeing a matching call, it injects a USER message naming the
 * signature so the model retries and runs it.
 *
 * @param name short identifier for the signature (e.g. {@code "devils_advocate"}); used in the
 *     corrective message
 * @param instructions the exact instructions string the model is expected to pass to {@code
 *     predict()}; matched verbatim
 * @param remediation optional override for the corrective message body. {@code null}/blank uses a
 *     default of "Run the {name} signature now and re-submit." Useful when the model needs more
 *     specific guidance ("call predict(specialists.get(\"X\"), Y) before submitting")
 */
public record RequiredPredictSignature(String name, String instructions, String remediation) {

  public RequiredPredictSignature {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("RequiredPredictSignature name must not be blank");
    }
    if (Strings.isBlank(instructions)) {
      throw new IllegalArgumentException("RequiredPredictSignature instructions must not be blank");
    }
  }

  /** Convenience: signature with default remediation message. */
  public RequiredPredictSignature(String name, String instructions) {
    this(name, instructions, null);
  }
}
