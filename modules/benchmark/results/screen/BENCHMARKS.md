# BENCHMARKS

_Generated from result `run-1787226775125` (schema v1, profile `full`) at 2026-08-20T12:17:07.380973Z. This document is derived from that result's JSON, not hand-written -- re-run the report generator against a new result to update it._

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


## Headline Results

Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL questions are never folded in here (AC-20) -- see "Negative Controls" below for those. Median across 1 repeat(s), with min/max shown alongside every median -- no median is published without its variance.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 128,105 (min 47,255, max 405,073, n=29) |
| Input tokens | no data | 126,165 (min 46,844, max 395,005, n=29) |
| Output tokens | no data | 1,940 (min 393, max 10,068, n=29) |
| Tool calls | no data | 7 (min 2, max 18, n=29) |
| File reads | no data | 2 (min 0, max 6, n=29) |
| Wall-clock | no data | 28.8s (min 7.6s, max 1.8 min, n=29) |
| Cost | no data | $0.1126 (min $0.0277, max $0.4082, n=29) |
| Accuracy | no data | 100.0% (min 33.3%, max 100.0%, n=29) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 29 / 29 / 0 / 0 |

## Per-Repo Results

### cal.com (`calcom`)

Measured at tag `v6.2.0`, commit `1c193cca8682b33b9866c792186033f7ef886682`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 136,118.5 (min 47,255, max 219,812, n=8) |
| Input tokens | no data | 133,818 (min 46,844, max 217,497, n=8) |
| Output tokens | no data | 2,005 (min 411, max 3,155, n=8) |
| Tool calls | no data | 7 (min 2, max 14, n=8) |
| File reads | no data | 2 (min 1, max 4, n=8) |
| Wall-clock | no data | 26.9s (min 7.6s, max 41.1s, n=8) |
| Cost | no data | $0.1216 (min $0.0277, max $0.1955, n=8) |
| Accuracy | no data | 100.0% (min 80.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 8 / 8 / 0 / 0 |

### Excalidraw (`excalidraw`)

Measured at tag `v0.18.1`, commit `a2ec2889babf7d2295469c6d90ebe77fae57df84`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 164,323 (min 47,869, max 300,176, n=9) |
| Input tokens | no data | 161,790 (min 47,155, max 296,666, n=9) |
| Output tokens | no data | 1,902 (min 714, max 3,510, n=9) |
| Tool calls | no data | 8 (min 3, max 14, n=9) |
| File reads | no data | 2 (min 0, max 6, n=9) |
| Wall-clock | no data | 28.8s (min 10.3s, max 50.1s, n=9) |
| Cost | no data | $0.1183 (min $0.0342, max $0.2115, n=9) |
| Accuracy | no data | 100.0% (min 33.3%, max 100.0%, n=9) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 9 / 9 / 0 / 0 |

### gin (`gin`)

Measured at tag `v1.12.0`, commit `73726dc606796a025971fe451f0aa6f1b9b847f6`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 81,177.5 (min 50,017, max 147,793, n=8) |
| Input tokens | no data | 79,401 (min 49,139, max 144,791, n=8) |
| Output tokens | no data | 1,514 (min 638, max 3,002, n=8) |
| Tool calls | no data | 4.5 (min 2, max 11, n=8) |
| File reads | no data | 1.5 (min 0, max 6, n=8) |
| Wall-clock | no data | 19.9s (min 10.3s, max 37.4s, n=8) |
| Cost | no data | $0.0770 (min $0.0430, max $0.1764, n=8) |
| Accuracy | no data | 100.0% (min 60.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 8 / 8 / 0 / 0 |

### Keycloak (`keycloak`)

Measured at tag `26.7.1`, commit `73f08b397f193712b26d317210dce99898129709`.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 131,276.5 (min 47,956, max 405,073, n=8) |
| Input tokens | no data | 129,106.5 (min 47,563, max 395,005, n=8) |
| Output tokens | no data | 2,009 (min 393, max 10,068, n=8) |
| Tool calls | no data | 7 (min 2, max 18, n=8) |
| File reads | no data | 2.5 (min 1, max 4, n=8) |
| Wall-clock | no data | 29.2s (min 8.8s, max 1.8 min, n=8) |
| Cost | no data | $0.1121 (min $0.0336, max $0.4082, n=8) |
| Accuracy | no data | 91.7% (min 80.0%, max 100.0%, n=8) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 8 / 8 / 0 / 0 |

## Negative Controls

NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win (AC-5). They are never folded into the headline above (AC-20). This section is the report's source of credibility, not its weakness -- every place ContextGraph loses is shown here exactly as measured.

| Metric | WITH_TOOLS (ContextGraph MCP) | WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) |
|---|---|---|
| Total tokens | no data | 67,025 (min 47,884, max 91,641, n=4) |
| Input tokens | no data | 66,098.5 (min 47,385, max 90,553, n=4) |
| Output tokens | no data | 926.5 (min 499, max 1,088, n=4) |
| Tool calls | no data | 3.5 (min 2, max 4, n=4) |
| File reads | no data | 1 (min 1, max 1, n=4) |
| Wall-clock | no data | 14.4s (min 9.8s, max 16.5s, n=4) |
| Cost | no data | $0.0538 (min $0.0324, max $0.0788, n=4) |
| Accuracy | no data | 100.0% (min 100.0%, max 100.0%, n=4) |
| Runs (total / included / contaminated-excluded / ceiling-hit) | not run | 4 / 4 / 0 / 0 |

Per-question breakdown:

| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |
|---|---|---|---|---|---|---|
| calcom-q8 | calcom | no data | 100.0% (min 100.0%, max 100.0%, n=1) | no data | $0.0324 (min $0.0324, max $0.0324, n=1) | insufficient data |
| excalidraw-q8 | excalidraw | no data | 100.0% (min 100.0%, max 100.0%, n=1) | no data | $0.0624 (min $0.0624, max $0.0624, n=1) | insufficient data |
| gin-q8 | gin | no data | 100.0% (min 100.0%, max 100.0%, n=1) | no data | $0.0452 (min $0.0452, max $0.0452, n=1) | insufficient data |
| keycloak-q8 | keycloak | no data | 100.0% (min 100.0%, max 100.0%, n=1) | no data | $0.0788 (min $0.0788, max $0.0788, n=1) | insufficient data |

## Contamination Report

Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash (sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is marked `contaminated` and excluded from every total in this document (AC-9) -- this section is the only place those runs are still counted.

Failed runs (crashed before producing a record at all -- distinct from `contaminated`, and also excluded from every total above; the suite continues past a single failure rather than aborting): 0

| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |
|---|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 0 | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 33 | 0 | 0 |

## Tool-Call Ceiling Report

The ceiling is 40 tool calls, identical in both arms (Q2). A run that hits it is truncated, not run to a natural stop -- if the control arm hits the ceiling often, its numbers above were obtained *by being cut off*, and that is shown here rather than hidden (AC-10a).

| Arm | Total runs | Ceiling-hit runs |
|---|---|---|
| WITH_TOOLS (ContextGraph MCP) | 0 | 0 |
| WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 33 | 0 |

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

- `cal.com` (`calcom`) -- https://github.com/calcom/cal.com.git @ `v6.2.0` (`1c193cca8682b33b9866c792186033f7ef886682`)
- `Excalidraw` (`excalidraw`) -- https://github.com/excalidraw/excalidraw.git @ `v0.18.1` (`a2ec2889babf7d2295469c6d90ebe77fae57df84`)
- `gin` (`gin`) -- https://github.com/gin-gonic/gin.git @ `v1.12.0` (`73726dc606796a025971fe451f0aa6f1b9b847f6`)
- `Keycloak` (`keycloak`) -- https://github.com/keycloak/keycloak.git @ `26.7.1` (`73f08b397f193712b26d317210dce99898129709`)

Fast local iteration on the harness itself uses the smoke profile instead (1 repo x 3 questions x 1 repeat per arm):

```bash
./gradlew :modules:benchmark:run --args="--profile smoke"
```

`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.

