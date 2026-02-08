# Helios

Production-grade agentic framework for Java. Simple, explicit, no magic.

## Requirements

- Java 25+
- Maven 3.9+

## Modules

Pick what you need:

| Module | Artifact | Description |
|--------|----------|-------------|
| Core | `ai.singlr:helios-core` | Agent, Memory, Tools, Fault Tolerance, Workflows. Zero external dependencies. |
| Gemini | `ai.singlr:helios-gemini` | Google Gemini provider (Interactions API) |
| Persistence | `ai.singlr:helios-persistence` | PostgreSQL-backed PromptRegistry and TraceStore |

## Installation

Add to your `pom.xml`:

```xml
<!-- Core only -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Gemini provider -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-gemini</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- PostgreSQL persistence -->
<dependency>
    <groupId>ai.singlr</groupId>
    <artifactId>helios-persistence</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For JPMS, add to your `module-info.java`:

```java
requires ai.singlr.core;
requires ai.singlr.gemini;       // if using Gemini
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

## Architecture

```
ai.singlr.core/
├── agent/     Agent, AgentConfig, AgentState
├── common/    Result<T>, Ids (UUID v7), Strings, HttpClientFactory
├── fault/     Backoff, RetryPolicy, CircuitBreaker, FaultTolerance
├── memory/    Memory, InMemoryMemory, MemoryBlock, MemoryTools
├── model/     Model, ModelProvider, ModelConfig, Message, Response, StreamEvent
├── prompt/    Prompt, PromptRegistry
├── schema/    SchemaGenerator, JsonSchema, OutputSchema
├── tool/      Tool, ToolParameter, ToolExecutor, ToolResult
├── trace/     TraceBuilder, TraceListener, Span, SpanKind
└── workflow/  Workflow, Step, StepResult, StepContext

ai.singlr.gemini/
├── GeminiModel, GeminiProvider, GeminiModelId
└── api/       Interactions API DTOs

ai.singlr.persistence/
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
