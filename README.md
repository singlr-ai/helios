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
| `helios-core` | Agent, Memory, Tools, Fault Tolerance, Workflows, Tracing, Structured Output | None |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-onnx` | Local embedding models via ONNX Runtime (Nomic, Gemma) | ONNX Runtime, DJL Tokenizers, Jackson 3.x |
| `helios-persistence` | PostgreSQL-backed Memory, PromptRegistry, and TraceStore | Helidon DbClient |

Most applications need `helios-core` + one provider (e.g., `helios-gemini`). Add `helios-onnx` if you need local vector embeddings. Add `helios-persistence` for database-backed memory, prompt management, and trace storage.

## Installation

Add to your `pom.xml` (replace `${helios.version}` with the [latest release](https://central.sonatype.com/namespace/ai.singlr)):

```xml
<!-- Core — always required -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-core</artifactId>
    <version>${helios.version}</version>
</dependency>

<!-- Gemini provider — for LLM chat, streaming, tool calling -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-gemini</artifactId>
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
requires ai.singlr.onnx;         // if using ONNX embeddings
requires ai.singlr.persistence;  // if using persistence
```

## Quick Start

### Create a Model

```java
var config = ModelConfig.of("your-api-key");
var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
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

The agent automatically gets memory tools (core_memory_get, core_memory_update, archival_memory_insert, archival_memory_search, etc.) and can self-edit its memory during conversations.

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
├── agent/      Agent, AgentConfig, AgentState
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
