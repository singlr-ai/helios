# `examples/gepa-prompt` — Reference GEPA-Shaped Prompt Optimizer

A worked composition of the `ai.singlr.core.eval` primitives shipped in Helios 1.6 (Spec 03):

- `ParetoFrontier<C>` — keeps every candidate that wins on at least one validation instance.
- `LlmReflectiveMutator` — proposes a revised prompt from a parent + feedback traces.
- `FeedbackMetric<E, A>` — returns both a numeric score and model-readable feedback.
- `Evaluator<I, O>` — batch-evaluates candidates over a labelled dataset.
- `InMemoryExperimentLog` / `FileExperimentLog` — append-only durability.

`GepaPromptOptimizer<I, O>` is ~450 lines of glue. Users who want a different sampling strategy, stopping criterion, or multi-predictor pipeline copy this file and adapt — the framework ships the primitives; this is the canonical composition.

## When to reach for this

Reach for GEPA-shaped optimization when:

- You have a system prompt that's important enough to optimize and a labelled validation set to score it against.
- Your metric can return *feedback* (a short string explaining the score), not just a number.
- You want to keep complementary candidates that win on different validation instances, not just the global average winner.

If you just want "iteratively improve a single prompt against an objective and keep the best one", [`examples/autoresearch-prompt`](../autoresearch-prompt) is simpler. GEPA pays off when the validation set has enough diversity that no single prompt dominates.

## The worked task

The integration test optimizes a sentiment classifier. The seed prompt is intentionally weak (`"Classify the sentence."` — no class hints), the training set has 9 examples across three classes, and the validation set has 6. After `AutoBudget.LIGHT` (six iterations) the optimizer produces a prompt that classifies the held-out validation set with ≥ 70% accuracy.

Run it with `GEMINI_API_KEY` exported:

```sh
mvn -pl examples/gepa-prompt test \
    -Dtest=GepaPromptOptimizerIntegrationTest --batch-mode
```

## Quick start

```java
var student = AgentConfig.newBuilder()
    .withName("my-classifier")
    .withModel(geminiFlash)
    .withSystemPrompt("Classify the sentence.")      // the prompt we'll optimize
    .build();

var metric = (FeedbackMetric<String, String>) (expected, actual, trace) ->
    actual != null && actual.toLowerCase().contains(expected)
        ? FeedbackMetric.Result.of(1.0, "correct")
        : FeedbackMetric.Result.of(0.0, "expected '" + expected + "', got: " + actual);

var optimizer = GepaPromptOptimizer.<String, String>builder()
    .studentConfig(student)
    .trainSet(trainExamples)                          // List<Example<String, String>>
    .valSet(valExamples)
    .metric(metric)
    .reflectionLm(reflectionModel)                    // can be the same model as the student
    .experimentLog(new InMemoryExperimentLog())       // required
    .inputMapper(SessionContext::of)                  // I -> SessionContext
    .budget(AutoBudget.LIGHT)                         // or .maxIterations(int)
    .seed(42L)                                        // reproducible RNG
    .build();

GepaResult result = optimizer.optimize();
System.out.println("Best prompt: " + result.bestPrompt());
System.out.println("Best aggregate: " + result.bestAggregateScore());

// Ship the optimized prompt as a config update:
var optimizedAgent = new Agent(result.applyTo(student));
```

## Budget dials

| Budget   | Iterations | Roughly         |
| -------- | ---------- | --------------- |
| `LIGHT`  | 6          | smoke test      |
| `MEDIUM` | 12         | default         |
| `HEAVY`  | 24         | overnight runs  |

Each iteration costs: 1 minibatch eval + 1 validation eval + 1 reflection LM call. With `minibatchSize=3` and a `valSet` of 30 examples that's ~33 model calls per iteration, plus the seed validation pass at the start.

For finer control, pass `.maxIterations(int)` instead of a budget — the optimizer drops the metric-call cap when iterations are set explicitly.

## What the optimizer does, one iteration at a time

1. **Seed.** Score the student's current system prompt on the validation set, add the per-instance scores to the Pareto frontier, log the seed entry.
2. **Sample a parent** from the frontier weighted by coverage (instances it uniquely wins on). Coverage-weighted sampling biases toward complementary diversity, not redundant strength.
3. **Run the parent on a training minibatch** with the `FeedbackMetric`. Collect feedback traces.
4. **Reflect.** `LlmReflectiveMutator.propose(parent, traces)` produces a revised prompt. Schema-constrained retry kicks in if the response is malformed.
5. **Score the child on the validation set** and add to the frontier.
6. **Log the iteration** to `ExperimentLog`. Emit `OptimizerCandidateProposed` / `OptimizerCandidateScored` events through the configured `EventSink`.

The optimizer loop exits when either `maxIterations` is reached or the metric-call cap is hit (whichever comes first).

## Live observability

Wire an `EventSink` to render the optimizer in a live UI:

```java
.eventSink(event -> {
  switch (event) {
    case HeliosEvent.Custom c when "optimizer.started".equals(c.kind()) ->
        ui.log("Starting: " + c.data().get("maxIterations") + " iterations");
    case HeliosEvent.OptimizerCandidateProposed p ->
        ui.candidateProposed(p.candidateId(), p.parentCandidateId(), p.source());
    case HeliosEvent.OptimizerCandidateScored s ->
        ui.candidateScored(s.candidateId(), s.aggregateScore(), s.perInstanceScores());
    case HeliosEvent.Custom c when "optimizer.completed".equals(c.kind()) ->
        ui.log("Done. Best aggregate: " + c.data().get("bestAggregate"));
    default -> {}
  }
});
```

`HeliosEvent.Custom` carries the lifecycle markers (`optimizer.started` / `optimizer.completed`); the typed `OptimizerCandidate*` events carry the per-candidate signal.

## Tuning notes

- **`minibatchSize`**: 3-5 is the sweet spot. Too small and the reflection LM doesn't have enough signal; too large and reflection cost dominates.
- **`parallelism`**: bound to your provider's rate-limit headroom. The internal `Evaluator` runs examples on virtual threads.
- **`traceSampler`**: passes through to the underlying `LlmReflectiveMutator`. The default keeps every failure plus a sample of successes, which is the right shape for most prompts.
- **`seed`**: determines parent sampling and minibatch order. Same seed → same trajectory (modulo LM non-determinism on the student / reflection sides).

## What this does NOT include

- **Multi-predictor optimization** (DSPy's strength). An `AgentConfig` has exactly one system prompt; multi-stage pipelines run their own optimizer per stage.
- **Few-shot demonstration bootstrapping.** GEPA is instruction optimization; few-shot composition is a different shape.
- **Cross-validation / held-out test sets.** Run the optimizer over your `valSet`, then evaluate `result.applyTo(student)` against an independent test set yourself.
