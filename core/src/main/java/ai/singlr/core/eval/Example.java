/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.Map;

/**
 * A labeled input/output pair used by {@link Evaluator} to score agent runs.
 *
 * @param input the input to feed to the agent
 * @param expected the reference output used to score the agent's response
 * @param metadata free-form tags (id, source, difficulty, etc.)
 * @param <I> input type
 * @param <O> expected output type
 */
public record Example<I, O>(I input, O expected, Map<String, Object> metadata) {

  public Example {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /**
   * Build an example with no metadata.
   *
   * @param input input value
   * @param expected expected output
   * @param <I> input type
   * @param <O> output type
   * @return a new example
   */
  public static <I, O> Example<I, O> of(I input, O expected) {
    return new Example<>(input, expected, Map.of());
  }

  /**
   * Build an example with metadata.
   *
   * @param input input value
   * @param expected expected output
   * @param metadata tags to attach
   * @param <I> input type
   * @param <O> output type
   * @return a new example
   */
  public static <I, O> Example<I, O> of(I input, O expected, Map<String, Object> metadata) {
    return new Example<>(input, expected, metadata);
  }
}
