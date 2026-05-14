# Changelog

All notable changes to Helios are documented here. Versions follow [SemVer](https://semver.org/).

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
