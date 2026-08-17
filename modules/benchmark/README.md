# `:modules:benchmark`

Measures the agent-facing benefit of ContextGraph's MCP tools: the same
question, same model, run once with ContextGraph's MCP server available and
once without, comparing token/tool-call/time/cost and answer accuracy. See
`agent-team/specs/benchmark-suite/spec.md` for the full spec and acceptance
criteria (AC-1..AC-22, AC-10a).

This module is not depended on by anything else in the project — it is a
leaf, on purpose (see the spec's non-goals: no CI gate, no regression
threshold blocks a build).

## Package map

Slice 01 (this skeleton) owns `model` and `cli`. Everything else below is
**reserved territory** for the slice that owns it — put new code for that
concern under that package so two slices working in this worktree at the
same time don't collide on the same files.

| Package | Owner (slice) | Concern |
|---|---|---|
| `io.contextgraph.benchmark.model` | 01 | The whole-run domain model and its JSON serialization (`BenchmarkRun` and everything it's built from). This is the contract — read it, don't fork it. Changes here ripple into every other package. |
| `io.contextgraph.benchmark.cli` | 01 (skeleton), 12 (wiring) | The `--profile smoke\|full` entry point. Slice 01 leaves it deliberately unwired; slice 12 is the only slice that should add orchestration calls here. |
| `io.contextgraph.benchmark.corpus` | 02 | Pinned repo clone/verify, `litellm.enabled=false` indexing, ingest metrics → `IngestRecord`. |
| `io.contextgraph.benchmark.questions` | 03 | Question set data format (YAML/JSON), loader, validator (evidence format, 3-6 facts, category distribution, duplicate ids) → `Question`/`GoldFact`. |
| `io.contextgraph.benchmark.runner` | 04 | Single (question, arm) agent run: WITH_TOOLS vs WITHOUT_TOOLS, contamination guard (sanitized PATH + PreToolUse hook), tool-call ceiling, metric collection → `AgentRunRecord`. |
| `io.contextgraph.benchmark.judge` | 05 | Blind scoring of an answer against gold facts, kappa validation mode → `JudgeScore`/`FactScore`. |
| `io.contextgraph.benchmark.stats` | 06 | Aggregation across repeats (median + min/max), contamination/ceiling exclusion accounting, break-even calculation. Pure functions, no I/O. |
| `io.contextgraph.benchmark.report` | 07 | `BENCHMARKS.md` generation from slice 06's summary. Formatting only — no new calculations. |
| (question data files) | 08-11 | Repo-specific question sets — one data file per repo (Excalidraw, gin, cal.com, Keycloak), *not* Kotlin source. Don't put these under `questions`' test fixtures; that's slice 03's test data, not the real gold sets. |
| `io.contextgraph.benchmark.orchestrator` (new) | 12 | Ties 02-07 together behind the two profiles. Slice 01 does not create this package — it's slice 12's to add. |

## Model contract (owned by slice 01)

`BenchmarkRun` is the versioned root (`schemaVersion` field, see
`BenchmarkRun.SCHEMA_VERSION`) that every other slice writes fields onto and
reads fields from:

- `CorpusRepo` — pinned repo + tag/SHA (02 writes).
- `Question` / `GoldFact` / `Evidence` — question set (03 writes; `Evidence`
  is a mandatory `file:line` value, unrepresentable without one — see
  `Evidence.parse`).
- `IngestRecord` — per-repo indexing cost, kept separate from query metrics
  (02 writes).
- `AgentRunRecord` — one (question, arm, repeat) measurement (04 writes).
- `JudgeScore` / `FactScore` — blind scoring output, one or more per run,
  keyed by `judgeModel` for the kappa validation mode (05 writes).
- `BenchmarkConfig` / `ModelConfig` — tool-call ceiling, repeats per arm,
  agent/judge model. Configuration, not constants: profiles vary these,
  nothing in the suite should hard-code 40, 4, or a model name directly.

If you (a downstream slice) find you need a field that isn't here: add it to
the relevant `model` class rather than smuggling the data through a side
channel — a field missing from this contract is a six-file change later,
per the task 01 brief. Keep the addition minimal and additive (new field
with a default, so old result JSON still round-trips).

## Running

```bash
./gradlew :modules:benchmark:build              # compiles and runs unit tests only
./gradlew :modules:benchmark:run --args="--profile smoke"   # writes an (currently empty) result JSON
```

`./gradlew build` / `./gradlew check` at the repo root never execute the
benchmark (AC-22) — the `application` plugin's `run` task is not wired into
either lifecycle.
