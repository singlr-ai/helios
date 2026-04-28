# Helios

Helios is an agentic runtime for the JVM. It provides a provider-agnostic model interface, multi-agent delegation, sandboxed code execution, structured output, and end-to-end tracing — primitives for building agents that decompose problems and run reliably in production.

Simple, explicit, no magic. No annotation-driven DI, no reflective surprises.

Published to [Maven Central](https://central.sonatype.com/namespace/ai.singlr) under the `ai.singlr` namespace. MIT licensed.

## Requirements

- Java 25+
- Maven 3.9+

## Modules

Pick what you need — each jar is published independently:

| Artifact | What it gives you | External deps |
|----------|-------------------|---------------|
| `helios-core` | Agent, Teams, Memory, Tools, Workflows, Tracing, Structured Output, Fault Tolerance | None |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3.x |
| `helios-openai` | OpenAI GPT provider (Responses API) | Jackson 3.x |
| `helios-repl` | Sandboxed code execution (RLM pattern) | Jackson 3.x |
| `helios-onnx` | Local embeddings via ONNX Runtime | ONNX Runtime, DJL Tokenizers |
| `helios-persistence` | PostgreSQL-backed Memory, PromptRegistry, TraceStore | Helidon DbClient |

Most applications need `helios-core` + one provider.

## Installation

```xml
<dependency>
  <groupId>ai.singlr</groupId>
  <artifactId>helios-core</artifactId>
  <version>${helios.version}</version>
</dependency>
<dependency>
  <groupId>ai.singlr</groupId>
  <artifactId>helios-anthropic</artifactId>  <!-- or -gemini, -openai -->
  <version>${helios.version}</version>
</dependency>
```

JPMS:

```java
requires ai.singlr.core;
requires ai.singlr.anthropic;
```

## Quick Start

```java
var model = new AnthropicModel(
    AnthropicModelId.CLAUDE_SONNET_4_6,
    ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

var agent = new Agent(AgentConfig.newBuilder()
    .withName("assistant")
    .withModel(model)
    .withSystemPrompt("You are a helpful assistant.")
    .build());

var response = agent.run("What is the capital of France?").getOrThrow();
System.out.println(response.content());
```

All providers implement the same `Model` interface — swap providers without touching the rest of your code.

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived resources (HTTP connection pool, file descriptors). It is designed to be **built once at app startup, shared across many Agents, and closed once at app shutdown**. `Agent` itself is per-request and stateless — closing an Agent would break other Agents that share the same Model, so `Agent` is intentionally not `AutoCloseable`. The creator of the `Model` owns its lifecycle.

```java
try (var model = new AnthropicModel(CLAUDE_SONNET_4_6, ModelConfig.of(apiKey))) {
  var config = AgentConfig.newBuilder().withModel(model).build();
  // build many Agents, run many requests
  for (var input : inputs) {
    new Agent(config).run(input);
  }
} // HttpClient and connection pool released here
```

`ReplSession` is also `AutoCloseable` — it owns its sandbox subprocess and must be closed by the caller.

## Tools

```java
var weatherTool = Tool.newBuilder()
    .withName("get_weather")
    .withDescription("Current weather for a city")
    .withParameter(ToolParameter.newBuilder()
        .withName("city").withType(ParameterType.STRING).withRequired(true).build())
    .withExecutor(args -> ToolResult.success("72°F in " + args.get("city")))
    .build();
```

`AgentConfig.withParallelToolExecution(true)` runs multiple tool calls concurrently on virtual threads; results are preserved in call order and each tool catches its own fault-tolerance exceptions so one timeout doesn't abort the others.

## Structured Output

```java
record Sentiment(String label, double confidence) {}

Response<Sentiment> response = model.chat(messages, OutputSchema.of(Sentiment.class));
Sentiment s = response.parsed();
```

## Memory

Letta-inspired two-tier memory: core blocks (always in context) plus archival. Agents get two tools (`memory_update`, `memory_read`) and can self-edit during conversations. `maxSize` on blocks is enforced; the agent self-corrects when an update would exceed it.

```java
var memory = new InMemoryMemory();
memory.putBlock(MemoryBlock.of("user_profile", Map.of("name", "Alice")));

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model).withMemory(memory).build());
```

## Streaming

```java
var events = model.chatStream(messages, tools);
while (events.hasNext()) {
  switch (events.next()) {
    case StreamEvent.TextDelta d -> System.out.print(d.text());
    case StreamEvent.ToolCallComplete tc -> System.out.println("Called: " + tc.toolCall().name());
    case StreamEvent.Done d -> System.out.println("\n" + d.response().content());
    default -> {}
  }
}
```

The iterator is `Closeable` — cast and close to bail out early and release the underlying connection.

## Fault Tolerance

Composable retry, circuit breaker, and timeout, zero dependencies:

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
    .withModel(model).withFaultTolerance(ft).build());
