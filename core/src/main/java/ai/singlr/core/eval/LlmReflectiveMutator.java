/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import java.util.List;

/**
 * Reference {@link ReflectiveMutator} implementation for {@code C = String} prompts.
 *
 * <p>Calls a reflection {@link Model} with an assembled prompt that describes the parent candidate
 * and a sample of evaluation traces, then post-processes the response. On post-processing failure
 * (blank result, extreme shrinkage), runs a single schema-constrained retry that forces the model
 * to return JSON matching {@link RevisedPrompt}. If both attempts fail, throws {@link
 * ReflectionFailedException} so the optimizer driver can decide between skip and abort.
 *
 * <p>Decomposed into three single-responsibility units to keep this class small: {@link
 * ReflectionPromptTemplate} assembles the prompt string, {@link ReflectionResponseParser} cleans
 * the response, and {@link TraceSampler} chooses which traces the reflection LM sees. Swap any of
 * them for a different optimization shape.
 */
public final class LlmReflectiveMutator implements ReflectiveMutator<String> {

  /** Schema-constrained retry shape — single JSON field carrying the revised prompt. */
  public record RevisedPrompt(String prompt) {}

  /** Default upper bound on the rendered-traces section, in characters. */
  public static final int DEFAULT_MAX_FEEDBACK_CHARS = 8000;

  /** Default lower bound on cleaned-length / parent-length before a retry is forced. */
  public static final double DEFAULT_MIN_LENGTH_FRACTION = 0.25;

  private final Model model;
  private final String reflectionInstructions;
  private final int maxFeedbackChars;
  private final double minLengthFraction;
  private final TraceSampler traceSampler;

  private LlmReflectiveMutator(Builder b) {
    this.model = b.model;
    this.reflectionInstructions = b.reflectionInstructions;
    this.maxFeedbackChars = b.maxFeedbackChars;
    this.minLengthFraction = b.minLengthFraction;
    this.traceSampler = b.traceSampler;
  }

  public static Builder builder(Model reflectionLm) {
    if (reflectionLm == null) {
      throw new IllegalArgumentException("reflectionLm must not be null");
    }
    return new Builder(reflectionLm);
  }

  @Override
  public String propose(String parentPrompt, List<TraceFeedback> traces) {
    if (parentPrompt == null) {
      throw new IllegalArgumentException("parentPrompt must not be null");
    }
    var visibleTraces =
        traceSampler == null
            ? (traces == null ? List.<TraceFeedback>of() : traces)
            : traceSampler.sample(traces == null ? List.of() : traces);
    var prompt =
        ReflectionPromptTemplate.build(
            reflectionInstructions, parentPrompt, visibleTraces, maxFeedbackChars);

    var firstResponse = model.chat(List.of(Message.user(prompt)));
    var cleaned = ReflectionResponseParser.cleanFreeText(firstResponse.content());
    if (ReflectionResponseParser.isAcceptable(cleaned, parentPrompt, minLengthFraction)) {
      return cleaned;
    }

    // Schema-constrained retry: force JSON, side-step prose-leakage.
    var retrySchema = OutputSchema.of(RevisedPrompt.class);
    try {
      var typedResponse = model.chat(List.of(Message.user(prompt)), retrySchema);
      var parsed = typedResponse.parsed();
      if (parsed != null && !Strings.isBlank(parsed.prompt())) {
        return parsed.prompt().strip();
      }
      throw new ReflectionFailedException(
          "Reflection retry produced no usable RevisedPrompt; first-attempt cleaned length="
              + cleaned.length());
    } catch (ReflectionFailedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ReflectionFailedException(
          "Reflection retry failed: "
              + e.getMessage()
              + "; first-attempt cleaned length="
              + cleaned.length(),
          e);
    }
  }

  public static final class Builder {
    private final Model model;
    private String reflectionInstructions;
    private int maxFeedbackChars = DEFAULT_MAX_FEEDBACK_CHARS;
    private double minLengthFraction = DEFAULT_MIN_LENGTH_FRACTION;
    private TraceSampler traceSampler;

    private Builder(Model model) {
      this.model = model;
    }

    /** Override the default reflection-instruction header. Pass {@code null} to use the default. */
    public Builder reflectionInstructions(String instructions) {
      this.reflectionInstructions = instructions;
      return this;
    }

    /**
     * Total budget across the rendered-traces section, in characters. Tune to control reflection LM
     * cost — long traces are dropped from the tail of the sample once the budget is exhausted.
     * {@code 0} disables truncation (rendering whatever traces the sampler returns).
     */
    public Builder maxFeedbackChars(int n) {
      if (n < 0) {
        throw new IllegalArgumentException("maxFeedbackChars must be >= 0");
      }
      this.maxFeedbackChars = n;
      return this;
    }

    /**
     * Lower bound on (cleaned response length) / (parent prompt length). Responses shorter than
     * this trigger the schema-constrained retry. Set to {@code 0} to accept any non-blank cleaned
     * response.
     */
    public Builder minLengthFraction(double f) {
      if (f < 0 || f > 1) {
        throw new IllegalArgumentException("minLengthFraction must be in [0, 1]");
      }
      this.minLengthFraction = f;
      return this;
    }

    /** Override the trace sampler. {@code null} disables sampling (all traces are shown). */
    public Builder traceSampler(TraceSampler sampler) {
      this.traceSampler = sampler;
      return this;
    }

    public LlmReflectiveMutator build() {
      return new LlmReflectiveMutator(this);
    }
  }
}
