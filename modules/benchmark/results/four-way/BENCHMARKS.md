<!-- retrieval-axis:start -->

## Retrieval Axis (LLM-free, deterministic)

**A separate axis from the agent A/B results above -- do not sum, average, or otherwise mix the two.** The agent axis measures whether a code-graph tool makes a *Claude agent* cheaper/faster/more accurate and requires `ANTHROPIC_API_KEY`. This axis answers a narrower question with no model call anywhere in the loop: asked the same question, which *files* does each tool put in front of you? Deterministic: no LLM call, the same corpus and question set always produce the same numbers (proven by the determinism tests in `RetrievalBenchmarkRunnerTest`, `RipgrepQueryDeriverTest`, `RipgrepBaselineRunnerTest` and `BashBaselineRunnerTest`).

Four sides are compared, and they are named this way everywhere below:

| Side | What it is | What it was asked |
|---|---|---|
| **ContextGraph (this project)** | this repository's own graph, queried in-process through `QueryEngine.buildContext` | the raw question text |
| **CodeGraph (third-party)** | `@colbymchenry/codegraph` v1.5.0, driven as a CLI via `codegraph explore` | the raw question text |
| **bash (base-system shell only)** | `grep` from the stock base system, over a clean, never-indexed checkout — **no third-party tools** are installed, invoked or assumed anywhere in this side | the same derived tokens the ripgrep side is given |
| **ripgrep (baseline)** | plain `rg` over a clean, never-indexed checkout | tokens derived from the raw question text by `RipgrepQueryDeriver` |

**The two graph tools' names differ by two letters, so they are never written bare in a table header, a column label, or a verdict.** "ContextGraph" is this project; "CodeGraph" is the third-party tool being compared against it. A result favouring CodeGraph is a legitimate outcome of this measurement, not an error in it.

So is a result favouring bash. The fourth side exists because the third one is not the floor: `rg` is a separate install that brings its own `.gitignore` awareness, binary skipping and ranking to the fight, so beating it is not the same as beating *nothing*. **bash (base-system shell only)** is what a developer with a stock shell and no installs gets, and it is the honest thing an index has to earn its cost against. Where it wins, that is the finding — printed, not explained away. *Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış bir sayıdır.*

_Generated from retrieval result `retrieval-1787565961442` (schema v3) at 2026-08-24T10:06:01.443713Z. Regenerate by re-running the retrieval measurement; this section is not hand-edited._

### Methodology

For every question, the expected file set is derived from its own gold facts' `file:line` evidence -- never a hand-written second ground truth, and never a per-tool one. All four sides are scored against that same set with precision@k, recall@k, and reciprocal rank (`RetrievalMetrics`, unit-tested against known input/output pairs).

**Fairness invariants.** Each side is handed the same raw `question.text`, with nothing pre-filtered and nothing lifted from the gold facts; each is scored against the same expected set with the same metrics; and no side's output is re-ranked, filtered or truncated before scoring -- each runner projects paths in its tool's own emission order and stops. A number obtained by breaking one of these is worse than no number.

**Absence is never scored as zero.** A question a tool could not be *asked* -- an index that failed its integrity check, a `codegraph explore` call that timed out or errored -- is excluded from that side's average and listed under "Skipped" below, rather than folded in as 0.0. Counting it as zero would blame the tool for an infrastructure failure instead of a retrieval one. A tool that *ran* and returned nothing is a real zero and is counted as one. Each side's table shows how many questions it actually measured, so a smaller denominator is visible rather than implied.

That rule is the rule for **bash (base-system shell only)** too, and is deliberately not relaxed for it. Neither text-search side can be blocked by an index -- both read the working tree -- but a `grep` that exits on a real error still leaves that question unmeasured, and it is excluded exactly as a timed-out `codegraph explore` is. A `grep` that ran clean and matched nothing is a real zero and is counted as one: that is the honest floor this side exists to measure.

`k` = 5, 10: the real gold set's expected-file-set size across all questions has a median of 3 and a maximum of 5, so k=5 is the smallest k at which every question's recall@k can reach 1.0 in principle; k=10 is a softer, twice-as-generous ceiling.

