# BENCHMARKS

_Generated from result `run-1787168812645` (schema v1, profile `smoke`) at 2026-08-19T19:49:51.984841Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

## Methodology

Every question runs in two arms that differ in exactly one way (AC-7): whether the agent has ContextGraph's MCP tools available.

- **WITH_TOOLS** -- the agent has ContextGraph's MCP server available, working in a copy of the repo that has already been indexed (`.contextgraph/graph.db` present).
- **WITHOUT_TOOLS** -- the control arm, representing a project ContextGraph has never touched. This is **not** the same agent with tools switched off: it runs in a separate, independently checked-out working copy of the same repo at the same pinned SHA that has never been indexed and carries no ContextGraph artefact at all (`.contextgraph/`, `graph.db`, `graph.local.db`, `GRAPH_REPORT.md`, `graph.html`) -- verified artefact-free before every run (AC-7a). Two working copies exist because ContextGraph writes its graph into the project root itself (`GraphDb.baseline(root)` resolves to `root/.contextgraph/graph.db`) and that path cannot be relocated outside the checkout, so an indexed checkout cannot also serve as its own control.

Everything else is identical between arms: same model, same system prompt, same question text, same tool-call ceiling.

| | |
|---|---|
| Agent model | `claude-sonnet-5` |
| Judge model | `claude-opus-5` |
| Tool-call ceiling | 40 (same in both arms) |
| Repeats per (question, arm) | 1 |

Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):

| Repo | `litellm.enabled` | Duration | Tokens | Cost |
|---|---|---|---|---|
| excalidraw | false | 31.3s | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 460,382 (min 460,382, max 460,382, n=1) | 302,400 (min 302,400, max 302,400, n=1) |
| Input tokens | 456,083 (min 456,083, max 456,083, n=1) | 299,372 (min 299,372, max 299,372, n=1) |
| Output tokens | 4,299 (min 4,299, max 4,299, n=1) | 3,028 (min 3,028, max 3,028, n=1) |
| Tool calls | 17 (min 17, max 17, n=1) | 12 (min 12, max 12, n=1) |
| File reads | 8 (min 8, max 8, n=1) | 5 (min 5, max 5, n=1) |
| Wall-clock | 1.1 min (min 1.1 min, max 1.1 min, n=1) | 45.4s (min 45.4s, max 45.4s, n=1) |
| Cost | $0.3906 (min $0.3906, max $0.3906, n=1) | $0.2593 (min $0.2593, max $0.2593, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

## Per-Repo Results

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 460,382 (min 460,382, max 460,382, n=1) | 302,400 (min 302,400, max 302,400, n=1) |
| Input tokens | 456,083 (min 456,083, max 456,083, n=1) | 299,372 (min 299,372, max 299,372, n=1) |
| Output tokens | 4,299 (min 4,299, max 4,299, n=1) | 3,028 (min 3,028, max 3,028, n=1) |
| Tool calls | 17 (min 17, max 17, n=1) | 12 (min 12, max 12, n=1) |
| File reads | 8 (min 8, max 8, n=1) | 5 (min 5, max 5, n=1) |
| Wall-clock | 1.1 min (min 1.1 min, max 1.1 min, n=1) | 45.4s (min 45.4s, max 45.4s, n=1) |
| Cost | $0.3906 (min $0.3906, max $0.3906, n=1) | $0.2593 (min $0.2593, max $0.2593, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

_Not run yet -- no negative-control agent runs recorded for this result._

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 0

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 1 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 1 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 1 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 1 | 0 |

## Judge Validation

The judge (`claude-opus-5`) scores every answer against that question's gold key-facts, blind to which arm produced the answer -- no tool trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact hit/miss and the accuracy score derived from it, not free text (AC-13).

Judge scoring failures (this answer could not be scored and is excluded from the accuracy statistics above, same as a contaminated run is excluded from the totals above; not fatal on its own -- only every answer failing aborts the run): 0

**Kappa validation was not run for this result.** No second judge model re-scored a subsample, so there is no inter-rater agreement percentage or Cohen's kappa to report here.

## Ingest Cost & Break-Even

Indexing cost is recorded separately from query-time cost (AC-2). Break-even answers: after how many questions does the WITH_TOOLS arm's lower per-question cost pay back what indexing cost up front (AC-21)?

| Repo | Ingest cost | Break-even |
|---|---|---|
| excalidraw | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.1314 (WITH_TOOLS costs the same or more per question) |

## Reproduction

Reproduce this result end to end, same profile as this run:

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

Corpus repos measured, pinned to the SHA shown under "Per-Repo Results" above:

- `Excalidraw` (`excalidraw`) -- https://github.com/excalidraw/excalidraw.git @ `v0.18.1` (`a2ec2889babf7d2295469c6d90ebe77fae57df84`)

Fast local iteration on the harness itself uses the smoke profile instead (1 repo x 3 questions x 1 repeat per arm):

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.

