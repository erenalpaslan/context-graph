# BENCHMARKS

_Generated from result `run-1787321997453` (schema v1, profile `full`) at 2026-08-21T15:18:17.941611Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

## Methodology

Every question runs in two arms that differ in exactly one way (AC-7): whether the agent has ContextGraph's MCP tools available.

- **WITH_TOOLS** -- the agent has ContextGraph's MCP server available, working in a copy of the repo that has already been indexed (`.contextgraph/graph.db` present).
- **WITHOUT_TOOLS** -- the control arm, representing a project ContextGraph has never touched. This is **not** the same agent with tools switched off: it runs in a separate, independently checked-out working copy of the same repo at the same pinned SHA that has never been indexed and carries no ContextGraph artefact at all (`.contextgraph/`, `graph.db`, `graph.local.db`, `GRAPH_REPORT.md`, `graph.html`) -- verified artefact-free before every run (AC-7a). Two working copies exist because ContextGraph writes its graph into the project root itself (`GraphDb.baseline(root)` resolves to `root/.contextgraph/graph.db`) and that path cannot be relocated outside the checkout, so an indexed checkout cannot also serve as its own control.

Everything else is identical between arms: same model, same system prompt, same question text, same budget.

| | |
|---|---|
| Agent backend | `claude-code` |
| Measurement | `FORCED` |
| Agent model | `claude-sonnet-5` |
| Judge model | `claude-opus-5` |
| Budget per run | unlimited -- no per-run cap was set |
| Repeats per (question, arm) | 1 |

### ContextGraph tool usage (WITH_TOOLS arm)

| | |
|---|---|
| ContextGraph tool calls | 12 |
| Runs that called it at least once | 5 / 5 |


## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 1,802,055 (min 363,777, max 9,128,711, n=5) | 1,902,942 (min 515,769, max 5,916,388, n=5) |
| Input tokens | 1,741,964 (min 345,472, max 9,075,218, n=5) | 1,878,866 (min 498,816, max 5,872,432, n=5) |
| Output tokens | 52,183 (min 18,305, max 60,091, n=5) | 24,076 (min 15,976, max 43,956, n=5) |
| Tool calls | 40 (min 8, max 73, n=5) | 38 (min 15, max 74, n=5) |
| File reads | 24 (min 2, max 47, n=5) | 7 (min 1, max 23, n=5) |
| Wall-clock | 7.9 min (min 2.4 min, max 9.2 min, n=5) | 3.9 min (min 2.3 min, max 8.1 min, n=5) |
| Cost | $2.0341 (min $0.6010, max $4.5394, n=5) | $1.2941 (min $0.6162, max $3.1807, n=5) |
| Accuracy | 95.8% (min 89.1%, max 99.3%, n=5) | 98.5% (min 93.5%, max 99.3%, n=5) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 5 / 5 / 0 / 3 | 5 / 5 / 0 / 2 |

## Per-Repo Results

### Keycloak (`keycloak`)

Measured at tag `26.7.1`, commit `73f08b397f193712b26d317210dce99898129709`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 1,802,055 (min 363,777, max 9,128,711, n=5) | 1,902,942 (min 515,769, max 5,916,388, n=5) |
| Input tokens | 1,741,964 (min 345,472, max 9,075,218, n=5) | 1,878,866 (min 498,816, max 5,872,432, n=5) |
| Output tokens | 52,183 (min 18,305, max 60,091, n=5) | 24,076 (min 15,976, max 43,956, n=5) |
| Tool calls | 40 (min 8, max 73, n=5) | 38 (min 15, max 74, n=5) |
| File reads | 24 (min 2, max 47, n=5) | 7 (min 1, max 23, n=5) |
| Wall-clock | 7.9 min (min 2.4 min, max 9.2 min, n=5) | 3.9 min (min 2.3 min, max 8.1 min, n=5) |
| Cost | $2.0341 (min $0.6010, max $4.5394, n=5) | $1.2941 (min $0.6162, max $3.1807, n=5) |
| Accuracy | 95.8% (min 89.1%, max 99.3%, n=5) | 98.5% (min 93.5%, max 99.3%, n=5) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 5 / 5 / 0 / 3 | 5 / 5 / 0 / 2 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

_Not run yet -- no negative-control agent runs recorded for this result._

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 0

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 5 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 5 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 5 | 3 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 5 | 2 |

## Judge Validation

The judge (`claude-opus-5`) scores every answer against that question's gold key-facts, blind to which arm produced the answer -- no tool trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact hit/miss and the accuracy score derived from it, not free text (AC-13).

Judge scoring failures (this answer could not be scored and is excluded from the accuracy statistics above, same as a contaminated run is excluded from the totals above; not fatal on its own -- only every answer failing aborts the run): 0

**Kappa validation was not run for this result.** No second judge model re-scored a subsample, so there is no inter-rater agreement percentage or Cohen's kappa to report here.

## Ingest Cost & Break-Even

Indexing cost is recorded separately from query-time cost (AC-2). Break-even answers: after how many questions does the WITH_TOOLS arm's lower per-question cost pay back what indexing cost up front (AC-21)?

_No indexing recorded for this result._

## Reproduction

Reproduce this result end to end, same profile as this run:

```bash
./gradlew :modules:benchmark:run --args="--profile full"
```

Corpus repos measured, pinned to the SHA shown under "Per-Repo Results" above:

- `Keycloak` (`keycloak`) -- https://github.com/keycloak/keycloak.git @ `26.7.1` (`73f08b397f193712b26d317210dce99898129709`)

Fast local iteration on the harness itself uses the smoke profile instead (1 repo x 3 questions x 1 repeat per arm):

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.

