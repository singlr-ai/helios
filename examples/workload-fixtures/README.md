# helios-workload-fixtures

Reference workload fixtures suite — three representative agent workloads that exercise the framework's load-bearing seams against a real LLM. The point is not to publish a benchmark; the point is to give *us* data when we are about to make a design decision.

Spec: `docs/specs/06-workload-fixtures.md` (in-repo, gitignored).

## What's covered (v1)

| Fixture | Shape | Harness | What it stresses |
|---|---|---|---|
| `numeric-stats` | fully-`java.*` input (`List<Double>` + `String`) | CodeActHarness | Floor case — bindings are perfectly typed, every iteration cost is intrinsic |
| `user-typed-sdtm` | nested user-defined records (`List<SourceFile>`, `List<FieldInference>`) | CodeActHarness | 1.5.2 hybrid input-binding regression test — `recoveryIterations` climbing means the binding regressed |
| `classification` | one-shot text → structured 3-class label | bare Agent + OutputSchema | Validates the suite works for non-REPL harnesses |

Four more fixtures (`tool-heavy`, `large-input`, `predict-heavy`, `multi-turn`) ship in a follow-up.

## Running

```
export GEMINI_API_KEY=...
mvn -pl examples/workload-fixtures exec:java \
  -Dexec.mainClass=ai.singlr.examples.fixtures.SuiteRunner \
  -Dexec.args="--providers gemini --reps 1 --out reports/$(date +%Y-%m-%d-%H%M)"
```

Args:

| Flag | Default | Meaning |
|---|---|---|
| `--providers` | `gemini` | Comma-sep provider list. v1 supports `gemini` only |
| `--fixtures` | (all) | Comma-sep fixture slugs; pick subset for quick passes |
| `--reps` | `1` | Repetitions per (fixture × provider) cell. Use `3` for variance numbers |
| `--out` | `reports/<timestamp>` | Output directory; will be created |
| `--baseline` | (none) | Path to a prior `pass.jsonl` to render delta-vs-baseline columns |

## Outputs

Each pass writes two files under `--out`:

- `pass.jsonl` — one JSON line per (fixture, model, attempt) tuple. Stable hand-rolled schema; load it into any tool that reads JSONL.
- `pass.md` — markdown summary table per fixture, plus a regressions section when `--baseline` is supplied.

Baseline data is **not** committed to this repo — store passes wherever you want and point `--baseline` at the prior file when comparing.

## Smoke test

```
export RUN_FIXTURES=true GEMINI_API_KEY=...
mvn test -pl examples/workload-fixtures
```

Runs one fixture, one rep, against Gemini Flash. Validates the harness shape end-to-end without spending real money on the full matrix. Gated by `RUN_FIXTURES` so default `mvn test` from the repo root does NOT trigger real-API calls.

## When to run a pass

- Before cutting a release — confirms no metric regressed vs the prior pass.
- Before / after any change that touches the agent loop, REPL substrate, or input binding — the suite tells us whether the change paid off.
- When evaluating a new optimization (e.g. JShell pooling, mini-OSGi class exposure) — first run a baseline, apply the change, re-run, compare.

Per the framework's "evidence-driven design" stance: any new spec proposal that affects trajectory cost should be accompanied by a fixtures pass showing it actually moves the metric it claims to move.
