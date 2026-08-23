<!-- retrieval-axis:start -->

## Retrieval Axis (LLM-free, deterministic)

**A separate axis from the agent A/B results above -- do not sum, average, or otherwise mix the two.** The agent axis measures whether a code-graph tool makes a *Claude agent* cheaper/faster/more accurate and requires `ANTHROPIC_API_KEY`. This axis answers a narrower question with no model call anywhere in the loop: asked the same question, which *files* does each tool put in front of you? Deterministic: no LLM call, the same corpus and question set always produce the same numbers (proven by the determinism tests in `RetrievalBenchmarkRunnerTest`, `RipgrepQueryDeriverTest` and `RipgrepBaselineRunnerTest`).

Three sides are compared, and they are named this way everywhere below:

| Side | What it is | What it was asked |
|---|---|---|
| **ContextGraph (this project)** | this repository's own graph, queried in-process through `QueryEngine.buildContext` | the raw question text |
| **CodeGraph (third-party)** | `@colbymchenry/codegraph` v1.5.0, driven as a CLI via `codegraph explore` | the raw question text |
| **ripgrep (baseline)** | plain `rg` over a clean, never-indexed checkout | tokens derived from the raw question text by `RipgrepQueryDeriver` |

**The two graph tools' names differ by two letters, so they are never written bare in a table header, a column label, or a verdict.** "ContextGraph" is this project; "CodeGraph" is the third-party tool being compared against it. A result favouring CodeGraph is a legitimate outcome of this measurement, not an error in it.

_Generated from retrieval result `retrieval-1787474369077` (schema v2) at 2026-08-23T08:39:29.085228Z. Regenerate by re-running the retrieval measurement; this section is not hand-edited._

### Methodology

For every question, the expected file set is derived from its own gold facts' `file:line` evidence -- never a hand-written second ground truth, and never a per-tool one. All three sides are scored against that same set with precision@k, recall@k, and reciprocal rank (`RetrievalMetrics`, unit-tested against known input/output pairs).

**Fairness invariants.** Each side is handed the same raw `question.text`, with nothing pre-filtered and nothing lifted from the gold facts; each is scored against the same expected set with the same metrics; and no side's output is re-ranked, filtered or truncated before scoring -- each runner projects paths in its tool's own emission order and stops. A number obtained by breaking one of these is worse than no number.

**Absence is never scored as zero.** A question a tool could not be *asked* -- an index that failed its integrity check, a `codegraph explore` call that timed out or errored -- is excluded from that side's average and listed under "Skipped" below, rather than folded in as 0.0. Counting it as zero would blame the tool for an infrastructure failure instead of a retrieval one. A tool that *ran* and returned nothing is a real zero and is counted as one. Each side's table shows how many questions it actually measured, so a smaller denominator is visible rather than implied.

`k` = 5, 10: the real gold set's expected-file-set size across all questions has a median of 3 and a maximum of 5, so k=5 is the smallest k at which every question's recall@k can reach 1.0 in principle; k=10 is a softer, twice-as-generous ceiling.

**ContextGraph (this project) side**: `QueryEngine.buildContext(question.text)` against the WITH (indexed) working copy -- the surface method closest to what an agent's own tool call makes (`ContextGraphMcpToolBridge`'s and the MCP server's `build_context` tool both call the same method). The ranked file list is the evidence list's paths, de-duplicated in rank order -- see `ContextGraphRetrievalRunner`.

**This axis has already found and driven two product defects. Read both before the numbers below.**

1. **Natural-language queries returned nothing at all -- found here, since fixed.** `buildContext`'s seed search passed the *entire* question sentence to SQLite FTS5 as one literal `MATCH` expression. FTS5 gives bareword queries implicit-AND semantics, so every token had to co-occur in one indexed row; punctuation in the sentence could also be parsed as FTS5 query syntax and throw, and that exception was swallowed into a `label LIKE '%<whole sentence>%'` fallback that could not match either. Measured on excalidraw's real index at the time: full sentence 0 rows, LIKE fallback 0 rows, the same words OR'd 28 rows. The first run of this axis therefore scored ContextGraph 0.0% on precision@5/10, recall@5/10 and MRR across all 22 measurable questions. `searchNodes` now tokenizes the query and OR's the terms as quoted phrases ranked by bm25; the numbers below are from after that fix. This is what the axis is for: it found a defect on the exact path MCP's `build_context` tool uses, and made the repair measurable.

2. **Go source yields no symbols at all -- found here, NOT yet fixed.** gin's index contains 847 `Document`, 206 `Concept` and 128 file-level nodes and *zero* `Function`, `Method`, `Class`, `Interface` or `Module` nodes; `ServeHTTP`, `handleHTTPRequest`, `combineHandlers` and `Engine` all return 0 hits. The TypeScript repos in the same corpus extract thousands of each (excalidraw 1284 functions, calcom 5969). gin's flat 0.0% below is that gap, not a retrieval-quality result: there is nothing indexed for the query to find. Any Go row in the tables below should be read as measuring extraction coverage, not retrieval.