```

## Tracing

```java
var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model).withTraceListener(traceStore).build());
```

`model.chat` spans record token usage, finish reason, and grounding citations (deduplicated, `www.`-stripped domains) when the model returns any. Attach the same listener to a `Workflow` for end-to-end traces across multi-step pipelines.

## Multi-Agent Delegation

Teams let a leader agent orchestrate workers exposed as tools. The leader sees workers as regular tools with a `task` parameter — no custom orchestration code:

```java
var team = Team.newBuilder()
    .withName("content-team")
    .withModel(model)
    .withSystemPrompt("Delegate to the right specialist.")
    .withWorker("researcher", "Find and synthesize information", researcher)
    .withWorker("writer", "Write polished content from research notes", writer)
    .withParallelToolExecution(true)   // concurrent dispatch
    .withMinIterations(3)              // force research depth
    .withRequiredTools("researcher")   // must consult at least once
    .build();

Result<Response> result = team.run("Write a post about Java virtual threads");
```

`Agent.asTool(name, desc, config)` wraps any agent as a standalone tool — a fresh agent is instantiated per call, so it's thread-safe.

**Guardrails.** `withMinIterations(n)` and `withRequiredTools(...)` inject `USER` guidance messages (tagged `helios.injected`) when the model tries to stop early. `withIterationHook(ctx -> ...)` gives programmatic control — the hook returns `IterationAction.allow() / stop() / inject(msg)`. `maxIterations` is always the ceiling.

### Team vs RLM — choosing the right paradigm

`Team` and `RlmHarness` (see [Sandboxed Code Execution](#sandboxed-code-execution)) are both first-class orchestration primitives. They solve different problem shapes; pick by the data flow:

| Use **Team** when... | Use **RLM** when... |
|---|---|
| Workers do bounded sub-tasks and the leader synthesizes their findings | The model needs many sequential decisions over a large or growing intermediate state |
| Worker outputs are small (\< 2 KB) and benefit from re-entering the leader's context | Intermediate results are large; printing them into transcript would cause context blowup |
| You want parallel fan-out (multiple workers concurrently) | Work is naturally serial — each step depends on the previous |
| 2–10 workers, 1–3 iterations of leader synthesis | Tens to hundreds of `predict()` calls slicing context held in REPL variables |

**Multi-stage RLM is just chained calls.** No framework abstraction needed — call `rlm.run(input)` then feed its output to a second harness. The composition lives in your code.

A common smell: a Team worker that returns a 50KB string blows up the leader's context. That worker should be an `RlmHarness` invoked directly, not a Team worker.

## Workflows

Composable orchestration primitives for multi-step pipelines:

| Step | Description |
|------|-------------|
| `Step.agent(name, agent)` | Runs an Agent |
| `Step.function(name, fn)` | Runs an arbitrary function |
| `Step.sequential(name, steps...)` | In order, fail-fast |
| `Step.parallel(name, steps...)` | Concurrent on virtual threads |
| `Step.condition(name, predicate, then, else)` | If/else |
| `Step.loop(name, predicate, body, max)` | While-loop with guard |
| `Step.fallback(name, steps...)` | Try alternatives until one succeeds |

```java
var workflow = Workflow.newBuilder("support")
    .withStep(Step.agent("classify", classifier))
    .withStep(Step.condition("route",
        ctx -> ctx.lastResult().content().contains("urgent"),
        Step.agent("urgent", responder),
        Step.agent("standard", responder)))
    .build();