**ContextGraph (this project) side**: `QueryEngine.buildContext(question.text)` against the WITH (indexed) working copy -- the surface method closest to what an agent's own tool call makes (`ContextGraphMcpToolBridge`'s and the MCP server's `build_context` tool both call the same method). The ranked file list is the evidence list's paths, de-duplicated in rank order -- see `ContextGraphRetrievalRunner`.

**This axis has already found and driven one product defect, and it re-measures a second gap from scratch every time it runs. Read both before the numbers below.**

1. **Natural-language queries returned nothing at all -- found here, since fixed.** `buildContext`'s seed search passed the *entire* question sentence to SQLite FTS5 as one literal `MATCH` expression. FTS5 gives bareword queries implicit-AND semantics, so every token had to co-occur in one indexed row; punctuation in the sentence could also be parsed as FTS5 query syntax and throw, and that exception was swallowed into a `label LIKE '%<whole sentence>%'` fallback that could not match either. Measured on excalidraw's real index at the time: full sentence 0 rows, LIKE fallback 0 rows, the same words OR'd 28 rows. The first run of this axis therefore scored ContextGraph 0.0% on precision@5/10, recall@5/10 and MRR across all 22 measurable questions. `searchNodes` now tokenizes the query and OR's the terms as quoted phrases ranked by bm25; the numbers below are from after that fix. This is what the axis is for: it found a defect on the exact path MCP's `build_context` tool uses, and made the repair measurable.

2. **`gin` yields no code declarations at all -- an extraction gap, not a retrieval result.** `gin`'s index holds 10 / 10 gold-cited files and *zero* declarations: 0 `Function`, 0 `Method`, 0 `Class`, 0 `Interface`. What it does hold is 847 `Document`, 206 `Concept`, 56 `CodeFile`, 42 `TestFile`, 11 `ConfigFile`, 10 `MarkdownFile`, 7 `Requirement`. A repo whose language the indexer does not parse is measuring **extraction coverage, not retrieval quality**: there is nothing indexed for the query to find, so every ContextGraph (this project) figure for it below is a floor set by the extractor rather than a verdict on retrieval, and its own table below repeats this warning next to the numbers. The other three sides are unaffected -- two read the working tree, and the third has its own index.

**What each ContextGraph (this project) index actually extracted**, which is the census the paragraph above is computed from:

| Repo | Declaration nodes | Node census (all types, most numerous first) |
|---|---|---|
| `calcom` | 14660 | 40022 `Module`, 8708 `Field`, 6580 `CodeFile`, 6003 `Function`, 5721 `Method`, 3653 `TypeAlias`, 3331 `Concept`, 2987 `Component`, 2521 `Variable`, 1721 `Class`, 1215 `Interface`, 816 `TestFile`, 593 `DatabaseSchema`, 571 `Document`, 533 `Column`, 472 `MarkdownFile`, 130 `Requirement`, 129 `Enum`, 126 `DatabaseTable`, 120 `PackageFile`, 113 `CodeModule`, 79 `ConfigFile` |
| `excalidraw` | 1789 | 3923 `Module`, 1508 `Document`, 1284 `Function`, 1086 `Field`, 451 `CodeFile`, 394 `Concept`, 387 `Component`, 386 `TypeAlias`, 350 `Method`, 289 `Variable`, 91 `Interface`, 89 `TestFile`, 64 `Class`, 46 `MarkdownFile`, 15 `ConfigFile`, 8 `PackageFile`, 6 `CodeModule`, 3 `Enum`, 3 `Requirement` |
| `gin` | 0 | 847 `Document`, 206 `Concept`, 56 `CodeFile`, 42 `TestFile`, 11 `ConfigFile`, 10 `MarkdownFile`, 7 `Requirement` |
| `keycloak` | 82776 | 105248 `Module`, 72147 `Method`, 27390 `Field`, 8278 `Class`, 7183 `CodeFile`, 5334 `Concept`, 2031 `TestFile`, 1831 `Document`, 1189 `Interface`, 1162 `Function`, 820 `TypeAlias`, 670 `Component`, 304 `Enum`, 194 `Record`, 101 `ConfigFile`, 88 `Requirement`, 82 `MarkdownFile`, 77 `Variable`, 9 `Constant`, 8 `DatabaseSchema`, 8 `PackageFile` |

