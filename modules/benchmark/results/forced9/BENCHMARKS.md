# BENCHMARKS

_Generated from result `run-1787205364433` (schema v1, profile `full`) at 2026-08-20T06:10:04.024488Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

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
| ContextGraph tool calls | 42 |
| Runs that called it at least once | 9 / 9 |


Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):

| Repo | `litellm.enabled` | Duration | Tokens | Cost |
|---|---|---|---|---|
| excalidraw | false | 34.0s | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 240,190.5 (min 102,797, max 613,956, n=8) | 139,371.5 (min 63,891, max 350,149, n=8) |
| Input tokens | 238,126.5 (min 101,540, max 609,753, n=8) | 137,512 (min 63,079, max 346,743, n=8) |
| Output tokens | 2,516.5 (min 1,257, max 4,203, n=8) | 1,859.5 (min 812, max 3,430, n=8) |
| Tool calls | 10.5 (min 6, max 20, n=8) | 7 (min 4, max 15, n=8) |
| File reads | 3.5 (min 1, max 7, n=8) | 3 (min 0, max 5, n=8) |
| Wall-clock | 35.0s (min 16.7s, max 1.1 min, n=8) | 27.4s (min 12.6s, max 47.9s, n=8) |
| Cost | $0.2580 (min $0.1172, max $0.4381, n=8) | $0.1250 (min $0.0409, max $0.2496, n=8) |
| Accuracy | 91.7% (min 33.3%, max 100.0%, n=8) | 100.0% (min 50.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 8 / 8 / 0 / 0 | 8 / 8 / 0 / 0 |

## Per-Repo Results

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 218,473 (min 102,797, max 613,956, n=9) | 132,333 (min 63,891, max 350,149, n=9) |
| Input tokens | 216,050 (min 101,540, max 609,753, n=9) | 130,564 (min 63,079, max 346,743, n=9) |
| Output tokens | 2,423 (min 1,257, max 4,203, n=9) | 1,769 (min 812, max 3,430, n=9) |
| Tool calls | 10 (min 6, max 20, n=9) | 6 (min 4, max 15, n=9) |
| File reads | 3 (min 1, max 7, n=9) | 2 (min 0, max 5, n=9) |
| Wall-clock | 33.9s (min 16.7s, max 1.1 min, n=9) | 26.8s (min 12.6s, max 47.9s, n=9) |
| Cost | $0.2447 (min $0.1172, max $0.4381, n=9) | $0.1097 (min $0.0409, max $0.2496, n=9) |
| Accuracy | 100.0% (min 33.3%, max 100.0%, n=9) | 100.0% (min 50.0%, max 100.0%, n=9) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 9 / 9 / 0 / 0 | 9 / 9 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 173,451 (min 173,451, max 173,451, n=1) | 67,883 (min 67,883, max 67,883, n=1) |
| Input tokens | 171,456 (min 171,456, max 171,456, n=1) | 66,567 (min 66,567, max 66,567, n=1) |
| Output tokens | 1,995 (min 1,995, max 1,995, n=1) | 1,316 (min 1,316, max 1,316, n=1) |
| Tool calls | 9 (min 9, max 9, n=1) | 6 (min 6, max 6, n=1) |
| File reads | 1 (min 1, max 1, n=1) | 2 (min 2, max 2, n=1) |
| Wall-clock | 27.7s (min 27.7s, max 27.7s, n=1) | 16.4s (min 16.4s, max 16.4s, n=1) |
| Cost | $0.1236 (min $0.1236, max $0.1236, n=1) | $0.0653 (min $0.0653, max $0.0653, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

Per-question breakdown:

| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |
|---|---|---|---|---|---|---|
| excalidraw-q8 | excalidraw | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) | $0.1236 (min $0.1236, max $0.1236, n=1) | $0.0653 (min $0.0653, max $0.0653, n=1) | tie on accuracy |

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 0

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 9 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 9 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 9 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 9 | 0 |

## Judge Validation

The judge (`claude-opus-5`) scores every answer against that question's gold key-facts, blind to which arm produced the answer -- no tool trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact hit/miss and the accuracy score derived from it, not free text (AC-13).

Judge scoring failures (this answer could not be scored and is excluded from the accuracy statistics above, same as a contaminated run is excluded from the totals above; not fatal on its own -- only every answer failing aborts the run): 0

**Kappa validation was not run for this result.** No second judge model re-scored a subsample, so there is no inter-rater agreement percentage or Cohen's kappa to report here.

## Ingest Cost & Break-Even

Indexing cost is recorded separately from query-time cost (AC-2). Break-even answers: after how many questions does the WITH_TOOLS arm's lower per-question cost pay back what indexing cost up front (AC-21)?

| Repo | Ingest cost | Break-even |
|---|---|---|
| excalidraw | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.1350 (WITH_TOOLS costs the same or more per question) |

## Reproduction

Reproduce this result end to end, same profile as this run:

```bash
./gradlew :modules:benchmark:run --args="--profile full"
```

Corpus repos measured, pinned to the SHA shown under "Per-Repo Results" above:

- `Excalidraw` (`excalidraw`) -- https://github.com/excalidraw/excalidraw.git @ `v0.18.1` (`a2ec2889babf7d2295469c6d90ebe77fae57df84`)

Fast local iteration on the harness itself uses the smoke profile instead (1 repo x 3 questions x 1 repeat per arm):

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.

