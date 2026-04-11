# Helios

Production-grade agentic framework for Java. Simple, explicit, no magic.

Published to [Maven Central](https://central.sonatype.com/namespace/ai.singlr) under the `ai.singlr` namespace.

## Requirements

- Java 25+
- Maven 3.9+

## Modules

Pick what you need — each jar is published independently:

| Artifact | What it gives you | External deps |
|----------|-------------------|---------------|
| `helios-core` | Agent, Teams, Memory, Tools, Fault Tolerance, Workflows, Tracing, Structured Output | None |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3.x |
| `helios-openai` | OpenAI GPT provider (Responses API) | Jackson 3.x |
| `helios-onnx` | Local embedding models via ONNX Runtime (Nomic, Gemma) | ONNX Runtime, DJL Tokenizers, Jackson 3.x |
| `helios-persistence` | PostgreSQL-backed Memory, PromptRegistry, and TraceStore | Helidon DbClient |

Most applications need `helios-core` + one provider. Add `helios-onnx` if you need local vector embeddings. Add `helios-persistence` for database-backed memory, prompt management, and trace storage.

## Installation

Add to your `pom.xml` (replace `${helios.version}` with the [latest release](https://central.sonatype.com/namespace/ai.singlr)):

```xml
<!-- Core — always required -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-core</artifactId>
    <version>${helios.version}</version>
</dependency>

<!-- Pick one (or more) LLM providers -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-gemini</artifactId>
    <version>${helios.version}</version>
</dependency>
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-anthropic</artifactId>
    <version>${helios.version}</version>
</dependency>
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-openai</artifactId>
    <version>${helios.version}</version>
</dependency>

<!-- ONNX embeddings — for local vector embeddings -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-onnx</artifactId>
    <version>${helios.version}</version>
</dependency>

<!-- PostgreSQL persistence — for memory, prompt versioning, and trace storage -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-persistence</artifactId>
    <version>${helios.version}</version>
</dependency>
```

For JPMS, add to your `module-info.java`:

```java
requires ai.singlr.core;
requires ai.singlr.gemini;       // if using Gemini
requires ai.singlr.anthropic;    // if using Claude
requires ai.singlr.openai;       // if using GPT
requires ai.singlr.onnx;         // if using ONNX embeddings
requires ai.singlr.persistence;  // if using persistence
```

## Quick Start

### Configure a Provider

Each provider needs an API key. Pass it via `ModelConfig`:

```java
var config = ModelConfig.of("your-api-key");
```

**Gemini** — get a key at [aistudio.google.com](https://aistudio.google.com/apikey)

```java
var config = ModelConfig.of(System.getenv("GEMINI_API_KEY"));
var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
```

**Anthropic** — get a key at [console.anthropic.com](https://console.anthropic.com/settings/keys)

```java
var config = ModelConfig.of(System.getenv("ANTHROPIC_API_KEY"));
var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
```

**OpenAI** — get a key at [platform.openai.com](https://platform.openai.com/api-keys)

```java
var config = ModelConfig.of(System.getenv("OPENAI_API_KEY"));
var model = new OpenAIModel(OpenAIModelId.GPT_4_1_MINI, config);
```

All providers implement the same `Model` interface, so the rest of your code is provider-agnostic. You can also fine-tune the config:

```java
var config = ModelConfig.newBuilder()
    .withApiKey(System.getenv("GEMINI_API_KEY"))
    .withTemperature(0.7)
    .withMaxOutputTokens(8192)
    .withThinkingLevel(ThinkingLevel.MEDIUM)  // for reasoning models
    .build();
```

### Run an Agent

```java
var agent = new Agent(AgentConfig.newBuilder()
    .withName("assistant")
    .withModel(model)
    .withSystemPrompt("You are a helpful assistant.")
    .build());

// Quick — get the value or throw
var response = agent.run("What is the capital of France?").getOrThrow();
System.out.println(response.content());

// Or pattern match for full control
switch (agent.run("What is the capital of France?")) {
    case Result.Success<Response>(var r) -> System.out.println(r.content());
    case Result.Failure<Response>(var error, var cause) -> System.err.println(error);
}
```

### Define Tools

```java
var weatherTool = Tool.newBuilder()
    .withName("get_weather")
    .withDescription("Gets the current weather for a city")
    .withParameter(ToolParameter.newBuilder()
        .withName("city")
        .withType(ParameterType.STRING)
        .withDescription("City name")
        .withRequired(true)
        .build())
    .withExecutor(args -> {
        var city = (String) args.get("city");
        return ToolResult.success("72°F and sunny in " + city);
    })
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withName("weather-bot")
    .withModel(model)
    .withTool(weatherTool)
    .build());
```

### Memory

Letta-inspired two-tier memory: core blocks (always in context) and archival (long-term storage).

```java
var memory = new InMemoryMemory();
memory.putBlock(MemoryBlock.of("user_profile", Map.of(
    "name", "Alice",
    "preferences", "concise answers"
)));

var agent = new Agent(AgentConfig.newBuilder()
    .withName("assistant")
    .withModel(model)
    .withMemory(memory)
    .build());
```

The agent automatically gets memory tools (`memory_update`, `memory_read`) and can self-edit its memory during conversations. Memory blocks enforce a `maxSize` limit — the agent self-corrects when an update would exceed it.

### Structured Output

```java
record Sentiment(String label, double confidence) {}

Response<Sentiment> response = model.chat(messages, OutputSchema.of(Sentiment.class));
Sentiment sentiment = response.parsed();
```

> **Gemini nesting limit:** Gemini's structured output enforces a maximum schema nesting depth. Deeply nested records (e.g., object → array → object → array → object) may be rejected with a 400 error. Flatten your schema if you hit this — prefer `List<String>` over `List<SomeRecord>` at the deepest levels.

### Streaming

```java
var events = model.chatStream(messages, tools);
while (events.hasNext()) {
    switch (events.next()) {
        case StreamEvent.TextDelta d -> System.out.print(d.text());
        case StreamEvent.ToolCallComplete tc -> System.out.println("Called: " + tc.toolCall().name());
        case StreamEvent.Done d -> System.out.println("\nDone: " + d.response().content());
        case StreamEvent.Error e -> System.err.println(e.message());
        default -> {}
    }
}
```

The streaming iterator implements `Closeable` — if you stop iterating early, cast and close to release the underlying connection:

```java
var events = model.chatStream(messages, List.of());
try {
    // process some events, then bail out
} finally {
    if (events instanceof java.io.Closeable c) c.close();
}
```

### Embeddings

Local vector embeddings via ONNX Runtime. Models are downloaded from HuggingFace on first use and cached locally.

```java
// Create an embedding model — the provider knows the model's dimensions and settings
try (var model = EmbeddingProvider.resolve(OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {

    // Embed text
    var result = model.embed("A man is eating food.");
    float[] embedding = result.getOrThrow(); // 768-dim vector

    // Query vs document embeddings (some models use different prefixes)
    var queryEmb = model.embedQuery("eating food").getOrThrow();
    var docEmb = model.embedDocument("A man is eating food.").getOrThrow();

    // Batch embedding
    var batch = model.embedBatch(new String[]{"text one", "text two"}).getOrThrow();
}
```

Or use the provider directly:

```java
var provider = new OnnxEmbeddingProvider();
try (var model = provider.create(OnnxModelId.EMBEDDING_GEMMA_300M.id(), EmbeddingConfig.defaults())) {
    var embedding = model.embedDocument("A software engineer building AI apps.").getOrThrow();
}
```

Supported models:

| Model | Enum | Type | Dimension |
|-------|------|------|-----------|
| nomic-ai/nomic-embed-text-v1.5 | `OnnxModelId.NOMIC_EMBED_V1_5` | Encoder | 768 |
| onnx-community/embeddinggemma-300m-ONNX | `OnnxModelId.EMBEDDING_GEMMA_300M` | Decoder | 768 |

### Fault Tolerance

Zero-dependency retry, circuit breaker, and timeout — composable and built-in.

```java
var ft = FaultTolerance.newBuilder()
    .withRetry(RetryPolicy.newBuilder()
        .withMaxAttempts(3)
        .withBackoff(Backoff.exponential(Duration.ofMillis(500), 2.0))
        .build())
    .withCircuitBreaker(CircuitBreaker.newBuilder()
        .withFailureThreshold(5)
        .withHalfOpenAfter(Duration.ofSeconds(30))
        .build())
    .withOperationTimeout(Duration.ofMinutes(5))
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withName("resilient-agent")
    .withModel(model)
    .withFaultTolerance(ft)
    .build());
```

## Teams

Multi-agent delegation — a leader agent orchestrates worker agents exposed as tools.

```java
var researcher = new Agent(AgentConfig.newBuilder()
    .withName("researcher")
    .withModel(model)
    .withSystemPrompt("You are a research specialist. Find and synthesize information.")
    .withTool(searchTool)
    .build());

var writer = new Agent(AgentConfig.newBuilder()
    .withName("writer")
    .withModel(model)
    .withSystemPrompt("You are a technical writer. Write clear, engaging content.")
    .build());

var team = Team.newBuilder()
    .withName("content-team")
    .withModel(model)
    .withSystemPrompt("You lead a content creation team. Delegate to the right specialist.")
    .withWorker("researcher", "Finds and synthesizes information from multiple sources", researcher)
    .withWorker("writer", "Writes polished, engaging content from research notes", writer)
    .withTool(publishTool)  // leader's own direct tools
    .build();

// Same API as Agent
Result<Response> result = team.run("Write a blog post about Java virtual threads");
```

The leader model sees workers as regular tools (each with a `task` string parameter). It delegates naturally — call `researcher`, pass results to `writer`, review, call `publish`. Worker failures surface as `ToolResult.failure` so the leader can self-correct.

### Parallel Workers

When the leader calls multiple workers in a single response, they can run concurrently:

```java
var team = Team.newBuilder()
    .withName("research-team")
    .withModel(model)
    .withWorker("market", "Market data analysis", marketAnalyst)
    .withWorker("geopolitical", "Geopolitical risk analysis", geoAnalyst)
    .withWorker("macro", "Macroeconomic trend analysis", macroAnalyst)
    .withParallelToolExecution(true)  // workers run concurrently on virtual threads
    .build();
```

The same flag works on any agent with multiple tools — not just teams:

```java
var agent = new Agent(AgentConfig.newBuilder()
    .withName("multi-tool-agent")
    .withModel(model)
    .withTool(searchTool)
    .withTool(calculatorTool)
    .withTool(weatherTool)
    .withParallelToolExecution(true)
    .build());
```

When the model returns multiple tool calls in one response, they execute concurrently on virtual threads. Results are returned in call order. Each tool catches its own fault tolerance exceptions independently — one timeout doesn't abort the others.

### Agent.asTool()

Wrap any agent as a tool for use in another agent. Each invocation creates a fresh agent — safe for concurrent use:

```java
var searchAgent = AgentConfig.newBuilder()
    .withName("searcher")
    .withModel(model)
    .withTool(webSearchTool)
    .withSystemPrompt("Search the web and return a concise summary.")
    .build();

var tool = Agent.asTool("search", "Searches the web for information", searchAgent);

// Use it in any agent
var agent = new Agent(AgentConfig.newBuilder()
    .withName("assistant")
    .withModel(model)
    .withTool(tool)
    .build());
```

The tool accepts a single `task` string parameter. The sub-agent runs to completion and returns its response content as the tool result.

### Deep Research Pattern

Combine parallel workers, iterative refinement, and `Agent.asTool()` for deep research. The planner model IS the orchestrator — no custom control flow needed:

```java
// Specialist agents — each with their own tools and system prompts
var marketAgent = new Agent(AgentConfig.newBuilder()
    .withName("market-analyst")
    .withModel(model)
    .withSystemPrompt("You analyze market data, pricing trends, and competitive dynamics.")
    .withTool(marketDataTool)
    .withTool(pricingTool)
    .build());

var geoAgent = new Agent(AgentConfig.newBuilder()
    .withName("geopolitical-analyst")
    .withModel(model)
    .withSystemPrompt("You analyze geopolitical risks, sanctions, and regulatory changes.")
    .withTool(newsSearchTool)
    .withTool(regulatoryDbTool)
    .build());

var macroAgent = new Agent(AgentConfig.newBuilder()
    .withName("macro-analyst")
    .withModel(model)
    .withSystemPrompt("You analyze macroeconomic indicators, central bank policy, and fiscal trends.")
    .withTool(econDataTool)
    .build());

// Planner spawns all three in parallel, reviews, identifies gaps, re-queries, synthesizes
var team = Team.newBuilder()
    .withName("deep-research")
    .withModel(reasoningModel)  // use a reasoning model for the planner
    .withSystemPrompt("""
        You are a senior research analyst. Conduct thorough multi-source research:
        1. Dispatch parallel queries to all relevant specialists
        2. Review results — identify gaps, contradictions, and weak areas
        3. Send targeted follow-up queries to fill gaps
        4. Synthesize a comprehensive final report with citations
        """)
    .withWorker("market", "Market data and competitive analysis", marketAgent)
    .withWorker("geopolitical", "Geopolitical risk and regulatory analysis", geoAgent)
    .withWorker("macro", "Macroeconomic trends and indicators", macroAgent)
    .withParallelToolExecution(true)
    .withMaxIterations(15)                                    // absolute ceiling
    .withMinIterations(3)                                     // force research depth
    .withRequiredTools("market", "geopolitical", "macro")     // every specialist must be consulted
    .build();

Result<Response> report = team.run("Analyze the impact of US tariff policy on semiconductor supply chains");
```

The planner model naturally follows the research-reflect-refine loop. On the first turn it calls all three workers in parallel. On subsequent turns it reviews, identifies gaps ("the market analysis didn't cover TSMC's capex plans"), sends targeted follow-ups, and eventually synthesizes a final report. No custom orchestration code — the model's reasoning drives the iteration.

#### Research Guardrails

`AgentConfig` has two declarative guardrails that prevent shallow research on smaller models:

- **`withMinIterations(n)`** — requires at least N loops before the agent may complete. If the model tries to stop early, the agent injects a `USER` guidance message ("[system guidance] You have completed X of N required research iterations. Continue investigating...") and loops again.
- **`withRequiredTools(...)`** — names tools that must be called at least once. If the model tries to stop without calling every required tool, the agent injects a `USER` guidance message naming the missing tools and loops again.

Both guardrails respect `maxIterations` as an absolute ceiling. Injected messages carry metadata `helios.injected=minIterations` or `requiredTools` so they are identifiable in traces and persistence.

#### Iteration Hook

For programmatic completion control beyond declarative guardrails, use `withIterationHook`:

```java
var team = Team.newBuilder()
    .withName("deep-research")
    .withModel(reasoningModel)
    .withWorker("market", "...", marketAgent)
    .withWorker("geopolitical", "...", geoAgent)
    .withWorker("macro", "...", macroAgent)
    .withMaxIterations(15)
    .withIterationHook(ctx -> {
        // fires only when the model wants to stop and built-in guardrails are satisfied
        if (ctx.totalToolCallCount() < 6) {
            return IterationAction.inject(
                "You've only called %d tools. Dig deeper — cross-reference sources."
                    .formatted(ctx.totalToolCallCount()));
        }
        if (!ctx.toolsCalledSoFar().contains("macro")) {
            return IterationAction.inject("You haven't consulted the macro specialist yet.");
        }
        return IterationAction.allow();
    })
    .build();
```

`IterationContext` exposes `iteration()`, `maxIterations()`, `minIterations()`, `requiredTools()`, `toolsCalledSoFar()`, `totalToolCallCount()`, `lastResponse()`, and an immutable `messages()` snapshot. The hook returns one of:

- `IterationAction.allow()` — permit completion (semantic: "no objection")
- `IterationAction.stop()` — force completion (semantic: "I decided this is done")
- `IterationAction.inject(message)` — force another iteration with the given user guidance

Hook exceptions are caught and surface as `Result.failure` — they never propagate raw to the caller. The hook respects `maxIterations` as an absolute ceiling; an `inject` on the last iteration will loop once more, hit the ceiling, and fail.

## Workflows

Composable orchestration primitives for multi-step pipelines.

### Step Types

| Step | Description |
|------|-------------|
| `Step.agent(name, agent)` | Runs an Agent |
| `Step.function(name, fn)` | Runs an arbitrary function |
| `Step.sequential(name, steps...)` | Runs steps in order, fail-fast |
| `Step.parallel(name, steps...)` | Runs steps concurrently on virtual threads |
| `Step.condition(name, predicate, ifStep, elseStep)` | If/else branching |
| `Step.loop(name, predicate, body, maxIterations)` | While-loop with guard |
| `Step.fallback(name, steps...)` | Tries alternatives until one succeeds |

### Example: Support Pipeline

```java
var classifier = new Agent(AgentConfig.newBuilder()
    .withName("classifier")
    .withModel(model)
    .build());

var responder = new Agent(AgentConfig.newBuilder()
    .withName("responder")
    .withModel(model)
    .build());

var workflow = Workflow.newBuilder("support-pipeline")
    .withStep(Step.agent("classify", classifier))
    .withStep(Step.condition("route",
        ctx -> ctx.lastResult().content().contains("urgent"),
        Step.agent("urgent-response", responder),
        Step.agent("standard-response", responder)))
    .build();

Result<StepResult> result = workflow.run("My order hasn't arrived");
```

### Example: Parallel Research

```java
var workflow = Workflow.newBuilder("research")
    .withStep(Step.parallel("gather",
        Step.agent("analyst-1", analyst),
        Step.agent("analyst-2", analyst),
        Step.agent("analyst-3", analyst)))
    .withStep(Step.agent("synthesize", synthesizer))
    .build();
```

### Example: Retry with Fallback

```java
var workflow = Workflow.newBuilder("reliable-query")
    .withStep(Step.fallback("try-models",
        Step.agent("primary", primaryAgent),
        Step.agent("backup", backupAgent)))
    .build();
```

## Tracing

Built-in observability with trace listeners.

```java
var workflow = Workflow.newBuilder("traced-pipeline")
    .withStep(Step.agent("classify", classifier))
    .withTraceListener(traceStore)
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withName("traced-agent")
    .withModel(model)
    .withTraceListener(traceStore)
    .build());
```

When the model returns grounding citations (e.g. from Gemini Google Search), the `model.chat` span automatically records:

- `groundingCitationCount` — the number of citations returned
- `groundingSources` — deduplicated, `www.`-stripped, comma-separated domains

This makes it trivial to verify from trace data whether an agent actually used search grounding.

## Persistence

PostgreSQL-backed implementations of Memory, PromptRegistry, and TraceListener. All three classes accept a shared `PgConfig` that carries the `DbClient`, the schema name (defaults to `public`), and an optional agent ID.

### Schema Setup

Helios ships a `schema.sql` on the classpath at `ai/singlr/persistence/schema.sql`. Run it against your database to create the `helios_*` tables.

**Default schema** (tables go into `public`):

```bash
psql -d mydb -f schema.sql
```

**Custom schema** (e.g., `lg`):

```sql
CREATE SCHEMA IF NOT EXISTS lg;
SET search_path TO lg;
\i schema.sql
```

Or in a single migration file:

```sql
CREATE SCHEMA IF NOT EXISTS lg;

SET search_path TO lg;

CREATE TABLE IF NOT EXISTS helios_prompts ( ... );
-- rest of schema.sql
```

### Configuration

```java
// Default schema (public) — no schema prefix applied to SQL
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .build();

// Custom schema — all SQL is qualified as lg.helios_*
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .withSchema("lg")
    .build();

// With agent scoping (required for PgMemory)
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .withSchema("lg")
    .withAgentId("my-agent")
    .build();
```

### Usage

```java
// Prompt versioning
var prompts = new PgPromptRegistry(pgConfig);
prompts.register("greeting", "Hello {name}!");
var prompt = prompts.resolve("greeting");

// Trace storage
var traces = new PgTraceStore(pgConfig);
var agent = new Agent(AgentConfig.newBuilder()
    .withName("my-agent")
    .withModel(model)
    .withTraceListener(traces)
    .build());

// Persistent memory (requires agentId in PgConfig)
var memory = new PgMemory(pgConfig);
var agent = new Agent(AgentConfig.newBuilder()
    .withName("my-agent")
    .withModel(model)
    .withMemory(memory)
    .build());
```

## Architecture

```
ai.singlr.core/
├── agent/      Agent, AgentConfig, AgentState, Team, ContextCompactor, TokenEstimator
├── common/     Result<T>, Ids (UUID v7), Strings, HttpClientFactory
├── embedding/  EmbeddingModel, EmbeddingProvider, EmbeddingConfig
├── fault/      Backoff, RetryPolicy, CircuitBreaker, FaultTolerance
├── memory/     Memory, InMemoryMemory, MemoryBlock, MemoryTools
├── model/      Model, ModelProvider, ModelConfig, Message, Response, StreamEvent
├── prompt/     Prompt, PromptRegistry
├── schema/     SchemaGenerator, JsonSchema, OutputSchema
├── tool/       Tool, ToolParameter, ToolExecutor, ToolResult
├── trace/      TraceBuilder, TraceListener, Span, SpanKind
└── workflow/   Workflow, Step, StepResult, StepContext

ai.singlr.gemini/
├── GeminiModel, GeminiProvider, GeminiModelId
└── api/        Interactions API DTOs

ai.singlr.anthropic/
├── AnthropicModel, AnthropicProvider, AnthropicModelId
└── api/        Messages API DTOs

ai.singlr.openai/
├── OpenAIModel, OpenAIProvider, OpenAIModelId
└── api/        Responses API DTOs

ai.singlr.onnx/
├── OnnxEmbeddingProvider, OnnxEmbeddingModel, OnnxModelId
└── (internal)  OnnxModelDownloader, OnnxModelSpec

ai.singlr.persistence/
├── PgConfig           (Shared config: DbClient, schema, agentId)
├── PgMemory           (PostgreSQL-backed Memory)
├── PgPromptRegistry   (PostgreSQL-backed PromptRegistry)
└── PgTraceStore       (PostgreSQL-backed TraceStore)
```

### Design Principles

- **Records everywhere** — immutable data, pattern matching, no boilerplate
- **Sealed types** — `Result<T>`, `Step`, `StreamEvent` are exhaustive
- **Static factory methods** — `Result.success()`, `Step.agent()`, `ModelConfig.of()`
- **Builder pattern** — `with` prefix, copy constructors for modification
- **JPMS modules** — proper encapsulation, ServiceLoader SPI for providers

## Building

```bash
mvn package
```

### Code Formatting

Uses [google-java-format](https://github.com/google/google-java-format) via Spotless (2-space indent).

```bash
mvn spotless:apply   # auto-format
mvn spotless:check   # verify formatting
```

## License

[MIT](LICENSE)