Declaration nodes are `Function`, `Method`, `Class`, `Interface` -- what a language grammar emits for something it parsed out of a source file. `Document`, `Concept` and file-level nodes are excluded from that total on purpose: a repo the indexer could not parse a line of still accumulates them, so counting them would hide the very gap this census exists to show.

**CodeGraph (third-party) side**: `codegraph explore -- <raw question text>` against a third working copy of the same pinned checkout, indexed by CodeGraph and by nothing else. Driven as a CLI rather than as an MCP server because its own `explore` command routes into the same handler as its `codegraph_explore` MCP tool, which keeps this side deterministic and LLM-free exactly as the baseline is.

Two parsing decisions were made here, and both move the numbers, so both are stated rather than left to be inferred. `explore` has **no `--json` flag**, so the ranked list is parsed out of its markdown.

1. **The ranked files are the ones CodeGraph rendered source for** -- its `Source Code` section, in emission order. Those are the files its own header counts ("Found N symbols across M files") and the ones its output tells an agent to treat as already read. The paths named in the preceding **blast-radius** section are *not* counted: they are annotations about impact on those same symbols. On a representative real response that is 4 ranked files against 7 further paths mentioned only in blast radius, so counting them would raise CodeGraph's recall and lower its precision.
2. **The `### ⚠️ Low-confidence match` sentinel neither truncates the list nor adds to it.** Files above it stay ranked. The section itself contains no file paths at all -- only advisory prose and *directory* hints -- and in v1.5.0 `codegraph explore` cannot emit it in the first place (only its `ContextBuilder` path does, which the CLI does not reach). The parser handles it defensively so a future version routing `explore` through that builder cannot silently truncate a ranked list.

**ripgrep (baseline) side**: `rg -F -w --count -e <token1> -e <token2> ...` against the WITHOUT (clean, never-indexed) working copy. The tokens are never the raw question sentence -- `RipgrepQueryDeriver` is the single place that derives them, extracting quoted spans, identifier-shaped words (camelCase, `snake_case`, dotted symbols, file-like tokens), and bare numeric literals from the question text, exactly what a human reading the question and reaching for `rg` would notice and search for. Files are ranked by matching-line count, descending. A question with no derivable tokens (a purely conceptual question naming no symbol, file, or constant) yields an empty ripgrep result -- reported as such, not padded.

**bash (base-system shell only) side**: `grep -r -F -w -I -s --exclude-dir=.git -c -e <token> [-e <token> …] .` against the same WITHOUT (clean, never-indexed) working copy the ripgrep side reads, given the same tokens from the same `RipgrepQueryDeriver`, ranked by the same rule (matching-line count descending, ties alphabetical). The two text-search sides therefore differ in the binary and in nothing else, which is what makes the gap between them attributable to `rg`'s engineering rather than to the query. `grep` is invoked at its base-system path, not resolved through `PATH`, so a developer's Homebrew GNU grep cannot quietly become the thing being measured.

**Why these flags and not others.** `rg` brings defaults to the fight that `grep` has none of, so an honest floor needs a different flag list rather than the same one. Each flag below either states out loud something `rg` assumes, or states nothing at all:

| Flag | Why it is there |
|---|---|
| `-r` | Search the checkout recursively -- `rg` recurses by default; `grep` has to be told. |
| `-F` | Fixed strings, so a dot or slash inside a derived token is not a regex metacharacter. Same as the ripgrep side's -F. |
| `-w` | Whole-word match, so searching for `Next` does not also match `NextFunc`. Same as the ripgrep side's -w. |
| `-I` | Skip binary files, which `rg` does by default; without it `grep` prints `Binary file … matches` instead of a count. |
| `-s` | Suppress messages about unreadable files. Flag-for-flag equivalent of the ripgrep side's --no-messages. |
| `--exclude-dir=.git` | Exclude the repository's own `.git` directory, which `rg` skips by default as a hidden entry; a depth-1 mirror's packfiles would otherwise dominate both runtime and match counts. |
| `-c` | Count matching lines per file -- the ranking signal. Same as the ripgrep side's --count. |