**CodeGraph (third-party) side**: `codegraph explore -- <raw question text>` against a third working copy of the same pinned checkout, indexed by CodeGraph and by nothing else. Driven as a CLI rather than as an MCP server because its own `explore` command routes into the same handler as its `codegraph_explore` MCP tool, which keeps this side deterministic and LLM-free exactly as the baseline is.

Two parsing decisions were made here, and both move the numbers, so both are stated rather than left to be inferred. `explore` has **no `--json` flag**, so the ranked list is parsed out of its markdown.

1. **The ranked files are the ones CodeGraph rendered source for** -- its `Source Code` section, in emission order. Those are the files its own header counts ("Found N symbols across M files") and the ones its output tells an agent to treat as already read. The paths named in the preceding **blast-radius** section are *not* counted: they are annotations about impact on those same symbols. On a representative real response that is 4 ranked files against 7 further paths mentioned only in blast radius, so counting them would raise CodeGraph's recall and lower its precision.
2. **The `### ⚠️ Low-confidence match` sentinel neither truncates the list nor adds to it.** Files above it stay ranked. The section itself contains no file paths at all -- only advisory prose and *directory* hints -- and in v1.5.0 `codegraph explore` cannot emit it in the first place (only its `ContextBuilder` path does, which the CLI does not reach). The parser handles it defensively so a future version routing `explore` through that builder cannot silently truncate a ranked list.

**ripgrep (baseline) side**: `rg -F -w --count -e <token1> -e <token2> ...` against the WITHOUT (clean, never-indexed) working copy. The tokens are never the raw question sentence -- `RipgrepQueryDeriver` is the single place that derives them, extracting quoted spans, identifier-shaped words (camelCase, `snake_case`, dotted symbols, file-like tokens), and bare numeric literals from the question text, exactly what a human reading the question and reaching for `rg` would notice and search for. Files are ranked by matching-line count, descending. A question with no derivable tokens (a purely conceptual question naming no symbol, file, or constant) yields an empty ripgrep result -- reported as such, not padded.

### Ingest cost

What it cost to build each tool's index, so query-time results are read next to the price of getting there. `ripgrep (baseline)` has no ingest step at all -- it searches the working tree directly, which is exactly why it is the baseline.

| Repo | Tool | Index build time | Index size |
|---|---|---|---|
| `excalidraw` | CodeGraph (third-party) | 2.7s | 36.5 MB |
| `excalidraw` | ContextGraph (this project) | 1m 1s | 27.4 MB |

### Gold-file coverage

How much of each repo's gold-fact-cited file set each tool's index can answer for at all. A tool cannot retrieve a file it never indexed, so a low figure here caps that tool's recall below and should be read *with* the scores, not after them.

**Why this is published rather than gated.** `IndexIntegrityGate` refuses to score the ContextGraph (this project) side of a repo whose index is missing gold-cited files. It applies to that side only. Left unstated, that would bias the comparison toward this project: an incomplete ContextGraph index is dropped from the scores, while an equally incomplete CodeGraph index would be scored. The gate was deliberately **not** loosened -- weakening it to obtain a number is precisely the manufactured result this exercise exists to avoid -- so the remedy is to print both tools' coverage instead. **The residual, stated plainly: a repo failing the gate is still absent from the ContextGraph (this project) column while its CodeGraph (third-party) column is scored.** Coverage makes that visible; it does not make it symmetric.

| Repo | Side | Gold-cited files in index | Coverage | How it was determined |
|---|---|---|---|---|
| `excalidraw` | ContextGraph (this project) | 21 / 21 | 100.0% | queried the index, file by file |
| `excalidraw` | CodeGraph (third-party) | 17 / 21 | 81.0% | queried the index, file by file |
| `excalidraw` | ripgrep (baseline) | 21 / 21 | 100.0% | 100% by construction — reads the working tree directly |

### Skipped

Not silently omitted -- every repo this run could not fully measure, and why:

| Repo | Reason |
|---|---|
| gin | WITHOUT working copy not found at /private/tmp/claude-501/-Users-erenalpaslan-Projects-context-graph--harness-worktrees-2026-08-23-063049-make-contextgraph-retrieval-ranking-a-fu/a80f3cb1-e70e-4a0e-a000-2f925d58e83c/scratchpad/corpus/gin/without -- corpus not prepared for this repo; skipping all three sides |
| calcom | WITHOUT working copy not found at /private/tmp/claude-501/-Users-erenalpaslan-Projects-context-graph--harness-worktrees-2026-08-23-063049-make-contextgraph-retrieval-ranking-a-fu/a80f3cb1-e70e-4a0e-a000-2f925d58e83c/scratchpad/corpus/calcom/without -- corpus not prepared for this repo; skipping all three sides |
| keycloak | WITHOUT working copy not found at /private/tmp/claude-501/-Users-erenalpaslan-Projects-context-graph--harness-worktrees-2026-08-23-063049-make-contextgraph-retrieval-ranking-a-fu/a80f3cb1-e70e-4a0e-a000-2f925d58e83c/scratchpad/corpus/keycloak/without -- corpus not prepared for this repo; skipping all three sides |

