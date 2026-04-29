/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * JPMS-mode reproducer for the helios-repl harness. Required so this module is built and tested in
 * JPMS mode (parent JVM uses --module-path), forcing the sandbox subprocess into JPMS mode too —
 * which is where the InputBindings classpath / module-graph mismatch surfaces.
 */
module ai.singlr.examples.rlm.jpms {
  requires ai.singlr.core;
  requires ai.singlr.repl;
  requires ai.singlr.gemini;
  requires tools.jackson.databind;

  // Open to Jackson so it can reflect into our record fields. JPMS consumers must do this
  // (or use --add-opens) for any record type they serialize through the harness.
  opens ai.singlr.examples.rlm.jpms;
}