There is, deliberately, **no emulation of `rg`'s `.gitignore` awareness** here. That is `rg`'s engineering, not plain text search's, and reproducing it with `--exclude-dir` lists would quietly strengthen this side into a second ripgrep. A number won by starving the baseline is not the baseline's number, and neither is one won by secretly strengthening it.

### Ingest cost

What it cost to build each tool's index, so query-time results are read next to the price of getting there. Neither text-search side has an ingest step at all: `bash (base-system shell only)` and `ripgrep (baseline)` both search the working tree directly, and pay nothing before the first query. That is exactly why they are the baselines, and it is the number every index-building row below is being compared against.

| Repo | Tool | Index build time | Index size |
|---|---|---|---|
| `calcom` | CodeGraph (third-party) | 1m 25s | 306.7 MB |
| `calcom` | ContextGraph (this project) | 1m 38s | 268.6 MB |
| `excalidraw` | CodeGraph (third-party) | 17.8s | 36.5 MB |
| `excalidraw` | ContextGraph (this project) | 22.9s | 32.7 MB |
| `gin` | CodeGraph (third-party) | 3.5s | 7.9 MB |
| `gin` | ContextGraph (this project) | 4.3s | 1.3 MB |
| `keycloak` | CodeGraph (third-party) | 1m 53s | 811.6 MB |
| `keycloak` | ContextGraph (this project) | 4m 14s | 1.58 GB |

### Gold-file coverage

How much of each repo's gold-fact-cited file set each tool's index can answer for at all. A tool cannot retrieve a file it never indexed, so a low figure here caps that tool's recall below and should be read *with* the scores, not after them.

**Why this is published rather than gated.** `IndexIntegrityGate` refuses to score the ContextGraph (this project) side of a repo whose index is missing gold-cited files. It applies to that side only. Left unstated, that would bias the comparison toward this project: an incomplete ContextGraph index is dropped from the scores, while an equally incomplete CodeGraph index would be scored. The gate was deliberately **not** loosened -- weakening it to obtain a number is precisely the manufactured result this exercise exists to avoid -- so the remedy is to print both tools' coverage instead. **The residual, stated plainly: a repo failing the gate is still absent from the ContextGraph (this project) column while its CodeGraph (third-party) column is scored.** Coverage makes that visible; it does not make it symmetric.

| Repo | Side | Gold-cited files in index | Coverage | How it was determined |
|---|---|---|---|---|
| `calcom` | ContextGraph (this project) | 19 / 19 | 100.0% | queried the index, file by file |
| `calcom` | CodeGraph (third-party) | 9 / 19 | 47.4% | queried the index, file by file |
| `calcom` | bash (base-system shell only) | 19 / 19 | 100.0% | 100% by construction — reads the working tree directly |
| `calcom` | ripgrep (baseline) | 19 / 19 | 100.0% | 100% by construction — reads the working tree directly |
| `excalidraw` | ContextGraph (this project) | 21 / 21 | 100.0% | queried the index, file by file |
| `excalidraw` | CodeGraph (third-party) | 17 / 21 | 81.0% | queried the index, file by file |
| `excalidraw` | bash (base-system shell only) | 21 / 21 | 100.0% | 100% by construction — reads the working tree directly |
| `excalidraw` | ripgrep (baseline) | 21 / 21 | 100.0% | 100% by construction — reads the working tree directly |
| `gin` | ContextGraph (this project) | 10 / 10 | 100.0% | queried the index, file by file |
| `gin` | CodeGraph (third-party) | 10 / 10 | 100.0% | queried the index, file by file |
| `gin` | bash (base-system shell only) | 10 / 10 | 100.0% | 100% by construction — reads the working tree directly |
| `gin` | ripgrep (baseline) | 10 / 10 | 100.0% | 100% by construction — reads the working tree directly |
| `keycloak` | ContextGraph (this project) | 26 / 26 | 100.0% | queried the index, file by file |
| `keycloak` | CodeGraph (third-party) | 22 / 26 | 84.6% | queried the index, file by file |
| `keycloak` | bash (base-system shell only) | 26 / 26 | 100.0% | 100% by construction — reads the working tree directly |
| `keycloak` | ripgrep (baseline) | 26 / 26 | 100.0% | 100% by construction — reads the working tree directly |

