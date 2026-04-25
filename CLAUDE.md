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
    └── autoresearch-code/          # Reference: code optimization via git Checkpoint (not published)
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
| **Fault Tolerance** | Zero-deps: Backoff, RetryPolicy, CircuitBreaker, FaultTolerance |
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

## Core Module: COMPLETE ✓

1079 tests, 97%+ instruction coverage on existing packages; 91% / 87% on `eval` package.

```
ai.singlr.core/
├── agent/     AgentConfig, AgentState, Agent, AgentStreamIterator, Team, ContextCompactor, TokenEstimator,
               IterationHook, IterationAction, IterationContext
├── common/    Result<T>, Strings, HttpClientFactory, Ids (UUID v7 + UTC timestamps)
├── eval/      Metric, Example, Score, Objective, Checkpoint, InMemoryCheckpoint,
               ExperimentEntry, ExperimentLog, InMemoryExperimentLog, FileExperimentLog (JSONL),
               ConfidenceScorer (MAD-based), Evaluator, EvalResult, ExampleResult
├── fault/     Backoff, RetryPolicy, CircuitBreaker, FaultTolerance
├── memory/    MemoryBlock, Memory, InMemoryMemory, MemoryTools
├── model/     Message, Response, Model, ModelProvider, ModelConfig, ToolCall, ToolChoice,
               StreamEvent, FinishReason, Role, ThinkingLevel, Citation
├── prompt/    Prompt, PromptRegistry, InMemoryPromptRegistry
├── schema/    JsonSchema, OutputSchema, SchemaGenerator
├── tool/      Tool, ToolParameter, ToolExecutor, ToolResult, ParameterType
├── trace/     Trace, Span, SpanKind, Annotation, TraceListener, CollectingTraceListener,
               TraceBuilder, SpanBuilder, SpanContainer (sealed: TraceBuilder | SpanBuilder)
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

128 tests. Uses **Messages API** (`POST https://api.anthropic.com/v1/messages`).

- **API Docs**: https://docs.anthropic.com/en/api/messages

```
ai.singlr.anthropic/
├── AnthropicModelId   # Enum: CLAUDE_OPUS_4_6, CLAUDE_SONNET_4_6
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
- Thinking mapped to budget_tokens: NONE→null, MINIMAL→1024, LOW→4096, MEDIUM→10000, HIGH→32000
- Metadata keys: `anthropic.thinking`, `anthropic.thinkingSignature`

## OpenAI Module: COMPLETE ✓

183 tests. Uses **Responses API** (`POST https://api.openai.com/v1/responses`).

- **API Docs**: https://platform.openai.com/docs/api-reference/responses

