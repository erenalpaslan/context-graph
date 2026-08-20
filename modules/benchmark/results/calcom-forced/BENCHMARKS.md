# BENCHMARKS

_Generated from result `run-1787206949852` (schema v1, profile `full`) at 2026-08-20T06:37:48.622574Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

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
| ContextGraph tool calls | 20 |
| Runs that called it at least once | 6 / 6 |


Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):

| Repo | `litellm.enabled` | Duration | Tokens | Cost |
|---|---|---|---|---|
| calcom | false | 2.5 min | 0 | $0.0000 |

## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 329,074 (min 243,706, max 607,159, n=5) | 135,531 (min 48,556, max 287,657, n=7) |
| Input tokens | 325,892 (min 240,528, max 601,348, n=5) | 132,568 (min 47,873, max 283,926, n=7) |
| Output tokens | 3,182 (min 2,473, max 5,811, n=5) | 2,698 (min 674, max 3,731, n=7) |
| Tool calls | 13 (min 11, max 20, n=5) | 10 (min 2, max 11, n=7) |
| File reads | 2 (min 1, max 4, n=5) | 2 (min 1, max 4, n=7) |
| Wall-clock | 48.4s (min 33.9s, max 1.3 min, n=5) | 33.6s (min 10.8s, max 49.1s, n=7) |
| Cost | $0.2738 (min $0.2087, max $0.4174, n=5) | $0.1300 (min $0.0344, max $0.2524, n=7) |
| Accuracy | 83.3% (min 80.0%, max 100.0%, n=5) | 100.0% (min 80.0%, max 100.0%, n=7) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 5 / 5 / 0 / 0 | 7 / 7 / 0 / 0 |

## Per-Repo Results

### cal.com (`calcom`)

Measured at tag `v6.2.0`, commit `1c193cca8682b33b9866c792186033f7ef886682`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 303,527 (min 94,604, max 607,159, n=6) | 128,920 (min 48,556, max 287,657, n=8) |
| Input tokens | 300,699.5 (min 93,761, max 601,348, n=6) | 126,590 (min 47,873, max 283,926, n=8) |
| Output tokens | 3,180 (min 843, max 5,811, n=6) | 2,197.5 (min 674, max 3,731, n=8) |
| Tool calls | 12.5 (min 5, max 20, n=6) | 9 (min 2, max 11, n=8) |
| File reads | 2 (min 1, max 4, n=6) | 1.5 (min 1, max 4, n=8) |
| Wall-clock | 46.7s (min 16.1s, max 1.3 min, n=6) | 28.8s (min 10.8s, max 49.1s, n=8) |
| Cost | $0.2472 (min $0.0778, max $0.4174, n=6) | $0.1130 (min $0.0344, max $0.2524, n=8) |
| Accuracy | 91.7% (min 80.0%, max 100.0%, n=6) | 100.0% (min 80.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 6 / 6 / 0 / 0 | 8 / 8 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | 94,604 (min 94,604, max 94,604, n=1) | 75,964 (min 75,964, max 75,964, n=1) |
| Input tokens | 93,761 (min 93,761, max 93,761, n=1) | 75,077 (min 75,077, max 75,077, n=1) |
| Output tokens | 843 (min 843, max 843, n=1) | 887 (min 887, max 887, n=1) |
| Tool calls | 5 (min 5, max 5, n=1) | 4 (min 4, max 4, n=1) |
| File reads | 1 (min 1, max 1, n=1) | 1 (min 1, max 1, n=1) |
| Wall-clock | 16.1s (min 16.1s, max 16.1s, n=1) | 15.2s (min 15.2s, max 15.2s, n=1) |
| Cost | $0.0778 (min $0.0778, max $0.0778, n=1) | $0.0702 (min $0.0702, max $0.0702, n=1) |
| Accuracy | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | 1 / 1 / 0 / 0 | 1 / 1 / 0 / 0 |

Per-question breakdown:

| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |
|---|---|---|---|---|---|---|
| calcom-q8 | calcom | 100.0% (min 100.0%, max 100.0%, n=1) | 100.0% (min 100.0%, max 100.0%, n=1) | $0.0778 (min $0.0778, max $0.0778, n=1) | $0.0702 (min $0.0702, max $0.0702, n=1) | tie on accuracy |

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 2

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 6 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 8 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 6 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 8 | 0 |

## Judge Validation

The judge (`claude-opus-5`) scores every answer against that question's gold key-facts, blind to which arm produced the answer -- no tool trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact hit/miss and the accuracy score derived from it, not free text (AC-13).

Judge scoring failures (this answer could not be scored and is excluded from the accuracy statistics above, same as a contaminated run is excluded from the totals above; not fatal on its own -- only every answer failing aborts the run): 0

**Kappa validation was not run for this result.** No second judge model re-scored a subsample, so there is no inter-rater agreement percentage or Cohen's kappa to report here.

## Ingest Cost & Break-Even

Indexing cost is recorded separately from query-time cost (AC-2). Break-even answers: after how many questions does the WITH_TOOLS arm's lower per-question cost pay back what indexing cost up front (AC-21)?

| Repo | Ingest cost | Break-even |
|---|---|---|
| calcom | $0.0000 | Does not amortize on cost alone -- per-question saving is -$0.1343 (WITH_TOOLS costs the same or more per question) |

## Reproduction

Reproduce this result end to end, same profile as this run:

```bash
./gradlew :modules:benchmark:run --args="--profile full"
```

Corpus repos measured, pinned to the SHA shown under "Per-Repo Results" above:

- `cal.com` (`calcom`) -- https://github.com/calcom/cal.com.git @ `v6.2.0` (`1c193cca8682b33b9866c792186033f7ef886682`)

Fast local iteration on the harness itself uses the smoke profile instead (1 repo x 3 questions x 1 repeat per arm):

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.