### Headline (GRAPH_HEAVY + NEUTRAL)

Negative-control questions are excluded here on purpose (AC-26) -- see "Negative Controls" below.

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; ripgrep (baseline) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 17.5% | 10.0% | 5.0% |
| precision@10 | 10.0% | 5.0% | 2.5% |
| recall@5 | 35.4% | 25.0% | 16.7% |
| recall@10 | 38.5% | 25.0% | 16.7% |
| MRR | 0.479 | 0.275 | 0.158 |

### By Category

#### GRAPH_HEAVY

n=5 question(s). Measured: ContextGraph (this project) 5/5; CodeGraph (third-party) 5/5; ripgrep (baseline) 5/5. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 24.0% | 12.0% | 4.0% |
| precision@10 | 14.0% | 6.0% | 2.0% |
| recall@5 | 36.7% | 20.0% | 6.7% |
| recall@10 | 41.7% | 20.0% | 6.7% |
| MRR | 0.567 | 0.340 | 0.200 |

#### NEUTRAL

n=3 question(s). Measured: ContextGraph (this project) 3/3; CodeGraph (third-party) 3/3; ripgrep (baseline) 3/3. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 6.7% | 6.7% | 6.7% |
| precision@10 | 3.3% | 3.3% | 3.3% |
| recall@5 | 33.3% | 33.3% | 33.3% |
| recall@10 | 33.3% | 33.3% | 33.3% |
| MRR | 0.333 | 0.167 | 0.087 |

#### NEGATIVE_CONTROL

n=1 question(s). Measured: ContextGraph (this project) 1/1; CodeGraph (third-party) 1/1; ripgrep (baseline) 1/1. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 0.0% | 0.0% | 0.0% |
| precision@10 | 0.0% | 0.0% | 0.0% |
| recall@5 | 0.0% | 0.0% | 0.0% |
| recall@10 | 0.0% | 0.0% | 0.0% |
| MRR | 0.000 | 0.000 | 0.000 |

### By Repo

#### `excalidraw`

n=9 question(s). Measured: ContextGraph (this project) 9/9; CodeGraph (third-party) 9/9; ripgrep (baseline) 9/9. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 15.6% | 8.9% | 4.4% |
| precision@10 | 8.9% | 4.4% | 2.2% |
| recall@5 | 31.5% | 22.2% | 14.8% |
| recall@10 | 34.3% | 22.2% | 14.8% |
| MRR | 0.426 | 0.244 | 0.140 |

### Negative Controls

Questions where `grep` is expected to clearly win (AC-5, AC-26). Reported separately from the headline above, including every place ContextGraph loses -- that is this section's entire purpose.

n=1 question(s). Measured: ContextGraph (this project) 1/1; CodeGraph (third-party) 1/1; ripgrep (baseline) 1/1. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |
|---|---|---|---|
| precision@5 | 0.0% | 0.0% | 0.0% |
| precision@10 | 0.0% | 0.0% | 0.0% |
| recall@5 | 0.0% | 0.0% | 0.0% |
| recall@10 | 0.0% | 0.0% | 0.0% |
| MRR | 0.000 | 0.000 | 0.000 |

Per-question breakdown (recall@10, higher is better):

| Question | Repo | ripgrep tokens | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) | Verdict |
|---|---|---|---|---|---|---|
| excalidraw-q8 | excalidraw | `localStorage` | 0.0% | 0.0% | 0.0% | no side found a gold file |

### Reproduction

Reproduce this retrieval result, against an already-prepared corpus:

```bash
./gradlew :modules:benchmark:prepareCorpus --args="--repos keycloak,excalidraw"
./gradlew :modules:benchmark:runRetrieval  --args="--output-dir results/three-way"
```

`runRetrieval` never clones, indexes, or re-indexes anything -- it reads the corpus `prepareCorpus` left behind, which is what lets it observe a corpus another process is still indexing without racing it. `./gradlew build` and `./gradlew check` run neither task.

**This instrument is not self-contained, and a fresh machine cannot reproduce it from this repository alone.** Two things must be installed by hand first:

- `ripgrep` (`rg`) on `PATH`, for the baseline side.
- `@colbymchenry/codegraph` v1.5.0 on `PATH` (`npm i -g @colbymchenry/codegraph@1.5.0`), for the CodeGraph (third-party) side. It was installed on the host rather than vendored into the repository, deliberately, so that producing these numbers needed no package-registry access from the measuring environment. That is a real, accepted cost of the setup and is recorded here rather than left for a reader to discover: without it, the CodeGraph column is absent — and it is absent *visibly*, as a recorded skip, never as a zero.

Without `codegraph`, `prepareCorpus` still succeeds: the third working copy is created and left unindexed, with the reason recorded in that repo's `ingest.json` and echoed to the console.

<!-- retrieval-axis:end -->