### Headline (GRAPH_HEAVY + NEUTRAL)

Negative-control questions are excluded here on purpose (AC-26) -- see "Negative Controls" below.

n=29 question(s). Measured: ContextGraph (this project) 29/29; CodeGraph (third-party) 29/29; bash (base-system shell only) 29/29; ripgrep (baseline) 29/29. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 12.4% | 12.4% | 11.0% | 11.0% |
| precision@10 | 7.2% | 6.2% | 8.3% | 8.3% |
| recall@5 | 21.1% | 28.0% | 27.0% | 27.0% |
| recall@10 | 23.9% | 28.0% | 35.1% | 35.1% |
| MRR | 0.312 | 0.326 | 0.218 | 0.218 |

### By Category

#### GRAPH_HEAVY

n=20 question(s). Measured: ContextGraph (this project) 20/20; CodeGraph (third-party) 20/20; bash (base-system shell only) 20/20; ripgrep (baseline) 20/20. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 11.0% | 14.0% | 12.0% | 12.0% |
| precision@10 | 6.5% | 7.0% | 10.0% | 10.0% |
| recall@5 | 14.9% | 24.6% | 19.1% | 19.1% |
| recall@10 | 17.8% | 24.6% | 30.9% | 30.9% |
| MRR | 0.278 | 0.335 | 0.254 | 0.254 |

#### NEUTRAL

n=9 question(s). Measured: ContextGraph (this project) 9/9; CodeGraph (third-party) 9/9; bash (base-system shell only) 9/9; ripgrep (baseline) 9/9. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 15.6% | 8.9% | 8.9% | 8.9% |
| precision@10 | 8.9% | 4.4% | 4.4% | 4.4% |
| recall@5 | 35.0% | 35.6% | 44.4% | 44.4% |
| recall@10 | 37.2% | 35.6% | 44.4% | 44.4% |
| MRR | 0.389 | 0.306 | 0.140 | 0.140 |

#### NEGATIVE_CONTROL

n=4 question(s). Measured: ContextGraph (this project) 4/4; CodeGraph (third-party) 4/4; bash (base-system shell only) 4/4; ripgrep (baseline) 4/4. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 5.0% | 10.0% | 5.0% | 5.0% |
| precision@10 | 2.5% | 5.0% | 5.0% | 5.0% |
| recall@5 | 25.0% | 37.5% | 25.0% | 25.0% |
| recall@10 | 25.0% | 37.5% | 37.5% | 37.5% |
| MRR | 0.250 | 0.175 | 0.185 | 0.185 |

### By Repo

#### `calcom`

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (baseline) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 12.5% | 2.5% | 5.0% | 5.0% |
| precision@10 | 6.3% | 1.3% | 3.8% | 3.8% |
| recall@5 | 33.8% | 3.1% | 5.0% | 5.0% |
| recall@10 | 33.8% | 3.1% | 7.5% | 7.5% |
| MRR | 0.369 | 0.125 | 0.152 | 0.152 |

#### `excalidraw`

n=9 question(s). Measured: ContextGraph (this project) 9/9; CodeGraph (third-party) 9/9; bash (base-system shell only) 9/9; ripgrep (baseline) 9/9. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 22.2% | 8.9% | 4.4% | 4.4% |
| precision@10 | 12.2% | 4.4% | 2.2% | 2.2% |
| recall@5 | 39.8% | 22.2% | 14.8% | 14.8% |
| recall@10 | 42.6% | 22.2% | 14.8% | 14.8% |
| MRR | 0.556 | 0.244 | 0.140 | 0.140 |

#### `gin`

> **Read the ContextGraph (this project) column here as extraction coverage, not retrieval quality.** `gin`'s index holds 10 / 10 gold-cited files and zero code declarations (847 `Document`, 206 `Concept`, 56 `CodeFile`, 42 `TestFile`, 11 `ConfigFile`, 10 `MarkdownFile`, 7 `Requirement`), so there is nothing indexed for a query to match. The figure below is the extractor's floor, not a retrieval verdict; the other three columns are unaffected.

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (baseline) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 0.0% | 32.5% | 22.5% | 22.5% |
| precision@10 | 0.0% | 16.3% | 17.5% | 17.5% |
| recall@5 | 0.0% | 83.3% | 63.5% | 63.5% |
| recall@10 | 0.0% | 83.3% | 82.3% | 82.3% |
| MRR | 0.000 | 0.813 | 0.352 | 0.352 |

