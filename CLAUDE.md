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
├── core/          # Zero deps - Interfaces, Agent, Memory, Tools, Fault Tolerance
├── gemini/        # Gemini Interactions API + Jackson 3.x
├── anthropic/     # Claude Messages API + Jackson 3.x
├── openai/        # OpenAI Responses API + Jackson 3.x
├── persistence/   # PostgreSQL persistence - Helidon DbClient
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
| **Streaming** | `runStream()` returns `CloseableIterator<StreamEvent>` — virtual thread + blocking queue + iterator pattern. `StreamEvent` sealed: TextDelta, ToolCallStart, ToolCallDelta, ToolCallComplete, Done, Error |
| **Fault Tolerance** | Zero-deps: Backoff, RetryPolicy, CircuitBreaker, FaultTolerance |

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

916 tests, 98% instruction / 93% branch coverage.

```
ai.singlr.core/
├── agent/     AgentConfig, AgentState, Agent, AgentStreamIterator, Team, ContextCompactor, TokenEstimator
├── common/    Result<T>, Strings, HttpClientFactory, Ids (UUID v7 + UTC timestamps)
├── fault/     Backoff, RetryPolicy, CircuitBreaker, FaultTolerance
├── memory/    MemoryBlock, Memory, InMemoryMemory, MemoryTools
├── model/     Message, Response, Model, ModelProvider, ModelConfig, ToolCall, ToolChoice,
               StreamEvent, FinishReason, Role, ThinkingLevel, Citation
├── prompt/    Prompt, PromptRegistry, InMemoryPromptRegistry
├── schema/    JsonSchema, OutputSchema, SchemaGenerator
├── tool/      Tool, ToolParameter, ToolExecutor, ToolResult, ParameterType
├── trace/     Trace, Span, SpanKind, Annotation, TraceListener, TraceBuilder, SpanBuilder
└── workflow/  Step (sealed), Workflow, StepResult, StepContext, AgentStep, FunctionStep,
               Sequential, Parallel, Condition, Loop, Fallback
```

## Gemini Module: COMPLETE ✓

94 tests. Uses **Interactions API** (not legacy generateContent).

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

### Not Yet Implemented

- Multimodal input (image, audio, video, document)
- Google Search / Code Execution tools
- Safety settings

## Anthropic Module: COMPLETE ✓

125 tests. Uses **Messages API** (`POST https://api.anthropic.com/v1/messages`).

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

180 tests. Uses **Responses API** (`POST https://api.openai.com/v1/responses`).

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

## Next Steps

1. **Session Persistence** - Database abstraction (PostgreSQL, SQLite)
2. **Knowledge** - Vector DB integration for semantic archival search