```
ai.singlr.openai/
├── OpenAIModelId      # Enum: GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO, GPT_4_1, GPT_4_1_MINI, GPT_4_1_NANO, GPT_4O, GPT_4O_MINI, O3, O4_MINI
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

83 tests. PostgreSQL via Helidon DbClient + TestContainers.

```
ai.singlr.persistence/
├── PgConfig           # Shared config: DbClient, schema, agentId
├── PgMemory           # Memory impl — archival, session history, session registry
├── PgPromptRegistry   # PromptRegistry impl — versioned prompts
├── PgTraceStore       # TraceListener impl — traces, spans, annotations
├── PgException        # Unchecked exception wrapper
├── sql/               # SQL constants: PromptSql, TraceSql, SpanSql, AnnotationSql, ArchiveSql, MessageSql, SessionSql
└── mapper/            # Row mappers: PromptMapper, TraceMapper, SpanMapper, AnnotationMapper, ArchiveMapper, MessageMapper, JsonbMapper, DbTypeMapperProvider
```

## REPL Module: IN PROGRESS

350 tests, 95% instruction / 94% branch coverage. Sandboxed code execution for **RLM (Recursive Language Model)** patterns.

```
ai.singlr.repl/
├── CodeExecutionTool      # Static factory → Tool (like MemoryTools/Agent.asTool)
├── ReplConfig             # Record + Builder: sandbox factory, timeout, host fns
├── ReplSession            # Session lifecycle (AutoCloseable), execution history, semaphore
├── ReplException          # Unchecked exception wrapper
├── sandbox/
│   ├── Sandbox            # Interface: execute(), isAlive(), close()
│   ├── SandboxFactory     # @FunctionalInterface: (HostFunctionRegistry) → Sandbox
│   ├── ExecutionRequest    # Record + Builder: code, language, timeout
│   ├── ExecutionResult     # Record + Builder: stdout, stderr, exitCode, submitted
│   ├── JvmSandbox         # JVM subprocess impl + RPC channel
│   ├── JvmSandboxConfig   # Record + Builder: timeouts, heap size
│   ├── JvmSandboxBootstrap # JShell subprocess entry point (stdin/stdout JSON-RPC)
│   └── HostBridge         # Static bridge: predict() and submit() for sandbox code
├── host/
│   ├── HostFunction       # Record: name, description, handler
│   ├── HostFunctionHandler # @FunctionalInterface: (Map) → Object
│   ├── HostFunctionRegistry # Mutable registry, freezable
│   ├── PredictFunction    # Factory: predict() backed by Model.chat() with fresh context
│   ├── SubmitFunction     # Factory: submit() for final output signal
│   ├── QueryFunction      # Factory: query() backed by DataSource (read-only SQL)
│   └── FetchFunction      # Factory: fetch() backed by HttpClient (domain allowlist)
└── protocol/
    ├── RpcMessage         # Sealed: Request, Response, ErrorResponse, Notification
    ├── RpcError           # Record: code, message, data (JSON-RPC 2.0 error codes)
    ├── RpcTransport       # Interface: send/receive over any channel
    ├── ProcessTransport   # stdin/stdout NDJSON with \0RPC: magic prefix
    └── RpcChannel         # Bidirectional dispatcher (virtual thread reader loop)
```

### Key Design Decisions

- JSON-RPC 2.0 over NDJSON for host-sandbox communication
- `\0RPC:` magic prefix distinguishes RPC lines from regular stdout
- Sandbox exceptions returned as `ToolResult.success()` so model sees tracebacks and self-corrects
- `PredictFunction` calls `Model.chat()` with fresh context (system + user only) — prevents context rot
- `SubmitFunction` uses `AtomicReference.compareAndSet` for single-call enforcement
- `ReplSession` uses `Semaphore.tryAcquire()` for max concurrent sessions
- `HostFunctionRegistry.freeze()` prevents modifications after sandbox startup
- `JvmSandboxBootstrap` enforces single-execute with `Semaphore(1)` — `System.setOut`/`setErr` are JVM-global, concurrent evals would corrupt streams
- Host bridge functions route all external access through the host process — sandbox never holds credentials or raw connections
- `QueryFunction` takes a `javax.sql.DataSource`, enforces read-only via first-keyword allowlist + `connection.setReadOnly(true)`
- `FetchFunction` takes an `HttpClient` + domain allowlist, HTTPS-only, prevents SSRF

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

### What These Demonstrate

- `Objective<C>` / `Checkpoint<C>` / `ExperimentLog` / `ConfidenceScorer` / `Metric` compose cleanly on two unrelated domains (prompts, code) without framework changes
- No framework loop class — the "autoresearch loop" is an `Agent` + a few user tools + the primitives
- Durable JSONL log survives context resets — agents can resume by reading the log's ASI

## Next Steps

1. **Container Sandbox** - Incus/Docker sandbox for arbitrary tool installation
2. **GraalVM Sandbox** - GraalJS `Sandbox` implementation for production-grade in-process isolation
3. **Session Persistence** - Database abstraction (PostgreSQL, SQLite)
4. **Knowledge** - Vector DB integration for semantic archival search