#### `keycloak`

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (baseline) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 10.0% | 5.0% | 10.0% | 10.0% |
| precision@10 | 7.5% | 2.5% | 8.8% | 8.8% |
| recall@5 | 10.6% | 8.8% | 25.0% | 25.0% |
| recall@10 | 17.3% | 8.8% | 39.6% | 39.6% |
| MRR | 0.264 | 0.056 | 0.223 | 0.223 |

### Negative Controls

Questions where `grep` is expected to clearly win (AC-5, AC-26). Reported separately from the headline above, including every place ContextGraph loses -- that is this section's entire purpose. With the fourth side present that expectation is now testable against `grep` itself rather than only against a third-party stand-in for it.

n=4 question(s). Measured: ContextGraph (this project) 4/4; CodeGraph (third-party) 4/4; bash (base-system shell only) 4/4; ripgrep (baseline) 4/4. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) |
|---|---|---|---|---|
| precision@5 | 5.0% | 10.0% | 5.0% | 5.0% |
| precision@10 | 2.5% | 5.0% | 5.0% | 5.0% |
| recall@5 | 25.0% | 37.5% | 25.0% | 25.0% |
| recall@10 | 25.0% | 37.5% | 37.5% | 37.5% |
| MRR | 0.250 | 0.175 | 0.185 | 0.185 |

Per-question breakdown (recall@10, higher is better):

| Question | Repo | derived query tokens | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) | Verdict |
|---|---|---|---|---|---|---|---|
| calcom-q8 | calcom | `packages/features/bookings/lib/handleCancelBooking.ts`, `handleCancelBooking.ts`, `handleCancelBooking`, `calendar/payment` | 100.0% | 0.0% | 0.0% | 0.0% | ContextGraph (this project) leads |
| excalidraw-q8 | excalidraw | `localStorage` | 0.0% | 0.0% | 0.0% | 0.0% | no side found a gold file |
| gin-q8 | gin | `404`, `405` | 0.0% | 100.0% | 100.0% | 100.0% | tie at 100.0% |
| keycloak-q8 | keycloak | `PolicyError` | 0.0% | 50.0% | 50.0% | 50.0% | tie at 50.0% |

### Reproduction

Reproduce this retrieval result, against an already-prepared corpus:

```bash
./gradlew :modules:benchmark:prepareCorpus --args="--repos gin,excalidraw,calcom,keycloak"
./gradlew :modules:benchmark:runRetrieval  --args="--output-dir results/four-way"
```

`runRetrieval` never clones, indexes, or re-indexes anything -- it reads the corpus `prepareCorpus` left behind, which is what lets it observe a corpus another process is still indexing without racing it. `./gradlew build` and `./gradlew check` run neither task.

**This instrument is not self-contained, and a fresh machine cannot reproduce it from this repository alone.** Two things must be installed by hand first:

- `ripgrep` (`rg`) on `PATH`, for the baseline side.
- `@colbymchenry/codegraph` v1.5.0 on `PATH` (`npm i -g @colbymchenry/codegraph@1.5.0`), for the CodeGraph (third-party) side. It was installed on the host rather than vendored into the repository, deliberately, so that producing these numbers needed no package-registry access from the measuring environment. That is a real, accepted cost of the setup and is recorded here rather than left for a reader to discover: without it, the CodeGraph column is absent — and it is absent *visibly*, as a recorded skip, never as a zero.

Without `codegraph`, `prepareCorpus` still succeeds: the third working copy is created and left unindexed, with the reason recorded in that repo's `ingest.json` and echoed to the console.

**bash (base-system shell only) needs none of that, and that is the entire point of it.** `grep` is on the base system already, so the fourth side is the only one of the four that a fresh machine can reproduce with nothing installed — which is precisely the floor the other three are being asked to justify their setup cost against.

<!-- retrieval-axis:end -->
