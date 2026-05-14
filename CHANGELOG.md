# Changelog

All notable changes to Helios are documented here. Versions follow [SemVer](https://semver.org/).

## [1.5.3] — 2026-05-14

No breaking changes. Unblocks Azure OpenAI / OpenAI-compatible proxies / Vertex / Bedrock by letting library users override the provider's base URL and HTTP headers via `ModelConfig`. Symmetric across all three providers (OpenAI, Anthropic, Gemini).

### Added — `ModelConfig.withBaseUrl(String)` and `ModelConfig.withHeader(...)` / `withHeaders(Map)`

`ModelConfig` gains two new fields and three new builders:

- `withBaseUrl(String)` — overrides the provider's hardcoded API endpoint. `null` (default) keeps the canonical URL: `https://api.openai.com/v1/responses`, `https://api.anthropic.com/v1/messages`, `https://generativelanguage.googleapis.com/v1beta`.
- `withHeader(String name, String value)` — adds one extra HTTP header to every request.
- `withHeaders(Map<String,String>)` — bulk variant; `null` clears.

Header names match **case-insensitively** against the provider's built-in headers (`Authorization`, `x-api-key`, `x-goog-api-key`). When a user header's name matches a built-in, the user value replaces the built-in entirely — that's what makes Azure work, where the auth header is `api-key` rather than `Authorization: Bearer`.

Two new public helpers on `ModelConfig` expose the merge logic for downstream provider implementations: `effectiveBaseUrl(String providerDefault)` and `effectiveHeaders(Map<String,String> defaults)`.

### Relaxed — `apiKey` is optional when `baseUrl` is set

Provider constructors (`OpenAIModel`, `AnthropicModel`, `GeminiModel`) previously rejected any `ModelConfig` whose `apiKey` was null/blank. They now accept blank-or-null `apiKey` as long as `baseUrl` is configured. In that mode the provider's default auth header (`Authorization: Bearer ...`, `x-api-key: ...`, `x-goog-api-key: ...`) is **omitted entirely** — the user is expected to supply their own via `withHeader(...)`. When `baseUrl` is null the original check still fires.

This is what makes the Azure path clean: a deployment URL + `api-key` header, with no leftover `Authorization` header in the wire request.

### Azure OpenAI usage

```java
var config = ModelConfig.newBuilder()
    .withBaseUrl("https://my-resource.openai.azure.com/openai/deployments/my-deployment/responses?api-version=2024-08-01-preview")
    .withHeader("api-key", System.getenv("AZURE_OPENAI_KEY"))
    .build();
var model = new OpenAIProvider().create(OpenAIModelId.GPT_4O.id(), config);
```

Same pattern works for AWS Bedrock with the Anthropic provider, Vertex AI with the Gemini provider, or any OpenAI-/Anthropic-/Gemini-compatible reverse proxy (LiteLLM, vLLM, Ollama).

### Why this shape

Reported by a library user — `OpenAIModel` hardcoded the OpenAI endpoint and `ModelConfig` had no escape hatch. The narrowest fix would have been an OpenAI-only `withBaseUrl`. We applied it symmetrically across all three providers because Anthropic-via-Bedrock and Gemini-via-Vertex are real cases and asymmetric now is asymmetric forever. The deliberately-rejected alternative — a dedicated `OpenAIAzureProvider` with deployment-name/api-version awareness — felt premature for one report; `withBaseUrl` + `withHeaders` is the generic seam that subsumes Azure plus a long tail of compatible endpoints.

## [1.5.2] — 2026-05-13

No breaking changes. Bundles everything from 1.5.1 (autoresearch optimizer primitives, GepaPromptOptimizer reference example, OpenAI 5.x model value corrections) plus one input-binding fix that motivated the dot-release. **Library users on 1.5.0 should jump directly to 1.5.2** — v1.5.1 was tagged but not deployed to Maven Central, so the public upgrade path is 1.5.0 → 1.5.2.

### Fixed — Hybrid input binding for user-typed collections (Spec 05)

`InputBindings` no longer falls back to raw `Object` for `List<UserType>` / `Map<String, UserType>` / `Set<UserType>` and their nested forms. The container shape is preserved; only the type arguments erase to `java.lang.Object`. The model gets `.size()`, `.get()`, iteration without a manual cast.

```
List<UserType>            → List<Object>
Map<String, UserType>     → Map<String, Object>
Set<UserType>             → Set<Object>
Map<String, List<UserType>> → Map<String, List<Object>>
UserType[]                → Object[]
UserType (top-level)      → raw Object (unchanged — rare in practice)
List<Integer> etc.        → unchanged (still typed)
```

Concrete user impact: trajectories on user-typed inputs (e.g. SDTM mapping) save 2 iterations + ~10K tokens that were previously spent recovering from `cannot find symbol: method size()` errors when the model tried the natural `.size()` / `.get(0)` on what looked like a `List`.

Design rationale and rejected alternatives (full drop to `Map<String,Object>`, mini-OSGi user-type exposure) captured in `docs/specs/05-input-binding-design.md` (in-repo, gitignored).

### Carried forward from 1.5.1 (not deployed to Central)

For reference — these landed on `main` under the v1.5.1 tag but were never published. They ship to Central as part of 1.5.2:

- **Spec 03** autoresearch optimizer primitives (`ParetoFrontier`, `ReflectiveMutator`, `LlmReflectiveMutator`, `FeedbackMetric`, `TraceFeedback`, `TraceSampler`, `ReflectionFailedException`). `Evaluator.withFeedbackMetric(...)`. New `EvalResult.feedback()` / `perExampleScores()` helpers. New `ExampleResult.feedback` field (backwards-compatible canonical constructor preserved).
- **Spec 04** `examples/gepa-prompt` reference optimizer with `AutoBudget`, `CandidateLineage`, `GepaResult.applyTo(AgentConfig)`. Live-Gemini integration test lifts a 3-class sentiment classifier from ~33% baseline to ≥70% accuracy via `AutoBudget.LIGHT`.
- **OpenAI 5.x fixes**. Every 5.x entry had wrong `maxOutputTokens` (whole family is 128K, was 32K/16K); 5.4 family also had wrong context window:

| Model | context (was → is) | max output (was → is) |
|---|---|---|
| `gpt-5.5` | 1,050,000 ✓ | 32,000 → **128,000** |
| `gpt-5.4` | 1,000,000 → **1,050,000** | 32,000 → **128,000** |
| `gpt-5.4-mini` | 1,000,000 → **400,000** | 32,000 → **128,000** |
| `gpt-5.4-nano` | 1,000,000 → **400,000** | 16,000 → **128,000** |

## [1.5.1] — 2026-05-13 (tagged, not deployed)

Tagged on `main` and pushed to `release/1.5.1` but **not deployed to Maven Central**. The 1.5.2 release supersedes it; the content below was the planned scope before the input-binding fix bumped us to 1.5.2.

### Added — Autoresearch optimization primitives

No breaking changes. Additive autoresearch primitives + worked optimizer example + OpenAI 5.x model value corrections.

### Added — Autoresearch optimization primitives

New types in `ai.singlr.core.eval` for GEPA-shaped optimizers:

- **`ParetoFrontier<C>`** — tracks candidates by per-instance validation scores and maintains the Pareto-non-dominated set. Coverage-weighted `sampleByCoverage(Random)`, `bestSingle()`, `aggregateScore(C)`, `envelope()`, `snapshot()` / `restore()`. Thread-safe via `ReentrantReadWriteLock`. NaN scores rejected at the boundary.
- **`ReflectiveMutator<C>`** — functional interface: `propose(parent, traces) → new candidate`. `LlmReflectiveMutator` is the reference implementation for `C = String` prompts, decomposed across `ReflectionPromptTemplate` (prompt assembly), `ReflectionResponseParser` (post-process + acceptance test), and `TraceSampler` (which traces the reflection LM sees). Schema-constrained retry on malformed responses; `ReflectionFailedException` when both attempts fail.
- **`FeedbackMetric<E, A>`** — sibling to `Metric` returning `{score, feedback}` for feedback-aware optimizers. `.asScalar()` adapts cleanly back to `Metric` when only a number is needed.
- **`TraceFeedback`** record — one `(input, expected, actual, score, feedback, trace)` tuple, the natural input to `ReflectiveMutator.propose`.

`Evaluator.Builder.withFeedbackMetric(FeedbackMetric)` is mutually exclusive with `withMetric(Metric)`. `ExampleResult` gained a `feedback` field (backwards-compatible canonical constructor preserved). `EvalResult.feedback()` re-shapes per-example results as `List<TraceFeedback>` for `ReflectiveMutator.propose` input; `EvalResult.perExampleScores()` returns the natural `double[]` shape for `ParetoFrontier.add`.

### Added — `examples/gepa-prompt` reference optimizer

New (unpublished) example module composes the primitives above into a working GEPA-shaped prompt optimizer:

- `GepaPromptOptimizer<I, O>` + Builder (~450 LoC driver)
- `AutoBudget.LIGHT / MEDIUM / HEAVY` budget presets (6 / 12 / 24 iterations; linear scaling)
- `CandidateLineage` parent → child graph
- `GepaResult` with `applyTo(AgentConfig)` helper

Live-Gemini integration test lifts a deliberately-weak 3-class sentiment classifier from ~33% baseline to ≥70% accuracy via `AutoBudget.LIGHT` (~6 iterations). The example is the proof-of-design for the primitives — if it were awkward, the primitives would be wrong.

### Fixed — OpenAI 5.x model context windows and max output tokens

Verified against `developers.openai.com/api/docs/models/gpt-5.4` (and corresponding mini/nano/5.5 pages). Every 5.x entry had the wrong `maxOutputTokens` (the whole family is 128K, not 32K/16K). The 5.4 family also had the wrong context window.

| Model | context (was → is) | max output (was → is) |
|---|---|---|
| `gpt-5.5` | 1,050,000 ✓ | 32,000 → **128,000** |
| `gpt-5.4` | 1,000,000 → **1,050,000** | 32,000 → **128,000** |
| `gpt-5.4-mini` | 1,000,000 → **400,000** | 32,000 → **128,000** |
| `gpt-5.4-nano` | 1,000,000 → **400,000** | 16,000 → **128,000** |

Added 4 new `OpenAIModelIdTest` assertions for the previously-uncovered fields (the coverage gap is why the wrong values went unnoticed). GPT-4.1 family and GPT-4o not re-audited in this release — file separately if you want a broader audit.

## [1.5.0] — 2026-05-13

### Breaking — Unified event stream replaces three legacy SPIs

The observability surface collapses from three separate listener interfaces into a single sealed event stream. This is the load-bearing change in 1.5.0 and every library user has to act on it.

**Removed (no compat shim):**

- `ai.singlr.core.trace.TraceListener`
- `ai.singlr.core.trace.SpanListener`
- `ai.singlr.core.trace.SpanStart`
- `ai.singlr.core.trace.CollectingTraceListener` (use `ai.singlr.core.events.CollectingEventSink` instead)
- `ai.singlr.core.memory.MemoryListener`
- `ai.singlr.core.memory.MemoryEvent`
- `AgentConfig.Builder.withTraceListener(...)` / `withSpanListener(...)` / `withMemoryListener(...)` and their list variants
- `Memory.addListener(...)` / `removeListener(...)`

**Added:**

- `ai.singlr.core.events.HeliosEvent` — sealed interface with 26 variants covering the agent loop, iteration boundaries, assistant text/thinking, tool calls, memory reads/writes, span open/close, sub-agent delegation, compaction, and optimizer events.
- `ai.singlr.core.events.EventSink` — functional interface, the single observability seam.
- `ai.singlr.core.events.CollectingEventSink` — thread-safe `List<HeliosEvent>` accumulator for tests.
- `ai.singlr.core.events.JsonlEventSink` — append-only JSON-Lines sink for live UIs and post-hoc audit.
- `ai.singlr.core.events.EventSinkPolicy` — backpressure / overflow policy.
- `AgentConfig.Builder.withEventSink(...)` / `withEventSinks(...)`.
- `Memory.addEventSink(...)` — memory-write events flow through the same stream.

**Migration path:** wrap your old listener logic in an `EventSink` lambda and pattern-match on the sealed `HeliosEvent` hierarchy. `TraceListener#onTraceClose` corresponds to `HeliosEvent.RunCompleted` (carrying the complete `Trace`). `SpanListener#onSpanStart/onSpanEnd` correspond to `HeliosEvent.SpanOpened` / `SpanClosed`. `MemoryListener#onMemoryWrite` corresponds to `HeliosEvent.MemoryWritten`.

### Added — Provider thinking-delta streaming

Anthropic, Gemini, and OpenAI now surface model reasoning through `StreamEvent.ThinkingDelta` and `StreamEvent.ThinkingComplete(fullText, signature)` during `runStream(...)`. Verified end-to-end against live APIs for all three providers.

### Added — `CodeActHarness` (Spec 02)

New one-line typed entrypoint at `ai.singlr.repl.CodeActHarness` for CodeAct flows (REPL without sub-LM). Composes the same substrate as `RlmHarness` (`ReplSession` + `CodeExecutionTool` + `InputBindings`) but with no `predict()`, no `submit()`, and no extract-fallback — the model writes Java in a sandboxed JShell REPL across turns and returns its structured answer as the final assistant message, captured via the Agent's `OutputSchema` path. `CodeActResult.Status` is `SUCCEEDED` or `FAILED`.

### Added — REPL substrate seams

- `ReplConfig.Builder.withAutoRegisterSubmit(boolean)` — controls whether `ReplSession.create(...)` auto-installs the `submit` host function. Defaults to `true` (RlmHarness-compatible). `CodeActHarness` flips it to `false`.
- `ai.singlr.repl.InputBindings` promoted from package-private to public so both harnesses share the typed-input JShell-binding utility.
- `ai.singlr.repl.PromptRendering` (package-private) — shared rendering helpers between `RlmSystemPrompt` and `CodeActSystemPrompt`.

### Fixed — Gemini v2 wire-format drift on thought signatures

The May 2026 Gemini Interactions API (`Api-Revision: 2026-05-20`) delivers thought signatures as a `step.delta` whose `delta.type == "thought_signature"`, not on `step.start` as the migration guide showed. `GeminiModel.StreamingIterator` now recognises that shape; the legacy `step.start`-carries-signature path is also still covered for fixture compat. Symptom before fix: `gemini.thoughtSignatures` missing from response metadata in thinking mode.

### Fixed — Gemini streaming function-call arguments

Added `ArgumentsDeserializer` for the JSON-encoded-string shape of `arguments` that the streaming Gemini Interactions API ships in some `interaction.step_*` events. Normalises both shapes (Map or string) to an internal `Map<String,Object>`.

### Internal

- `EventEmitter` extracted from `Agent` as a top-level package-private helper — reduces Agent file size and isolates the per-run fan-out logic.
- `Workflow` non-durable runs use a synthetic `runId` via `Ids.newId()` so they participate in the unified event stream.
