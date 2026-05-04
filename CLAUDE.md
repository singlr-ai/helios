# Singular Agentic Framework

Production-grade agentic framework for Java. Simple, explicit, no magic.

## Project Info

- **Java**: 25 | **Build**: Maven | **License**: MIT
- **Group ID**: `ai.singlr`

## Philosophy

1. **No magic** - Explicit wiring, no annotation-driven DI
2. **Idiomatic Java** - Records, sealed types, pattern matching
3. **Production from day 1** - Fault tolerance, observability, 100% test coverage

## Libraries

- **Jackson 3.x** for JSON ([migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md))
- **JUnit 6.x** for testing ([docs](https://docs.junit.org/6.0.2/overview.html))
- **Helidon SE 4.3.x** for persistence modules ([DbClient docs](https://helidon.io/docs/v4/se/dbclient)) — not in core

## Coding Conventions

- Records with static Builder class, `with` prefix for builder methods
- No `get` prefix for accessors
- 2-space indent, 4-space continuation
- No inline comments; use Javadocs for public API
- Use `var`, `List.of()`, `Map.of()`
- DO NOT use wildcard imports, ever!
- Copyright header: `/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */`

**CRITICAL** Talk to me before making design decisions

## Architecture

```
helios/
├── core/                           # Zero deps - Interfaces, Agent, Memory, Tools, Eval, Fault Tolerance
├── gemini/                         # Gemini Interactions API + Jackson 3.x
├── anthropic/                      # Claude Messages API + Jackson 3.x
├── openai/                         # OpenAI Responses API + Jackson 3.x
├── repl/                           # Sandboxed code execution for RLM patterns + Jackson 3.x
├── persistence/                    # PostgreSQL persistence - Helidon DbClient
└── examples/
    ├── autoresearch-prompt/        # Reference: prompt tuning via eval primitives (not published)
    ├── autoresearch-code/          # Reference: code optimization via git Checkpoint (not published)
    └── rlm-demo/                   # Reference: RlmHarness end-to-end integration test against Gemini (not published)
```

### JPMS Modules

Core exports public API, providers register via ServiceLoader SPI.

## Key Patterns

| Pattern | Description |
|---------|-------------|
| **Result<T>** | Sealed interface: Success/Failure with pattern matching |
| **Memory** | Letta-inspired: Core blocks (always in context) + 2 tools (`memory_update`, `memory_read`) with maxSize enforcement |
| **Context Compaction** | Two-tier: micro-compact (drop old tool results at 75%) + auto-compact (model summarization at 90%) |
| **Agent Loop** | `run()` for completion, `step()` for manual control, `run(session, OutputSchema.of(T.class))` for structured output, `runStream()` for token streaming |
| **Parallel Tools** | `AgentConfig.parallelToolExecution(true)` — multiple tool calls execute concurrently on virtual threads, results preserved in call order |
| **Agent.asTool()** | `Agent.asTool(name, desc, config)` — wraps an agent as a Tool for sub-agent delegation. Fresh Agent per call (thread-safe) |
| **Teams** | Leader agent with worker agents as delegation tools — same API as Agent. `withParallelToolExecution(true)` for concurrent worker dispatch |
| **Nested Tracing** | `Team.run(...)` produces ONE unified `Trace`. Worker-agent spans (model calls, tool executions, sub-delegations) nest as children of the leader's `tool.<worker-name>` delegation span via `Agent.PARENT_SPAN` (JEP 506 `ScopedValue`, final in Java 25). Workers' own `TraceListener`s do not fire in nested mode — everything flows through the leader's listener. Standalone (non-nested) agent runs still fire their own trace as before. `SpanContainer` sealed interface unifies `TraceBuilder` and `SpanBuilder` so the agent loop creates spans against either polymorphically. The delegation span carries two diagnostic attributes: `subAgent.nested=true` (set by `asTool` when `PARENT_SPAN` was bound) and `subAgent.spanCount=<n>` (set by the nested sub-run when it ends) — together they let operators confirm nesting engaged end-to-end just by inspecting span attributes |
| **CollectingTraceListener** | Thread-safe `TraceListener` in `core/trace` that accumulates fired traces into a `List<Trace>`. With nested tracing you usually get one entry per top-level `Team.run`/`Agent.run` |
| **Streaming** | `runStream()` returns `CloseableIterator<StreamEvent>` — virtual thread + blocking queue + iterator pattern. `StreamEvent` sealed: TextDelta, ToolCallStart, ToolCallDelta, ToolCallComplete, Done, Error |
| **SpanListener (live observability)** | `core.trace.SpanListener` fires `onSpanStart(SpanStart)` and `onSpanEnd(Span)` as spans open/close — parallel SPI to `TraceListener` which fires only at trace close. Wire via `AgentConfig.Builder.withSpanListener(...)` or `Team.Builder.withSpanListener(...)`. In nested-trace mode (worker inside Team), worker `SpanListener`s are silenced — listeners thread through `SpanBuilder` constructors so the leader's listeners observe everything. `tracingEnabled()` returns true when either listener kind is configured |
| **RlmHarness (helios-repl)** | One-line typed entrypoint: `RlmHarness.builder(I.class, O.class).model(...).sandboxFactory(...).strategy("...").build().run(input)`. Bundles `ReplSession` + `CodeExecutionTool` + auto-registered `predict()` + typed `submit()` + canonical system prompt + `ExtractFallback`. Defaults match Trampoline production: `maxIterations=30`, `maxLlmCalls=50`, `maxOutputCharsToModel=5000`. `RlmResult.status()` is `SUBMITTED` (clean), `EXTRACTED` (fallback recovered output from trajectory), or `FAILED` |
| **Fault Tolerance** | Zero-deps: Backoff, RetryPolicy, CircuitBreaker, FaultTolerance |
| **Secret Redaction** | `core.common.SecretRegistry` is a thread-safe registry of secret values; `Redactor` (built via `registry.redactor()`) is an immutable Aho-Corasick byte-level scrubber that replaces every contiguous occurrence of a registered secret with `<redacted:NAME>`. Operates on raw bytes BEFORE UTF-8 decode so encoding mangling cannot bypass it. Validation: secrets must be ≥8 chars and pure ASCII. Overlap policy: leftmost-longest. Same byte value under two names attributes to the first registered. Zero deps |
| **Provenance (Basis-style structured output)** | `core.common.Confidence` (LOW/MEDIUM/HIGH ordinal), `core.common.Source` (title?, url, excerpts), `core.common.FieldProvenance` (field, sources, reasoning, confidence), `core.common.Provenanced<T>` ({output, provenance:[...]} sidecar pattern). `OutputSchema.provenancedOf(MyOutput.class)` returns an `OutputSchema<Provenanced<MyOutput>>` whose JSON schema asks the model for `{output, provenance}`. `ProvenanceValidator.DEFAULT` rejects `MEDIUM`/`HIGH` entries with no sources — the calibration mechanism that prevents the model from rubber-stamping HIGH on every field. Custom validators via `OutputSchema.provenancedOf(MyOutput.class, validator)`; `ProvenanceValidator.excerptLengthCap(int)` and `andThen(...)` compose. `SubmitFunction` (REPL/RLM path) reconstructs `Provenanced<T>` from the submitted Map, enforces structural correspondence (every output field has exactly one entry, no entries reference unknown fields, no duplicates), and runs the validator — failures throw back through JSON-RPC so the model sees them inline and retries. `OutputSchema.reconstructProvenanced(Map, Function)` is the core-side helper providers can invoke. Mirrors parallel.ai's Basis framework with the family renamed to "provenance" |
| **Persistent core memory (PgMemory)** | `helios_core_blocks` table keyed by `(agent_id, block_name)` upserts on `putBlock`, persists `data` as JSONB, preserves `created_at` while refreshing `updated_at`. Per-user/per-tenant scoping via the `agentId` namespace (e.g. `"kubera-research:user-42"`). Combined with the system-prompt re-render in `Agent` (refreshes `${core_memory}` every iteration when memory has changed), `memory_update` calls mid-run are visible to the model on the very next iteration |
| **CommandGrant (host-owned CLI)** | `core.tool.CommandGrant.builder("gh").withSecretRegistry(reg).withEnv("GH_TOKEN", t).build().toTool()` produces a Tool that lets the model invoke a single CLI binary under tight controls. Hardening, all on by default: binary path pinned at build time (no PATH-shadow surprises), argv-only never shell, `ProcessBuilder.environment().clear()` then injects only granted env (no JVM env leak), argv pre-scan refuses any registered secret value (forces env-only secret transport), stdin = closed, per-call temp cwd by default, stdout+stderr capped + redacted, descendants killed on timeout via `ProcessHandle.descendants()`, stderr hidden from model unless `withStderrToModel(true)`. Concurrency limited per grant (default 4, immediate refuse on overflow). `InvocationResult.redactionCounts()` exposes per-secret hit counts for telemetry |
| **FilesystemKnowledge (curated corpus)** | `core.knowledge.FilesystemKnowledge.builder(root).withSecretRegistry(reg).build().tools()` returns three read-only tools — `kb_grep` (Java regex over file lines), `kb_glob` (path matching), `kb_read` (line-range file reads) — bounded by per-file size caps, per-read byte caps, per-grep wall-clock deadline, and result count caps. Pure JDK (`Files.walkFileTree` + `Pattern`); no `rg` dependency. Path-jail via `PathJail`: lexical normalize + `startsWith(root)` refuses `..`/absolute, then `toRealPath` refuses symlink escapes. Symlinks encountered during walk are skipped entirely. Hidden directories pruned by default (`.git/` etc); `withSkipHidden(false)` overrides. Binary files (NUL byte in first 8 KB) skipped by grep. Every tool's output flows through the shared `SecretRegistry`, so a token written to a file by `CommandGrant("gh")` is redacted when the agent later reads it via `kb_read`. Read-only by design — no `kb_write` |
| **Grounding Citations in Traces** | When a model returns `Response.citations()`, the `model.chat` span records `groundingCitationCount` and `groundingSources` (deduplicated, `www.`-stripped, comma-separated domains). Cheap — no flag required |
| **Research Guardrails** | `AgentConfig.withMinIterations(n)` forces at least N iterations; `withRequiredTools("a","b")` forces the named tools to be called at least once. When the model tries to stop early, the agent injects a `USER` guidance message (metadata `helios.injected=minIterations\|requiredTools`) and loops. `maxIterations` remains the absolute ceiling |
| **Iteration Hook** | `AgentConfig.withIterationHook(ctx -> ...)` — programmatic completion control. Fires only when the model wants to stop and built-in guardrails are satisfied. Hook returns `IterationAction.allow()`, `stop()`, or `inject(msg)`. `IterationContext` exposes iteration number, required/called tools, total tool count, response, and immutable message history. Hook exceptions are caught and surface as `Result.failure` |
| **Evaluation** | `Evaluator` runs an `AgentConfig` over `List<Example<I, O>>` on virtual threads, attaches per-run `TraceListener`, scores via `Metric<O, O>`, returns `EvalResult`. Builds a fresh `Agent` per example (never shared across threads). `Metric<E, A>` keeps expected and actual types separate so criteria-shape metrics (expected = descriptor, actual = produced output) don't have to fake a single type |
| **Autoresearch** | Iterative optimization loop — LLM proposes candidates, `Objective<C>` scores them, keep/discard via `Checkpoint<C>`, durable append-only `ExperimentLog` (JSONL), MAD-based `ConfidenceScorer`. No framework loop class — composition of Agent + tools + primitives. Two reference example modules validate the abstraction on prompt and code domains |

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived OS resources (HttpClient connection pools, file descriptors). Per-provider `close()` calls `httpClient.shutdown()` with a 5s grace period before `shutdownNow()`.

**Ownership rule:** the component that constructs a `Model` owns its lifecycle. `Agent` is per-request and stateless (see Review False Flags below), so `Agent` is intentionally NOT `AutoCloseable` — closing the Model from inside Agent would break sibling Agents that share it. Build the Model once at app startup, share it across many Agents/Teams, and `close()` it once at shutdown.

`ReplSession` and `Sandbox` are `AutoCloseable` and own their subprocess. `JvmSandbox` also installs a JVM shutdown hook so a leaked sandbox is force-killed on host JVM exit.

On JDK 25 the JDK `HttpClient`'s selector and default executor are daemon threads, so a leaked Model does not prevent JVM exit. The leak is OS-level (FDs, sockets, pooled connections), not threads. Closing remains the right thing for any long-running service.

## Review False Flags

When critically reviewing this codebase, do NOT flag the following — they have been investigated and are not issues:

- **Agent.resolveTools() cache race condition** — Agent is instantiated per-request (`new Agent(config)`), never shared across threads. The non-volatile cache is correct for single-threaded use.
- **PgPromptRegistry.register() TOCTOU version race** — The database `UNIQUE (name, version)` constraint catches concurrent duplicate inserts. The transaction will fail and the caller retries. This is by design.
- **SCIM filter SQL injection** — The `scim-sql` library produces parameterized clauses with bind variables. It is our own OSS library and is safe.
- **FaultTolerance virtual executor leak** — `newVirtualThreadPerTaskExecutor()` uses JVM-managed virtual threads. `cancel(true)` interrupts the thread; it gets GC'd. No leak.
- **Streaming InputStream not closed** — `BufferedReader.close()` cascades through `InputStreamReader.close()` to `InputStream.close()`. Java decorator pattern handles this correctly.
- **Silent tool failure in Agent loop** — By design. The agent sends the failure back to the model so it can self-correct. The max iterations guard prevents infinite loops.
- **parseStreamEvent returns null for thought events** — By design. Thoughts accumulate internally and surface in the `Done` event.
- **Jackson exception = silent data loss in persistence** — Exceptions are caught and wrapped in `PgException`, which propagates to the caller. Not silent.
- **Parallel tool execution swallows exceptions** — By design. Each tool catches its own FT exceptions and returns `ToolResult.failure()`. One tool timing out doesn't abort others. The model sees all results and self-corrects.
- **Agent.finalizeTrace silently converts end() failures to fail()** — Defensive against a span-leak bug class that surfaced in Kubera's prod (1.0.30) where some race left a child span open and bubbled `IllegalStateException` out of `team.run`. The user's request had completed successfully; the leaked span is a tracing detail that must not abort the API call. We log a `WARNING` so the leak is visible, then force-close via `fail()` so listeners still receive the trace. Same rationale for `forceCloseSpan` in tool-execution finally blocks and the try-catch around `recordNestedSpanCount`.

## Core Module: COMPLETE ✓

1373 tests, 97%+ instruction coverage on existing packages; 91% / 87% on `eval` package.

```
ai.singlr.core/
├── agent/     AgentConfig, AgentState (now carries promptVars), Agent (refreshes ${core_memory}
               in the system message every iteration), AgentStreamIterator, Team, ContextCompactor,
               TokenEstimator, IterationHook, IterationAction, IterationContext
├── common/    Result<T>, Strings, HttpClientFactory, Ids (UUID v7 + UTC timestamps),
               SecretRegistry, Redactor, RedactionResult,
               Confidence, Source, FieldProvenance, Provenanced, ValidationResult,
               ProvenanceValidator (DEFAULT rejects MEDIUM/HIGH without sources)
├── eval/      Metric, Example, Score, Objective, Checkpoint, InMemoryCheckpoint,
               ExperimentEntry, ExperimentLog, InMemoryExperimentLog, FileExperimentLog (JSONL),
               ConfidenceScorer (MAD-based), Evaluator, EvalResult, ExampleResult
├── fault/     Backoff, RetryPolicy, CircuitBreaker, FaultTolerance
├── knowledge/ FilesystemKnowledge (kb_grep, kb_glob, kb_read), PathJail
├── memory/    MemoryBlock, Memory, InMemoryMemory, MemoryTools
├── model/     Message, Response, Model, ModelProvider, ModelConfig, ToolCall, ToolChoice,
               StreamEvent, FinishReason, Role, ThinkingLevel, Citation
├── prompt/    Prompt, PromptRegistry, InMemoryPromptRegistry
├── schema/    JsonSchema, OutputSchema (now provenanced via OutputSchema.provenancedOf(Class)
               which returns OutputSchema<Provenanced<T>> + a ProvenanceValidator binding;
               OutputSchema.reconstructProvenanced(Map, Function) builds a typed Provenanced<T>
               from a deserialized response without requiring core to depend on Jackson),
               SchemaGenerator
├── tool/      Tool, ToolParameter, ToolExecutor, ToolResult, ParameterType, CommandGrant
├── trace/     Trace, Span, SpanKind, Annotation, TraceListener, CollectingTraceListener,
               SpanListener, SpanStart, TraceBuilder, SpanBuilder,
               SpanContainer (sealed: TraceBuilder | SpanBuilder)
└── workflow/  Step (sealed), Workflow, StepResult, StepContext, AgentStep, FunctionStep,
               Sequential, Parallel, Condition, Loop, Fallback
```

## Gemini Module: COMPLETE ✓

104 unit + 27 integration tests. Uses **Interactions API** (not legacy generateContent).

- **API Spec**: https://ai.google.dev/static/api/interactions.openapi.json
- **Docs**: https://ai.google.dev/api/interactions-api

```
ai.singlr.gemini/
├── GeminiModelId      # Enum of supported models (GEMINI_3_FLASH_PREVIEW)
├── GeminiModel        # Implements Model interface
├── GeminiProvider     # Implements ModelProvider SPI
└── api/               # DTOs: InteractionRequest, Turn, ContentItem, etc.
```

### Supported Features

| Feature | Status |
|---------|--------|
| Text chat | ✅ |
| Multi-turn conversations | ✅ |
| System instructions | ✅ |
| Function calling (tools) | ✅ |
| Streaming (SSE) | ✅ |
| Usage statistics | ✅ |
| Generation config (temperature, topP, maxTokens, stopSequences, seed) | ✅ |
| Tool choice (auto/any/none/required) | ✅ |
| Thinking level | ✅ |
| Structured output (JSON schema) | ✅ |
| Thought signature round-tripping | ✅ |
| Google Search grounding (citations via streaming `text_annotation` deltas) | ✅ |

### Not Yet Implemented

- Multimodal input (image, audio, video, document)
- Code Execution tools
- Safety settings

## Anthropic Module: COMPLETE ✓

148 tests. Uses **Messages API** (`POST https://api.anthropic.com/v1/messages`).

- **API Docs**: https://docs.anthropic.com/en/api/messages

```
ai.singlr.anthropic/
├── AnthropicModelId   # Enum: CLAUDE_OPUS_4_7, CLAUDE_OPUS_4_6, CLAUDE_SONNET_4_6
├── AnthropicModel     # Implements Model interface (internal streaming, SSE)
├── AnthropicProvider  # Implements ModelProvider SPI (name = "anthropic")
├── AnthropicException # RuntimeException with statusCode classification
└── api/               # DTOs: MessagesRequest, MessagesResponse, ContentBlock, etc.
```

### Supported Features

| Feature | Status |
|---------|--------|
| Text chat | ✅ |
| Multi-turn conversations | ✅ |
| System instructions | ✅ |
| Function calling (tools) | ✅ |
| Streaming (SSE) | ✅ |
| Usage statistics | ✅ |
| Generation config (temperature, topP, maxTokens, stopSequences) | ✅ |
| Tool choice (auto/any/none/required) | ✅ |
| Extended thinking (budget_tokens) | ✅ |
| Structured output (JSON schema via system prompt) | ✅ |
| Thought signature round-tripping | ✅ |
| TOOL message coalescing | ✅ |

### Key Design Decisions

- All requests stream internally (avoids HTTP timeouts on long generations)
- Paired `event:`/`data:` SSE line parsing (Claude-specific format)
- Tool call streaming: partial JSON in `input_json_delta`, parsed at `content_block_stop`
- Consecutive TOOL messages coalesce into single user message with `tool_result` blocks
- Thinking shape dispatches per `AnthropicModelId.usesAdaptiveThinking()`:
  - **Legacy** (Opus 4.6, Sonnet 4.6): `thinking={"type":"enabled","budget_tokens":N}`. NONE→null, MINIMAL→1024, LOW→4096, MEDIUM→10000, HIGH→32000.
  - **Adaptive** (Opus 4.7+): `thinking={"type":"adaptive"}` + sibling `output_config={"effort":"low|medium|high"}`. NONE→null, MINIMAL/LOW→low, MEDIUM→medium, HIGH→high. Opus 4.7 explicitly rejects the legacy shape with `"thinking.type.enabled" is not supported for this model` — dispatch is mandatory, not cosmetic. New models default to legacy shape; flip `usesAdaptiveThinking=true` when adaptive is required (1.1.5).
- Metadata keys: `anthropic.thinking`, `anthropic.thinkingSignature`

## OpenAI Module: COMPLETE ✓

193 tests. Uses **Responses API** (`POST https://api.openai.com/v1/responses`).

- **API Docs**: https://platform.openai.com/docs/api-reference/responses

```
ai.singlr.openai/
├── OpenAIModelId      # Enum: GPT_5_5, GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO, GPT_4_1, GPT_4_1_MINI, GPT_4_1_NANO, GPT_4O, GPT_4O_MINI, O3, O4_MINI
├── OpenAIModel        # Implements Model interface (internal streaming, SSE)
├── OpenAIProvider     # Implements ModelProvider SPI (name = "openai")
├── OpenAIException    # RuntimeException with statusCode classification
└── api/               # DTOs: ResponsesRequest, ResponsesResponse, InputItem, OutputItem, etc.
```

### Supported Features

| Feature | Status |
|---------|--------|
| Text chat | ✅ |
| Multi-turn conversations | ✅ |
| System instructions | ✅ |
| Function calling (tools) | ✅ |
| Streaming (SSE) | ✅ |
| Usage statistics | ✅ |
| Generation config (temperature, topP, maxTokens, stopSequences) | ✅ |
| Tool choice (auto/any/none/required) | ✅ |
| Reasoning effort (low/medium/high) | ✅ |
| Structured output (native JSON schema via text.format) | ✅ |
| Reasoning summary capture | ✅ |

### Key Design Decisions

- All requests stream internally (avoids HTTP timeouts on long generations)
- Standard SSE `data:` lines only (simpler than Anthropic's `event:`+`data:` pairs)
- Input is array of Items (messages, function_call, function_call_output) — not legacy messages
- System instructions via top-level `instructions` field (not in input array)
- Tool call arguments serialized as JSON string (OpenAI convention)
- ThinkingLevel mapped to reasoning effort: NONE→null, MINIMAL/LOW→"low", MEDIUM→"medium", HIGH→"high"
- Reasoning summaries stored in `Response.thinking` with metadata key `openai.reasoning`
- ToolChoice.Any maps to `"required"`, ToolChoice.Required maps to `{type: "function", name: "..."}`

## Persistence Module: COMPLETE ✓

96 tests. PostgreSQL via Helidon DbClient + TestContainers.

```
ai.singlr.persistence/
├── PgConfig           # Shared config: DbClient, schema, agentId
├── PgMemory           # Memory impl — archival, session history, session registry, core blocks
├── PgPromptRegistry   # PromptRegistry impl — versioned prompts
├── PgTraceStore       # TraceListener impl — traces, spans, annotations
├── PgException        # Unchecked exception wrapper
├── sql/               # SQL constants: PromptSql, TraceSql, SpanSql, AnnotationSql, ArchiveSql, MessageSql, SessionSql, CoreBlockSql
└── mapper/            # Row mappers: PromptMapper, TraceMapper, SpanMapper, AnnotationMapper, ArchiveMapper, MessageMapper, CoreBlockMapper, JsonbMapper, DbTypeMapperProvider
```

## REPL Module: IN PROGRESS

570 tests. Sandboxed code execution for **RLM (Recursive Language Model)** patterns. The substrate (sandbox, host functions, JSON-RPC) is supplemented by a typed `RlmHarness` that bundles the canonical RLM run shape — system prompt, typed submit, extract-fallback, predict budget, input bindings, scripting prelude — into a one-line entrypoint.

```
ai.singlr.repl/
├── RlmHarness               # One-line typed entrypoint: builder(I.class, O.class).model(...).build().run(input)
├── RlmResult                # Record: output, status (SUBMITTED/EXTRACTED/FAILED), error, history, predictCallCount
├── RlmSystemPrompt          # Generates canonical system prompt from input/output schemas + strategy
├── InputBindings            # Generates JShell pre-eval that exposes record fields as typed `var`s in the sandbox
├── ExtractFallback          # Reconstitute structured output from trajectory when loop ends without submit
├── Skill                    # Composable bundle: name, instructions, envTips, tools (merge() detects name conflicts; envTips render under "## Strategy: <name>")
├── RequiredPredictSignature # Declared (name, instructions) the model must invoke; harness hook injects corrective on miss
├── SandboxBindingsListener  # Observes the sandbox's working memory (every user-declared var) after each execute_code
├── SandboxBudgetExceededException # Thrown by predict() wrapper once maxLlmCalls is exhausted
├── CodeExecutionTool        # Static factory → Tool (like MemoryTools/Agent.asTool); truncates output to model; prepends budget header
├── ReplConfig               # Record + Builder: sandbox factory, timeout, host fns, submitSchema, maxLlmCalls, maxOutputCharsToModel, budgetHeader, requiredPredictSignatures, sandboxBindingsListener
├── ReplSession              # Session lifecycle (AutoCloseable), execution history, semaphore, predictCallCount, calledSignatures
├── ReplException            # Unchecked exception wrapper
├── sandbox/
│   ├── Sandbox              # Interface: execute(), isAlive(), close()
│   ├── SandboxFactory       # @FunctionalInterface: (HostFunctionRegistry) → Sandbox
│   ├── ExecutionRequest     # Record + Builder: code, language, timeout
│   ├── ExecutionResult      # Record + Builder: stdout, stderr, exitCode, submitted
│   ├── JvmSandbox           # JVM subprocess impl + RPC channel
│   ├── JvmSandboxConfig     # Record + Builder: timeouts, heap size
│   ├── JvmSandboxBootstrap  # JShell subprocess entry point (stdin/stdout JSON-RPC); installs SandboxPrelude
│   ├── SandboxPrelude       # JShell preamble: standard imports + free println + sum/mean/max/min/join/filter/map/sorted/countBy
│   └── HostBridge           # Static bridge: predict/submit/fetch/query/getInput — only Java type sandbox code needs to import
├── host/
│   ├── HostFunction         # Record: name, description, parameters, handler
│   ├── HostParameter        # Record: name, type, description, required — drives JShell wrapper synthesis
│   ├── HostFunctionHandler  # @FunctionalInterface: (Map) → Object
│   ├── HostFunctionRegistry # Mutable registry, freezable
│   ├── PredictFunction      # Factory: predict() backed by Model.chat() with fresh context
│   ├── SubmitFunction       # Factory: submit() — typed (validates against OutputSchema) or untyped
│   ├── SchemaValidator      # Lightweight JsonSchema validator producing model-readable error messages
│   ├── QueryFunction        # Factory: query() backed by DataSource (read-only SQL)
│   └── FetchFunction        # Factory: fetch() backed by HttpClient (domain allowlist)
└── protocol/
    ├── RpcMessage           # Sealed: Request, Response, ErrorResponse, Notification
    ├── RpcError             # Record: code, message, data (JSON-RPC 2.0 error codes)
    ├── RpcTransport         # Interface: send/receive over any channel
    ├── ProcessTransport     # stdin/stdout NDJSON with \0RPC: magic prefix
    └── RpcChannel           # Bidirectional dispatcher (virtual thread reader loop)
```

### Key Design Decisions

- JSON-RPC 2.0 over NDJSON for host-sandbox communication
- `\0RPC:` magic prefix distinguishes RPC lines from regular stdout
- Sandbox exceptions returned as `ToolResult.success()` so model sees tracebacks and self-corrects
- `PredictFunction` calls `Model.chat()` with fresh context (system + user only) — prevents context rot
- `SubmitFunction` uses `AtomicReference.compareAndSet` for single-call enforcement; when `OutputSchema` is configured, validation failures throw back through the JSON-RPC bridge so the model sees the error inline and retries within the same iteration loop without losing sandbox variables
- `CodeExecutionTool` truncates the formatted output shown to the model (default 5000 chars) and appends a marker explicitly stating "Variables in the sandbox retain their full values" — this is the load-bearing context-rot fix per Trampoline's production experience. The full untruncated text stays in `ReplSession.history()`
- `ReplSession` transparently wraps any host function named `predict` with a budget counter; once `maxLlmCalls` is exceeded the wrapper throws `SandboxBudgetExceededException` which propagates as a JShell traceback and signals the model to wrap up via `submit()`
- `ReplSession` uses `Semaphore.tryAcquire()` for max concurrent sessions
- `HostFunctionRegistry.freeze()` prevents modifications after sandbox startup
- `JvmSandboxBootstrap` enforces single-execute with `Semaphore(1)` — `System.setOut`/`setErr` are JVM-global, concurrent evals would corrupt streams
- Host bridge functions route all external access through the host process — sandbox never holds credentials or raw connections
- `QueryFunction` takes a `javax.sql.DataSource`, enforces read-only via first-keyword allowlist + `connection.setReadOnly(true)`
- `FetchFunction` takes an `HttpClient` + domain allowlist, HTTPS-only, prevents SSRF
- `RlmHarness` is a thin assembly over the substrate, NOT a parallel hierarchy — every option maps to an `AgentConfig` or `ReplConfig` field. `RlmSystemPrompt.build` ports Trampoline's `PREDICT_RLM_INSTRUCTIONS` conventions to a Java/JShell idiom (variable persistence, verify-then-submit-alone, validation retry, budget paragraph)
- `ExtractFallback.attempt(model, schema, summary)` runs a single fresh schema-constrained `Agent.run` with no tools or memory. Implements the paper Appendix B.2 fix for "model built the right answer in variables but never returned it" — when `RlmHarness` exits maxIterations without `submit()`, it summarizes the agent's message history and runs the fallback to reconstitute the typed output. Status flips from `SUBMITTED` to `EXTRACTED` so callers can distinguish
- `InputBindings` generates a JShell snippet that calls `HostBridge.getInput()` to retrieve the input fields as a `Map<String, Object>` and casts each top-level record field to its declared generic type rendered as Java source. **No Jackson reference appears in the snippet** — JSON conversion happens host-side, and JShell only sees `java.*` types plus `HostBridge`. This is what makes the binding work uniformly under both classpath and JPMS launches: under JPMS the parent's modulepath modules are invisible to JShell's internal javac, so any reference to `tools.jackson.*` (or any other non-`java.*` library) would fail to compile. The user's input class never enters the JShell namespace — accessibility-agnostic, supports package-private records. Simple types (anything in `java.*`) get full static typing; complex/user-defined types fall back to `Object` so the model can navigate via the underlying Map. Wiring: `RlmHarness.run` builds the input map via `MAPPER.convertValue(input, ...)` and registers it as a host function named `__getInput`
- `SandboxPrelude` installs a curated JShell preamble at sandbox boot: standard imports (`java.util.*`, `java.util.stream.*`, `java.util.function.*`, `java.io.*`, `java.math.*`, `java.time.*`, `Collectors`), free `print/println/printf` (PRINTING-equivalent), and ten script-style helpers (sum, sumInts, mean, max, min, join, filter, map, sorted, countBy). Lives at the sandbox layer so direct `CodeExecutionTool` users and `RlmHarness` both get the same surface. Replaces verbose Stream chains: `sum(numbers)` instead of `numbers.stream().mapToInt(Integer::intValue).sum()`
- **Budget header in execute_code tool result (1.1.4).** Each `execute_code` tool result is prefixed with `[budget: predicts=N/M]\n` so the model can self-regulate parallelism mid-trajectory. Conditional rendering: omitted entirely when `maxLlmCalls=0` (unlimited) or when `withBudgetHeader(false)` is set. Cited motivation: Prime Intellect's RLM blog observation that telling the model what budget remains lets it make smarter parallelism decisions. Tiny token cost (1 line × ~30 iterations); empirically meaningful behavioral lever.
- **Required predict signatures (1.1.4).** `RlmHarness.Builder.requiredPredictSignature(new RequiredPredictSignature("devils_advocate", instructionsText))` declares specialist `predict()` calls the model must invoke before stopping. Detection is exact equality on the `instructions` arg — not substring or fuzzy match — so users typically pass the same `Map<String, String>` value both as the strategy reference and as the registered signature. The harness's existing `requireSubmit` iteration hook now also names missing signatures in its corrective injection. Resolves the Pro 3.1 prompt-compliance failure mode where the model chronically skips devil's-advocate calls; structural lever beats prompt-only enforcement. `ReplSession.calledSignatures()` is exposed for lower-level callers building their own checks.
- **Skill envTips field (1.1.4).** `Skill` now carries a separate `envTips` text field rendered under a `## Strategy: <name>` header (Prime Intellect's pattern). Splits "what the skill IS" (instructions, rendered under `## Skill: <name>`) from "HOW to use the skill" (numbered protocol steps, A/B-testable). Empirical motivation: PI's data showed tips lift some models and hurt others — needs to be a per-skill knob. `Skill.merge` concatenates each skill's body and strategy in declaration order, with per-section headers; merged Skill's own `envTips` field is empty (folded into instructions) so the harness's flatten-into-strategy wire-up stays trivial.
- **Sandbox bindings listener (1.1.4).** `RlmHarness.Builder.sandboxBindingsListener(...)` (or lower-level `ReplConfig.Builder.withSandboxBindingsListener(...)`) observes the sandbox's working memory after each `execute_code`. Listener receives a `Map<String,String>` of every user-declared `var` (excluding `__`-prefixed harness internals), with each value's `toString` capped per-value (default 200 chars) and per-snapshot (default 16 KB). Implementation: bootstrap calls `JShell.variables()` + `JShell.varValue()` after each execute, ships the map back in `ExecutionResult.bindings()`, the session fires the listener synchronously. Default off — must opt in. Listener exceptions are caught and ignored. Use case: live "user watches the agent think" UI where `macro = "Fed paused, VIX=18.5"` lights up as the model binds variables across iterations; also useful for post-mortem debugging when truncated stdout isn't enough. Lives in helios-repl (not core SpanListener) — REPL-specific structural state shouldn't pollute the agent-loop listener interface.
- **`RlmResult.predictCalls()` and `calledHostFunctions()` (1.1.6).** Surfaces the trajectory data needed by downstream RLM evaluators on the result record itself, so metrics don't have to grep JShell source via `ExecutionResult.executedCode()`. `predictCalls` is a `List<PredictCall>` of every `predict()` invocation, each carrying `(instructions, input, iteration)` — `iteration` is the 0-indexed turn within `ReplSession` (binding snippet is iter 0, model's first execute_code is iter 1, etc.). `calledHostFunctions` is a `Map<String, Integer>` of per-name call counts for user-registered Skill tools; framework reserved names (`predict`, `submit`, `fetch`, `query`, `getInput`, `__getInput`, `__call`) are excluded so the map measures "data tool diversity" cleanly. Both fields are preserved on `FAILED` results so post-mortem debugging works on rejected trajectories. Built on a single per-host-function tracking wrapper installed by `ReplSession.create` (replaces the older predict-only wrapper); cost per non-predict call is one atomic increment.
- **`SignatureMatchers` utility class (1.1.6).** `EXACT` / `SUBSTRING` / `PREFIX` constants plus a `regex(Pattern)` factory for the common `withSignatureMatcher(...)` shapes. Eliminates the lambda every consumer was writing for paraphrasing-tolerant matching.
- **Post-loop required-signature check (1.1.5).** The 1.1.4 `requireSubmitHook` only fires when the model volunteers a STOP turn. Heavy tool-using models (notably Opus 4.7 in Kubera's first pilot) keep emitting `execute_code` until `maxIterations` and never trigger the hook. The post-loop check in `RlmHarness.run` after `agent.run()` returns: if `submit()` was called but a `RequiredPredictSignature` was never invoked, return `RlmResult.Status.FAILED` with the missing signature names in the error — rather than silently `SUBMITTED`. Pairs with two new debugging affordances: `withSignatureMatcher(BiPredicate<String,String>)` opt-out for paraphrasing models (default `String::equals`), and `ReplSession.predictInstructions()` exposing every recorded `predict()` call's instructions text for post-mortem comparison against registered signatures. The in-loop hook stays as-is for the optimistic case; the post-loop check is the safety net.
- **`ExecutionResult.executedCode` (1.1.5).** Every result carries the source code that ran. Captured parent-side from `ExecutionRequest.code()` in `JvmSandbox.toExecutionResult` — no protocol change. Per-call cap via `ReplConfig.withMaxExecutedCodeChars(int)` (default 5000) appends `... (len=N)` so consumers know the original length. Combined with the existing `bindings` field, gives live observers the *what reasoning produced this state* alongside *what state exists* — the substrate Kubera asked for to render code-and-bindings side-by-side in their live-UI panel.
- **Custom host functions are typed and JShell-callable.** Every non-reserved `HostFunction` registered before sandbox boot gets a typed JShell static wrapper synthesized into the sandbox preamble — `HostFunction("marketQuote", ..., [HostParameter.required("ticker", STRING, ...)], handler)` becomes callable as `marketQuote("AAPL")` from emitted Java code. The wrapper packs args into a `LinkedHashMap` keyed by parameter name and dispatches via `HostBridge.__call`. Synthesis lives parent-side in `SandboxPrelude.synthesizeCustomWrappers(registry)` (testable without subprocess); `JvmSandbox.create` sends the snippet to the bootstrap via the `installPrelude` RPC after subprocess boot but before any user execute. Reserved names (`predict`, `submit`, `fetch`, `query`, `getInput`, `__getInput`, `__call`) are skipped — hardcoded `HostBridge` static methods own those signatures. Synthesized wrappers always return `Object` (the synthesizer can't know the handler's return shape); the system prompt lists each signature with parameter descriptions so the model has explicit names + types in scope. Resolves the Kubera-reported "registered host functions are unreachable from JShell" blocker — pre-fetching everything into `KuberaInput` is no longer required, so the model can choose which slice of the world to look at.
- **Typed positional submit codegen was tried and rejected.** Generating `static void submit(int x, String y)` from the output schema looked ergonomic but cut integration determinism from 10/10 to 7/10 — Java's positional overloading lets the LLM put values in wrong slots. Map-based submit (explicit keys) stays. See `feedback/typed-submit-rejected` memory and `RlmSystemPrompt`'s "Map.of(...)" form. Rule: **LLM-facing API design — keys must be explicit, ambiguity is fatal.**

### RLM Pattern & Host Bridge Functions

The sandbox enables **RLM (Recursive Language Model)** patterns: code owns loops, math, and aggregation; the LLM owns judgment via `predict()` calls. Each `predict()` gets fresh context (system + user only) — no context rot across iterations.

**Sandbox API** (4 host bridge functions):

| Function | Purpose | Security Model |
|----------|---------|---------------|
| `predict(instructions, input)` | Call model with fresh context | Host controls which model, rate limits |
| `submit(output)` | Return structured final result | Single-call enforced via CAS |
| `query(sql, ...params)` | Read-only database query | Host holds credentials, enforces SELECT-only |
| `fetch(url, headers)` | HTTP GET via host | Host controls allowed domains, prevents SSRF |

**Why host bridge functions, not raw JDBC/HttpClient in sandbox:**
- Credentials never enter the sandbox process — no exfiltration risk
- Host enforces read-only queries (rejects INSERT/UPDATE/DELETE/DROP)
- Host controls HTTP scope (allowlisted domains only)
- API surface is 4 functions — LLM understands instantly without documentation
- Sandbox code stays pure: loops + math + predict() + query() + submit()

### Not Yet Implemented

- Container sandbox (Incus/Docker) for full Linux environments
- GraalVM polyglot sandbox (GraalJS) for in-process sandboxing with `allowIO(NONE)`

## Autoresearch Examples

Two reference modules exercise the `core/eval` primitives end-to-end. Neither is published to Maven Central — they are in-repo demonstrations that double as regression tests for the framework.

### `examples/autoresearch-prompt`

24 tests (23 unit + 1 integration). Iteratively optimizes a system prompt against a labeled dataset.

- `PromptOptimizer` — builder-driven runner. Wires an `Evaluator` behind an `Objective<String>`, an `InMemoryCheckpoint<String>` holding the best prompt, and a coach `Agent` with three tools.
- `PromptCoachTools` — `try_prompt(candidate, description, asi)`, `show_best()`, `show_log(limit)`. `try_prompt` evaluates, compares to best, auto-commits or auto-discards, and appends to the log.
- Integration test runs 10 coach iterations against Gemini with a 3-example yes/no dataset.

### `examples/autoresearch-code`

32 tests (all local, no external APIs). Pi-autoresearch-style source-code optimization.

- `GitWorkspace` implements `Checkpoint<String>` where snapshots are commit hashes. `snapshot()` → `git rev-parse HEAD`, `restore(hash)` → `git reset --hard`, plus `commit(msg)` and `discardWorkingChanges()`.
- `CodeCoachTools` — `read_file`, `write_file`, `run_experiment` (parses `METRIC name=value` from stdout), `log_experiment` (commits on keep, discards on discard/crash), `show_log`.
- `CodeAutoresearch` — builder-driven runner. The coach reads/writes files in a scoped allowlist, runs a user-supplied benchmark command, and logs decisions.
- Uses virtual threads for concurrent stdout/stderr draining to avoid pipe deadlocks.

### `examples/rlm-demo`

2 integration tests against Gemini Flash. End-to-end exercise of `RlmHarness` over the full RLM substrate: JvmSandbox subprocess, JShell evaluation, JSON-RPC bridge, predict() round-trip, canonical system prompt + scripting prelude + input bindings, typed submit validation, clean termination.

- `simpleStatsTaskReachesSubmittedStatus` — record input/output, no predict; validates the substrate end-to-end on a deterministic computation.
- `taskUsingPredictForJudgmentReachesSubmittedStatus` — exercises `predict()` for sentiment classification; validates the predict()-then-submit round-trip.
- Lives in `examples/` (not `repl/src/test`) because the test crosses two JPMS modules (`helios-repl` + `helios-gemini`) and we don't want a test-only `requires` polluting `helios-repl`'s production module declaration. Same pattern `autoresearch-prompt` uses.

### `examples/rlm-demo-jpms`

1 integration test against Gemini Flash. JPMS-mode regression test: a `module-info.java` with `requires ai.singlr.repl; requires ai.singlr.gemini;` forces surefire to launch the test JVM in named-module mode, the sandbox subprocess inherits `--module-path`, and any sandbox-side reference to a modulepath library would fail to compile under JShell. As of 1.1.2 the substrate has no such reference — `InputBindings` routes through `HostBridge.getInput()` — so this test exercises the same harness over the same task without needing a modulepath workaround. Kept as a regression guard so future contributors can't accidentally re-introduce a sandbox-side import of a non-`java.*` package.

### What These Demonstrate

- `Objective<C>` / `Checkpoint<C>` / `ExperimentLog` / `ConfidenceScorer` / `Metric` compose cleanly on two unrelated domains (prompts, code) without framework changes
- No framework loop class — the "autoresearch loop" is an `Agent` + a few user tools + the primitives
- Durable JSONL log survives context resets — agents can resume by reading the log's ASI

## Next Steps

1. **Container Sandbox** - Incus/Docker sandbox for arbitrary tool installation
2. **GraalVM Sandbox** - GraalJS `Sandbox` implementation for production-grade in-process isolation
3. **Session Persistence** - Database abstraction (PostgreSQL, SQLite)
4. **Knowledge** - Vector DB integration for semantic archival search