```

## Sandboxed Code Execution

The `helios-repl` module runs Java code in a JVM subprocess sandbox, brokering access to the host via a small set of host functions. This enables the **RLM (Recursive Language Model) pattern**: code owns loops, math, and aggregation; the LLM owns judgment via `predict()` calls with fresh context.

### One-line entrypoint: `RlmHarness`

For most RLM tasks you don't need to assemble the substrate by hand. `RlmHarness` bundles `ReplSession`, `CodeExecutionTool`, `PredictFunction`, typed `submit`, the canonical system prompt template, and the `ExtractFallback` recovery path:

```java
record Input(String query, List<String> documents) {}
record Output(String answer, List<String> sources, int totalCount) {}

var rlm = RlmHarness.builder(Input.class, Output.class)
    .model(rootModel)
    .subModel(subModel)               // backs predict(); defaults to root model
    .sandboxFactory(JvmSandbox.factory())
    .strategy("Answer the query using the provided documents. Cite sources.")
    .maxIterations(30)                // outer agent budget
    .maxLlmCalls(50)                  // cumulative predict() budget per session
    .build();

RlmResult<Output> result = rlm.run(new Input("what is helios?", docs));
switch (result.status()) {
  case SUBMITTED -> /* model called submit() cleanly */;
  case EXTRACTED -> /* loop hit max iterations; output reconstituted from trajectory */;
  case FAILED    -> /* see result.error() */;
}
```

The harness is a thin assembly over the primitives below — drop down any time it's too narrow.

### Building blocks

```java
var session = ReplSession.create(
    ReplConfig.newBuilder()
        .withSandboxFactory(JvmSandbox.factory())
        .withHostFunction(PredictFunction.create(model))
        .withHostFunction(QueryFunction.create(dataSource))
        .withHostFunction(FetchFunction.create(httpClient, Set.of("api.example.com")))
        .withSubmitSchema(OutputSchema.of(Output.class))   // typed submit + validation
        .withMaxOutputCharsToModel(5000)                   // truncate stdout shown to model
        .withMaxLlmCalls(50)                               // predict() budget
        .build(),
    new Semaphore(50));

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withTool(CodeExecutionTool.create(session))
    .build());
```

Sandbox API — four host bridge functions:

| Function | Purpose | Security |
|----------|---------|----------|
| `predict(instructions, input)` | Call model with fresh context | Host controls which model; per-session call budget |
| `submit(output)` | Return structured final result | Single-call enforced; validates against `OutputSchema` if configured |
| `query(sql, ...params)` | Read-only database query | Host holds credentials; SELECT/WITH/EXPLAIN only |
| `fetch(url)` | HTTP GET via host | HTTPS-only, domain allowlist, no redirects, IP literals rejected, response size-capped |

Credentials never enter the sandbox. Each `predict()` gets a fresh context (system + user only) — no context rot across iterations. Variables persist across `execute_code` calls; printed output is truncated when shown to the model so long predict results stay in sandbox variables instead of bloating the transcript.

### Skills — composable capability bundles

```java
var pdfSkill = new Skill("pdf",
    "Use parsePdf(bytes) to extract text from PDF byte arrays.",
    List.of(parsePdfFunction));

var rlm = RlmHarness.builder(Input.class, Output.class)
    .model(model)
    .sandboxFactory(JvmSandbox.factory())
    .skill(pdfSkill)
    .build();
