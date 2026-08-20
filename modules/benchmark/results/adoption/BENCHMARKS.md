# BENCHMARKS

_Generated from result `run-1787168664268` (schema v1, profile `smoke`) at 2026-08-19T19:46:45.800381Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

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
| excalidraw | false | 31.7s | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 250,526 (min 250,526, max 250,526, n=1) | 250,992 (min 250,992, max 250,992, n=1) |
| Input tokens | 248,163 (min 248,163, max 248,163, n=1) | 248,451 (min 248,451, max 248,451, n=1) |
| Output tokens | 2,363 (min 2,363, max 2,363, n=1) | 2,541 (min 2,541, max 2,541, n=1) |
| Tool calls | 11 (min 11, max 11, n=1) | 10 (min 10, max 10, n=1) |
| File reads | 4 (min 4, max 4, n=1) | 4 (min 4, max 4, n=1) |
| Wall-clock | 33.9s (min 33.9s, max 33.9s, n=1) | 35.7s (min 35.7s, max 35.7s, n=1) |
| Cost | $0.2781 (min $0.2781, max $0.2781, n=1) | $0.2246 (min $0.2246, max $0.2246, n=1) |
| Accuracy | 83.3% (min 83.3%, max 83.3%, n=1) | 83.3% (min 83.3%, max 83.3%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

## Per-Repo Results

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 250,526 (min 250,526, max 250,526, n=1) | 250,992 (min 250,992, max 250,992, n=1) |
| Input tokens | 248,163 (min 248,163, max 248,163, n=1) | 248,451 (min 248,451, max 248,451, n=1) |
| Output tokens | 2,363 (min 2,363, max 2,363, n=1) | 2,541 (min 2,541, max 2,541, n=1) |
| Tool calls | 11 (min 11, max 11, n=1) | 10 (min 10, max 10, n=1) |
| File reads | 4 (min 4, max 4, n=1) | 4 (min 4, max 4, n=1) |
| Wall-clock | 33.9s (min 33.9s, max 33.9s, n=1) | 35.7s (min 35.7s, max 35.7s, n=1) |
| Cost | $0.2781 (min $0.2781, max $0.2781, n=1) | $0.2246 (min $0.2246, max $0.2246, n=1) |
| Accuracy | 83.3% (min 83.3%, max 83.3%, n=1) | 83.3% (min 83.3%, max 83.3%, n=1) |
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
| excalidraw | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.0535 (WITH_TOOLS costs the same or more per question) |

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

