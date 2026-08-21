# BENCHMARKS

_Generated from result `run-1787208762517` (schema v1, profile `full`) at 2026-08-20T07:05:07.136352Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

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
| ContextGraph tool calls | 0 |
| Runs that called it at least once | 0 / 8 |

**No ContextGraph tool was ever called in this run.** The two arms were therefore behaviourally identical, and any accuracy difference or lack of one below is evidence about tool *adoption*, not about whether the graph improves answers. Do not read the headline as a verdict on ContextGraph's usefulness.


Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):

| Repo | `litellm.enabled` | Duration | Tokens | Cost |
|---|---|---|---|---|
| excalidraw | false | 31.9s | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 178,058 (min 58,225, max 265,594, n=7) | 149,684 (min 49,317, max 266,093, n=8) |
| Input tokens | 176,242 (min 57,642, max 262,673, n=7) | 147,721 (min 48,544, max 262,707, n=8) |
| Output tokens | 1,816 (min 583, max 2,921, n=7) | 2,040 (min 773, max 3,386, n=8) |
| Tool calls | 5 (min 2, max 6, n=7) | 7 (min 3, max 15, n=8) |
| File reads | 0 (min 0, max 1, n=7) | 2 (min 0, max 7, n=8) |
| Wall-clock | 25.4s (min 9.2s, max 36.7s, n=7) | 28.5s (min 10.9s, max 43.1s, n=8) |
| Cost | $0.2558 (min $0.0819, max $0.4030, n=7) | $0.1314 (min $0.0379, max $0.2444, n=8) |
| Accuracy | 83.3% (min 50.0%, max 100.0%, n=7) | 91.7% (min 50.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 7 / 7 / 0 / 0 | 8 / 8 / 0 / 0 |

## Per-Repo Results

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 161,840.5 (min 58,225, max 265,594, n=8) | 136,760 (min 49,317, max 266,093, n=9) |
| Input tokens | 160,073 (min 57,642, max 262,673, n=8) | 135,169 (min 48,544, max 262,707, n=9) |
| Output tokens | 1,767.5 (min 583, max 2,921, n=8) | 1,745 (min 773, max 3,386, n=9) |
| Tool calls | 4.5 (min 2, max 6, n=8) | 7 (min 3, max 15, n=9) |
| File reads | 0 (min 0, max 1, n=8) | 2 (min 0, max 7, n=9) |
| Wall-clock | 25.0s (min 9.2s, max 36.7s, n=8) | 23.2s (min 10.9s, max 43.1s, n=9) |
| Cost | $0.2542 (min $0.0819, max $0.4030, n=8) | $0.1240 (min $0.0379, max $0.2444, n=9) |
| Accuracy | 91.7% (min 50.0%, max 100.0%, n=8) | 100.0% (min 50.0%, max 100.0%, n=9) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 8 / 8 / 0 / 0 | 9 / 9 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 60,264 (min 60,264, max 60,264, n=1) | 73,883 (min 73,883, max 73,883, n=1) |
| Input tokens | 59,203 (min 59,203, max 59,203, n=1) | 72,301 (min 72,301, max 72,301, n=1) |
| Output tokens | 1,061 (min 1,061, max 1,061, n=1) | 1,582 (min 1,582, max 1,582, n=1) |
| Tool calls | 2 (min 2, max 2, n=1) | 5 (min 5, max 5, n=1) |
| File reads | 0 (min 0, max 0, n=1) | 2 (min 2, max 2, n=1) |
| Wall-clock | 12.6s (min 12.6s, max 12.6s, n=1) | 19.4s (min 19.4s, max 19.4s, n=1) |
| Cost | $0.0989 (min $0.0989, max $0.0989, n=1) | $0.0768 (min $0.0768, max $0.0768, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

Per-question breakdown:

| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |
|---|---|---|---|---|---|---|
| excalidraw-q8 | excalidraw | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) | $0.0989 (min $0.0989, max $0.0989, n=1) | $0.0768 (min $0.0768, max $0.0768, n=1) | tie on accuracy |

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 1

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 8 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 9 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 8 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 9 | 0 |

## Judge Validation

The judge (`claude-opus-5`) scores every answer against that question's gold key-facts, blind to which arm produced the answer -- no tool trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact hit/miss and the accuracy score derived from it, not free text (AC-13).

Judge scoring failures (this answer could not be scored and is excluded from the accuracy statistics above, same as a contaminated run is excluded from the totals above; not fatal on its own -- only every answer failing aborts the run): 0

**Kappa validation was not run for this result.** No second judge model re-scored a subsample, so there is no inter-rater agreement percentage or Cohen's kappa to report here.

## Ingest Cost & Break-Even

Indexing cost is recorded separately from query-time cost (AC-2). Break-even answers: after how many questions does the WITH_TOOLS arm's lower per-question cost pay back what indexing cost up front (AC-21)?

| Repo | Ingest cost | Break-even |
|---|---|---|
| excalidraw | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.1302 (WITH_TOOLS costs the same or more per question) |

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