```

`Skill.merge(List)` raises if two skills register the same tool name, so accidental shadowing surfaces at composition time.

## Evaluation & Autoresearch

Helios ships domain-agnostic primitives for batch evaluation and iterative optimization in `ai.singlr.core.eval`.

| Primitive | Purpose |
|---|---|
| `Metric<E, A>` | `score(expected, actual, trace) → double` — functional interface; expected and actual types are independent so criteria-shape scoring (expected = descriptor, actual = produced output) doesn't need to fake a single type |
| `Example<I, O>` | Labeled input/expected pair |
| `Evaluator` | Run an `AgentConfig` over a `List<Example>` on virtual threads, collect traces + scores |
| `Objective<C>` | Score a candidate from a user-defined search space |
| `Checkpoint<C>` | `snapshot()` / `restore(snapshot)` — keep/discard mechanics |
| `ExperimentLog` | Append-only JSONL log (`InMemoryExperimentLog`, `FileExperimentLog`) |
| `ConfidenceScorer` | MAD-based noise-floor score; returns `null` before 3 entries |

```java
var evaluator = Evaluator.<String, String>newBuilder()
    .withAgentConfig(config)
    .withDataset(examples)
    .withMetric(Metric.exactMatch())
    .withParallelism(8)
    .build();

EvalResult<String, String> result = evaluator.run();
```

**Autoresearch** — the pattern of "LLM proposes a candidate, objective scores it, keep improvements, discard regressions, persist the decision" — is the minimum set of primitives above plus an agent + a few user-written tools. The framework does not ship the loop as a class; it ships the pieces, and two reference modules show how to compose them:

- **`examples/autoresearch-prompt`** — optimize a system prompt against a labeled dataset. `Checkpoint<String>` = `InMemoryCheckpoint`, `Objective<String>` = `Evaluator` over the dataset.
- **`examples/autoresearch-code`** — pi-autoresearch-style source-code optimization with a git-backed `Checkpoint<String>`, a shell benchmark `Objective`, and five coach tools (`read_file`, `write_file`, `run_experiment`, `log_experiment`, `show_log`).

Both examples sit on the same five primitives — proof that the abstraction is domain-agnostic. Neither module is published; they're reference implementations.

## Embeddings

Local vector embeddings via ONNX Runtime. Models download from HuggingFace on first use and are cached locally.

```java
try (var model = EmbeddingProvider.resolve(
    OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {
  float[] vector = model.embed("A man is eating food.").getOrThrow();
}
```

Supported: `NOMIC_EMBED_V1_5` (768-dim encoder), `EMBEDDING_GEMMA_300M` (768-dim decoder).

## Persistence

PostgreSQL-backed `Memory`, `PromptRegistry`, and `TraceListener`. All three share a `PgConfig` carrying the `DbClient`, schema name, and optional agent ID.

```java
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .withSchema("lg")
    .withAgentId("my-agent")
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withMemory(new PgMemory(pgConfig))
    .withTraceListener(new PgTraceStore(pgConfig))
    .build());
```

Schema lives on the classpath at `ai/singlr/persistence/schema.sql` — run it against your database to create the `helios_*` tables. Optional custom schema prefix is applied to all generated SQL.

## Design Principles

- **No magic** — explicit wiring, no annotation-driven DI.
- **Records everywhere** — immutable data, pattern matching, sealed types (`Result<T>`, `Step`, `StreamEvent`).
- **Builder pattern** — `with` prefix, static `newBuilder()` factory.
- **JPMS modules** — proper encapsulation, ServiceLoader SPI for providers.
- **Production from day 1** — fault tolerance, tracing, and ~2300 tests across modules with 95%+ instruction coverage on shipped code.

## Building

```bash
mvn package
mvn spotless:apply   # auto-format (Google Java Format, 2-space indent)
```

## License

[MIT](LICENSE)
