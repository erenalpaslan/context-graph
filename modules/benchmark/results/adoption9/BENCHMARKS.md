# BENCHMARKS

_Generated from result `run-1787169404857` (schema v1, profile `full`) at 2026-08-19T20:09:17.808707Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

## Methodology

Every question runs in two arms that differ in exactly one way (AC-7): whether the agent has ContextGraph's MCP tools available.

- **WITH_TOOLS** -- the agent has ContextGraph's MCP server available, working in a copy of the repo that has already been indexed (`.contextgraph/graph.db` present).
- **WITHOUT_TOOLS** -- the control arm, representing a project ContextGraph has never touched. This is **not** the same agent with tools switched off: it runs in a separate, independently checked-out working copy of the same repo at the same pinned SHA that has never been indexed and carries no ContextGraph artefact at all (`.contextgraph/`, `graph.db`, `graph.local.db`, `GRAPH_REPORT.md`, `graph.html`) -- verified artefact-free before every run (AC-7a). Two working copies exist because ContextGraph writes its graph into the project root itself (`GraphDb.baseline(root)` resolves to `root/.contextgraph/graph.db`) and that path cannot be relocated outside the checkout, so an indexed checkout cannot also serve as its own control.

Everything else is identical between arms: same model, same system prompt, same question text, same budget.

| | |
|---|---|
| Agent backend | `claude-code` |
| Measurement | `adoption` -- nothing was said about ContextGraph; this measures whether an agent reaches for it unprompted |
| Agent model | `claude-sonnet-5` |
| Judge model | `claude-opus-5` |
| Budget per run | unlimited -- no per-run cap was set |
| Repeats per (question, arm) | 1 |

### ContextGraph tool usage (WITH_TOOLS arm)

| | |
|---|---|
| ContextGraph tool calls | 0 |
| Runs that called it at least once | 0 / 9 |

**No ContextGraph tool was ever called in this run.** The two arms were therefore behaviourally identical, and any accuracy difference or lack of one below is evidence about tool *adoption*, not about whether the graph improves answers. Do not read the headline as a verdict on ContextGraph's usefulness.


Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):

| Repo | `litellm.enabled` | Duration | Tokens | Cost |
|---|---|---|---|---|
| excalidraw | false | 31.3s | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 170,463 (min 49,806, max 443,480, n=8) | 128,195 (min 48,974, max 275,325, n=8) |
| Input tokens | 168,061.5 (min 49,196, max 440,233, n=8) | 126,477 (min 48,246, max 272,904, n=8) |
| Output tokens | 2,077.5 (min 610, max 3,247, n=8) | 1,786.5 (min 704, max 3,205, n=8) |
| Tool calls | 8 (min 3, max 13, n=8) | 5.5 (min 2, max 14, n=8) |
| File reads | 3 (min 0, max 5, n=8) | 2.5 (min 0, max 5, n=8) |
| Wall-clock | 31.9s (min 10.1s, max 50.8s, n=8) | 24.3s (min 10.7s, max 38.5s, n=8) |
| Cost | $0.1355 (min $0.0385, max $0.4056, n=8) | $0.1082 (min $0.0376, max $0.1883, n=8) |
| Accuracy | 91.7% (min 50.0%, max 100.0%, n=8) | 91.7% (min 50.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 8 / 8 / 0 / 0 | 8 / 8 / 0 / 0 |

## Per-Repo Results

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 143,542 (min 49,806, max 443,480, n=9) | 114,586 (min 48,974, max 275,325, n=9) |
| Input tokens | 141,516 (min 49,196, max 440,233, n=9) | 113,044 (min 48,246, max 272,904, n=9) |
| Output tokens | 2,026 (min 610, max 3,247, n=9) | 1,679 (min 704, max 3,205, n=9) |
| Tool calls | 7 (min 3, max 13, n=9) | 5 (min 2, max 14, n=9) |
| File reads | 3 (min 0, max 5, n=9) | 2 (min 0, max 5, n=9) |
| Wall-clock | 28.2s (min 10.1s, max 50.8s, n=9) | 22.6s (min 10.7s, max 38.5s, n=9) |
| Cost | $0.1094 (min $0.0385, max $0.4056, n=9) | $0.0962 (min $0.0376, max $0.1883, n=9) |
| Accuracy | 100.0% (min 50.0%, max 100.0%, n=9) | 100.0% (min 50.0%, max 100.0%, n=9) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 9 / 9 / 0 / 0 | 9 / 9 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 71,660 (min 71,660, max 71,660, n=1) | 66,538 (min 66,538, max 66,538, n=1) |
| Input tokens | 70,370 (min 70,370, max 70,370, n=1) | 65,221 (min 65,221, max 65,221, n=1) |
| Output tokens | 1,290 (min 1,290, max 1,290, n=1) | 1,317 (min 1,317, max 1,317, n=1) |
| Tool calls | 4 (min 4, max 4, n=1) | 4 (min 4, max 4, n=1) |
| File reads | 2 (min 2, max 2, n=1) | 2 (min 2, max 2, n=1) |
| Wall-clock | 17.9s (min 17.9s, max 17.9s, n=1) | 15.9s (min 15.9s, max 15.9s, n=1) |
| Cost | $0.0673 (min $0.0673, max $0.0673, n=1) | $0.0625 (min $0.0625, max $0.0625, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

Per-question breakdown:

| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |
|---|---|---|---|---|---|---|
| excalidraw-q8 | excalidraw | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) | $0.0673 (min $0.0673, max $0.0673, n=1) | $0.0625 (min $0.0625, max $0.0625, n=1) | tie on accuracy |

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
| excalidraw | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.0132 (WITH_TOOLS costs the same or more per question) |

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

