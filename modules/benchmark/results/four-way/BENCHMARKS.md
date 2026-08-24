<!-- retrieval-axis:start -->

## Retrieval Axis (LLM-free, deterministic)

**A separate axis from the agent A/B results above -- do not sum, average, or otherwise mix the two.** The agent axis measures whether a code-graph tool makes a *Claude agent* cheaper/faster/more accurate and requires `ANTHROPIC_API_KEY`. This axis answers a narrower question with no model call anywhere in the loop: asked the same question, which *files* does each tool put in front of you? Deterministic: no LLM call, the same corpus and question set always produce the same numbers (proven by the determinism tests in `RetrievalBenchmarkRunnerTest`, `RipgrepQueryDeriverTest`, `RipgrepBaselineRunnerTest` and `BashBaselineRunnerTest`).

Four sides are compared, and they are named this way everywhere below:

| Side | What it is | What it was asked |
|---|---|---|
| **ContextGraph (this project)** | this repository's own graph, queried in-process through `QueryEngine.buildContext` | the raw question text |
| **CodeGraph (third-party)** | `@colbymchenry/codegraph` v1.5.0, driven as a CLI via `codegraph explore` | the raw question text |
| **bash (base-system shell only)** | `grep` from the stock base system, over a clean, never-indexed checkout — **no third-party tools** are installed, invoked or assumed anywhere in this side | the same derived tokens the ripgrep side is given |
| **ripgrep (third-party)** | plain `rg` over a clean, never-indexed checkout | tokens derived from the raw question text by `RipgrepQueryDeriver` |

**The two graph tools' names differ by two letters, so they are never written bare in a table header, a column label, or a verdict.** "ContextGraph" is this project; "CodeGraph" is the third-party tool being compared against it. A result favouring CodeGraph is a legitimate outcome of this measurement, not an error in it.

So is a result favouring bash. The fourth side exists because the third one is not the floor: `rg` is a separate install that brings its own `.gitignore` awareness, binary skipping and ranking to the fight, so beating it is not the same as beating *nothing*. **bash (base-system shell only)** is what a developer with a stock shell and no installs gets, and it is the honest thing an index has to earn its cost against. Where it wins, that is the finding — printed, not explained away. *Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış bir sayıdır.*

_Generated from retrieval result `retrieval-1787568863031` (schema v3) at 2026-08-24T10:54:23.031605Z. Regenerate by re-running the retrieval measurement; this section is not hand-edited._

### Methodology

For every question, the expected file set is derived from its own gold facts' `file:line` evidence -- never a hand-written second ground truth, and never a per-tool one. All four sides are scored against that same set with precision@k, recall@k, and reciprocal rank (`RetrievalMetrics`, unit-tested against known input/output pairs).

**Fairness invariants.** Each side is handed the same raw `question.text`, with nothing pre-filtered and nothing lifted from the gold facts; each is scored against the same expected set with the same metrics; and no side's output is re-ranked, filtered or truncated before scoring -- each runner projects paths in its tool's own emission order and stops. A number obtained by breaking one of these is worse than no number.

**Absence is never scored as zero.** A question a tool could not be *asked* -- an index that failed its integrity check, a `codegraph explore` call that timed out or errored -- is excluded from that side's average and listed under "Skipped" below, rather than folded in as 0.0. Counting it as zero would blame the tool for an infrastructure failure instead of a retrieval one. A tool that *ran* and returned nothing is a real zero and is counted as one. Each side's table shows how many questions it actually measured, so a smaller denominator is visible rather than implied.

That rule is the rule for **bash (base-system shell only)** too, and is deliberately not relaxed for it. Neither text-search side can be blocked by an index -- both read the working tree -- but a `grep` that exits on a real error still leaves that question unmeasured, and it is excluded exactly as a timed-out `codegraph explore` is. A `grep` that ran clean and matched nothing is a real zero and is counted as one: that is the honest floor this side exists to measure.

`k` = 5, 10: the real gold set's expected-file-set size across all questions has a median of 3 and a maximum of 5, so k=5 is the smallest k at which every question's recall@k can reach 1.0 in principle; k=10 is a softer, twice-as-generous ceiling.

**ContextGraph (this project) side**: `QueryEngine.buildContext(question.text)` against the WITH (indexed) working copy -- the surface method closest to what an agent's own tool call makes (`ContextGraphMcpToolBridge`'s and the MCP server's `build_context` tool both call the same method). The ranked file list is the evidence list's paths, de-duplicated in rank order -- see `ContextGraphRetrievalRunner`.

**This axis has already found and driven one product defect, and it re-measures a second gap from scratch every time it runs. Read both before the numbers below.**

1. **Natural-language queries returned nothing at all -- found here, since fixed.** `buildContext`'s seed search passed the *entire* question sentence to SQLite FTS5 as one literal `MATCH` expression. FTS5 gives bareword queries implicit-AND semantics, so every token had to co-occur in one indexed row; punctuation in the sentence could also be parsed as FTS5 query syntax and throw, and that exception was swallowed into a `label LIKE '%<whole sentence>%'` fallback that could not match either. Measured on excalidraw's real index at the time: full sentence 0 rows, LIKE fallback 0 rows, the same words OR'd 28 rows. The first run of this axis therefore scored ContextGraph 0.0% on precision@5/10, recall@5/10 and MRR across all 22 measurable questions. `searchNodes` now tokenizes the query and OR's the terms as quoted phrases ranked by bm25; the numbers below are from after that fix. This is what the axis is for: it found a defect on the exact path MCP's `build_context` tool uses, and made the repair measurable.

2. **Every repo measured here yielded code declarations -- no ContextGraph (this project) row below is an extraction gap in disguise.** A repo whose language the indexer cannot parse still gets its files read and stored, so its gold-file coverage can read 100% while every query returns nothing; such a row would be measuring **extraction coverage, not retrieval quality**, and this run has none. The census below is the evidence for that claim, not a reassurance about it.

**What each ContextGraph (this project) index actually extracted**, which is the census the paragraph above is computed from:

| Repo | Declaration nodes | Node census (all types, most numerous first) |
|---|---|---|
| `calcom` | 14660 | 40022 `Module`, 8708 `Field`, 6580 `CodeFile`, 6003 `Function`, 5721 `Method`, 3653 `TypeAlias`, 3331 `Concept`, 2987 `Component`, 2521 `Variable`, 1721 `Class`, 1215 `Interface`, 816 `TestFile`, 593 `DatabaseSchema`, 571 `Document`, 533 `Column`, 472 `MarkdownFile`, 130 `Requirement`, 129 `Enum`, 126 `DatabaseTable`, 120 `PackageFile`, 113 `CodeModule`, 79 `ConfigFile` |
| `excalidraw` | 1789 | 3923 `Module`, 1508 `Document`, 1284 `Function`, 1086 `Field`, 451 `CodeFile`, 394 `Concept`, 387 `Component`, 386 `TypeAlias`, 350 `Method`, 289 `Variable`, 91 `Interface`, 89 `TestFile`, 64 `Class`, 46 `MarkdownFile`, 15 `ConfigFile`, 8 `PackageFile`, 6 `CodeModule`, 3 `Enum`, 3 `Requirement` |
| `gin` | 1497 | 882 `Function`, 847 `Document`, 515 `Module`, 473 `Method`, 313 `Field`, 206 `Concept`, 123 `Class`, 111 `Variable`, 100 `Constant`, 56 `CodeFile`, 42 `TestFile`, 35 `Type`, 19 `Interface`, 11 `ConfigFile`, 10 `MarkdownFile`, 7 `Requirement` |
| `keycloak` | 82776 | 105248 `Module`, 72147 `Method`, 27390 `Field`, 8278 `Class`, 7183 `CodeFile`, 5334 `Concept`, 2031 `TestFile`, 1831 `Document`, 1189 `Interface`, 1162 `Function`, 820 `TypeAlias`, 670 `Component`, 304 `Enum`, 194 `Record`, 101 `ConfigFile`, 88 `Requirement`, 82 `MarkdownFile`, 77 `Variable`, 9 `Constant`, 8 `DatabaseSchema`, 8 `PackageFile` |

Declaration nodes are `Function`, `Method`, `Class`, `Interface` -- what a language grammar emits for something it parsed out of a source file. `Document`, `Concept` and file-level nodes are excluded from that total on purpose: a repo the indexer could not parse a line of still accumulates them, so counting them would hide the very gap this census exists to show.

**CodeGraph (third-party) side**: `codegraph explore -- <raw question text>` against a third working copy of the same pinned checkout, indexed by CodeGraph and by nothing else. Driven as a CLI rather than as an MCP server because its own `explore` command routes into the same handler as its `codegraph_explore` MCP tool, which keeps this side deterministic and LLM-free exactly as the baseline is.

Two parsing decisions were made here, and both move the numbers, so both are stated rather than left to be inferred. `explore` has **no `--json` flag**, so the ranked list is parsed out of its markdown.

1. **The ranked files are the ones CodeGraph rendered source for** -- its `Source Code` section, in emission order. Those are the files its own header counts ("Found N symbols across M files") and the ones its output tells an agent to treat as already read. The paths named in the preceding **blast-radius** section are *not* counted: they are annotations about impact on those same symbols. On a representative real response that is 4 ranked files against 7 further paths mentioned only in blast radius, so counting them would raise CodeGraph's recall and lower its precision.
2. **The `### ⚠️ Low-confidence match` sentinel neither truncates the list nor adds to it.** Files above it stay ranked. The section itself contains no file paths at all -- only advisory prose and *directory* hints -- and in v1.5.0 `codegraph explore` cannot emit it in the first place (only its `ContextBuilder` path does, which the CLI does not reach). The parser handles it defensively so a future version routing `explore` through that builder cannot silently truncate a ranked list.

**ripgrep (third-party) side**: `rg -F -w --count -e <token1> -e <token2> ...` against the WITHOUT (clean, never-indexed) working copy. The tokens are never the raw question sentence -- `RipgrepQueryDeriver` is the single place that derives them, extracting quoted spans, identifier-shaped words (camelCase, `snake_case`, dotted symbols, file-like tokens), and bare numeric literals from the question text, exactly what a human reading the question and reaching for `rg` would notice and search for. Files are ranked by matching-line count, descending. A question with no derivable tokens (a purely conceptual question naming no symbol, file, or constant) yields an empty ripgrep result -- reported as such, not padded.

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

What it cost to build each tool's index, so query-time results are read next to the price of getting there. Neither text-search side has an ingest step at all: `bash (base-system shell only)` and `ripgrep (third-party)` both search the working tree directly, and pay nothing before the first query. That is exactly why they are the baselines, and it is the number every index-building row below is being compared against.

| Repo | Tool | Index build time | Index size |
|---|---|---|---|
| `calcom` | CodeGraph (third-party) | 1m 25s | 306.7 MB |
| `calcom` | ContextGraph (this project) | 1m 38s | 268.6 MB |
| `excalidraw` | CodeGraph (third-party) | 17.8s | 36.5 MB |
| `excalidraw` | ContextGraph (this project) | 22.9s | 32.7 MB |
| `gin` | CodeGraph (third-party) | reused existing index | 7.9 MB |
| `gin` | ContextGraph (this project) | 2.7s | 8.2 MB |
| `keycloak` | CodeGraph (third-party) | 1m 53s | 811.6 MB |
| `keycloak` | ContextGraph (this project) | 4m 14s | 1.58 GB |

A row reading _reused existing index_ is one whose index this run found already built and did not rebuild -- the working copy is pinned at a SHA and verified never to change, so an index that exists for it cannot be stale. **That is a statement about this run, not a claim that the index was free.** Whatever it cost belongs to the earlier run that built it and is reported in that run's own result document; it is deliberately not carried forward into this one. A duration written into a result that did not measure it is exactly the figure nobody can later check, and this axis prints the sentinel rather than becoming that.

### Gold-file coverage

How much of each repo's gold-fact-cited file set each tool's index can answer for at all. A tool cannot retrieve a file it never indexed, so a low figure here caps that tool's recall below and should be read *with* the scores, not after them.

**Why this is published rather than gated.** `IndexIntegrityGate` refuses to score the ContextGraph (this project) side of a repo whose index is missing gold-cited files. It applies to that side only. Left unstated, that would bias the comparison toward this project: an incomplete ContextGraph index is dropped from the scores, while an equally incomplete CodeGraph index would be scored. The gate was deliberately **not** loosened -- weakening it to obtain a number is precisely the manufactured result this exercise exists to avoid -- so the remedy is to print both tools' coverage instead. **The residual, stated plainly: a repo failing the gate is still absent from the ContextGraph (this project) column while its CodeGraph (third-party) column is scored.** Coverage makes that visible; it does not make it symmetric.

| Repo | Side | Gold-cited files in index | Coverage | How it was determined |
|---|---|---|---|---|
| `calcom` | ContextGraph (this project) | 19 / 19 | 100.0% | queried the index, file by file |
| `calcom` | CodeGraph (third-party) | 9 / 19 | 47.4% | queried the index, file by file |
| `calcom` | bash (base-system shell only) | 19 / 19 | 100.0% | 100% by construction — reads the working tree directly |
| `calcom` | ripgrep (third-party) | 19 / 19 | 100.0% | 100% by construction — reads the working tree directly |
| `excalidraw` | ContextGraph (this project) | 21 / 21 | 100.0% | queried the index, file by file |
| `excalidraw` | CodeGraph (third-party) | 17 / 21 | 81.0% | queried the index, file by file |
| `excalidraw` | bash (base-system shell only) | 21 / 21 | 100.0% | 100% by construction — reads the working tree directly |
| `excalidraw` | ripgrep (third-party) | 21 / 21 | 100.0% | 100% by construction — reads the working tree directly |
| `gin` | ContextGraph (this project) | 10 / 10 | 100.0% | queried the index, file by file |
| `gin` | CodeGraph (third-party) | 10 / 10 | 100.0% | queried the index, file by file |
| `gin` | bash (base-system shell only) | 10 / 10 | 100.0% | 100% by construction — reads the working tree directly |
| `gin` | ripgrep (third-party) | 10 / 10 | 100.0% | 100% by construction — reads the working tree directly |
| `keycloak` | ContextGraph (this project) | 26 / 26 | 100.0% | queried the index, file by file |
| `keycloak` | CodeGraph (third-party) | 22 / 26 | 84.6% | queried the index, file by file |
| `keycloak` | bash (base-system shell only) | 26 / 26 | 100.0% | 100% by construction — reads the working tree directly |
| `keycloak` | ripgrep (third-party) | 26 / 26 | 100.0% | 100% by construction — reads the working tree directly |

### Skipped

**Nothing was skipped in this run.** The list is empty, and it is printed empty rather than dropped: every table below promises that a short column is listed here, and a section that disappears when it has nothing to say is indistinguishable from one nobody wrote. "Absence is never scored as zero" is only checkable if the absences are enumerated -- including when there are none of them.

Every side measured every question it was given: across 4 repo(s) and 33 question(s), no column's denominator is short of its table's `n`. That is the rule above discharged by the run's own counts rather than by assurance.

### The two text-search columns

**Read this before the tables: every figure below is the same for bash (base-system shell only) and for ripgrep (third-party).** All 45 aggregate figure(s) this document computes for the two -- every metric, in every grouping -- come out identical at the precision it prints. Two columns that read alike are what a wiring fault looks like, so the check is published here rather than left for a reader to suspect.

**The two are computed independently.** They share exactly one thing, on purpose: `RipgrepQueryDeriver` derives the query tokens once and both sides are handed the same list, which is what makes the pair comparable at all. Below that they have nothing in common -- `BashBaselineRunner` spawns the base-system `grep` through `BashProcess` at an absolute path, `RipgrepBaselineRunner` spawns `rg` through `RipgrepProcess`, each parses its own binary's output, and each ranked list is recorded in its own field of the result document. Neither ever reads the other's answer.

**They did not, however, return the same thing.** 12 of 33 question(s) produce ranked lists that are not equal, which is the check that these are two measurements rather than one printed twice. What they share is the part the metrics can see: 30 of 33 have an identical first-10 prefix, and the 3 that do not are `calcom-q3`, `calcom-q5`, `keycloak-q6`. The bash (base-system shell only) side never returns fewer files than the ripgrep (third-party) side, and at most 29 more.

**Why they differ at all -- two causes, not one:**

1. **`rg` never opens files that `grep` reads.** `rg` honours the checkout's own `.gitignore` files and skips hidden entries by default. The flag table above shows the bash (base-system shell only) side is told only to skip `.git` and binary files, because emulating the rest would quietly turn it into a second ripgrep. Anything an ignore rule excludes, or that sits under a hidden directory, is therefore searched by one side and never opened by the other. This is the larger of the two effects and accounts for most of the extra tail.
2. **The two do not mean the same thing by `-w`.** Both sides pass a whole-word flag, but the character classes behind them differ: the base-system `grep` decides word boundaries over ASCII, `rg` decides them over Unicode. An ASCII token butted directly against non-ASCII text is a whole-word match for the first and not for the second, so the *same* file can be counted by both sides with different match counts -- and the ranking is ordered by match count, so it moves. This cause is invisible to a first-10 check: it shifts ranks deep in the tail on questions whose scored prefix is identical, which is precisely how it gets overlooked.

**None of it reaches a scored position.** Every per-question precision@k and recall@k, at every `k` this run measured, is identical on both sides -- including on the 3 question(s) whose first-10 order does differ, where the entries that differ are not gold-cited files at all, and so change nothing that is scored.

Reciprocal rank is the one metric here that is *not* capped at `k`, and it is the only place any difference survives at all: it differs on 4 of 33 question(s) -- `calcom-q2`, `calcom-q3`, `keycloak-q1`, `keycloak-q3` -- where the first gold-cited file sits at rank 80 or deeper, far beyond every `k` measured here. They are carried into the pooled MRR and are the only thing that is -- and not one of them survives rounding to the precision the tables print, which is why the MRR rows read alike too.

**And that is the finding, not a footnote to one.** At k=5 and k=10 on this corpus, the engineering inside `rg` -- its ignore-file awareness, its parallel walk, its tuned matcher -- buys nothing a stock `grep` does not already reach. The floor a developer gets with **nothing installed** is not lower than the floor `rg` sets, so the margins the two index-building sides show over bash (base-system shell only) below are margins over a baseline that was not starved to produce them. That is the whole reason the fourth side was added. *Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış bir sayıdır.*

### How long each side's ranked list is

**precision@10 divides by 10, not by however many files a side returned.** `RetrievalMetrics` counts the unfilled slots as misses -- the standard IR definition, applied identically to every side -- so a side that returns fewer than 10 files carries a cap on that row which no amount of retrieval quality can lift. The sides do not return lists of remotely similar length, so that rule does not fall on them equally.

Over the 29 headline question(s), which is the pool the headline table below is computed from:

| Side | Median files returned | Longest | Returned 5 files or fewer | Gold-cited file at ranks 6-10 |
|---|---|---|---|---|
| ContextGraph (this project) | 11 | 35 | 2 of 29 | 6 of 29 |
| CodeGraph (third-party) | 4 | 8 | 20 of 29 | 0 of 29 |
| bash (base-system shell only) | 72 | 992 | 6 of 29 | 6 of 29 |
| ripgrep (third-party) | 64 | 988 | 6 of 29 | 6 of 29 |

**The shortest lists are CodeGraph (third-party)'s** -- a median of 4 against bash (base-system shell only)'s 72, and 20 of its 29 question(s) return 5 file(s) or fewer. A list that length caps precision@10 at 40.0% before retrieval quality is considered at all.

**One consequence sits in the headline table and looks like something it is not.** recall@5 and recall@10 read the same figure for CodeGraph (third-party) (28.0%). That is not the ranked list running out before rank 10: not one question in this pool places a gold-cited file at ranks 6-10 for that side at all, so raising `k` finds nothing that was not already found. precision@10 is precision@5 scaled by exactly 5/10 for the same reason -- the same hit count over a `k` that is larger -- which makes those two rows arithmetic rather than a second measurement.

The sides whose recall does move between those two rows are the ones that put gold-cited files in that band: ContextGraph (this project) on 6 of 29, bash (base-system shell only) on 6 of 29, ripgrep (third-party) on 6 of 29.

**And here is the half that stops the paragraph above from being an excuse.** A short list caps precision@10, so the question a reader needs answered is how much of the measured spread that cap accounts for -- and it is computable exactly. For one question the cap is the smallest of (files returned, 10, gold-cited files), over 10: the score that side would have got if every file it returned had been a gold one. Averaged over the pool, it is the highest precision@10 its own lists left available to it.

| Side | Highest precision@10 its lists allowed | Measured precision@10 | Share of its own ceiling reached |
|---|---|---|---|
| ContextGraph (this project) | 29.7% | 12.4% | 41.9% |
| CodeGraph (third-party) | 26.6% | 6.2% | 23.4% |
| bash (base-system shell only) | 25.5% | 8.3% | 32.4% |
| ripgrep (third-party) | 25.5% | 8.3% | 32.4% |

The ceilings span 4.2 percentage points, from 25.5% to 29.7%; the measured figures span 6.2 percentage points, from 6.2% to 12.4%. **Scoring each side against its own ceiling, rather than against a flat k=10, leaves them in the same order.** The side with the shortest lists is also the one furthest below what those lists allowed -- 23.4% of its own ceiling, the lowest share of any side here. List length therefore explains part of the spread and not the result: the asymmetry above is real, is published, and does not account for the difference the tables show.

### Headline (GRAPH_HEAVY + NEUTRAL)

Negative-control questions are excluded here on purpose (AC-26) -- see "Negative Controls" below.

n=29 question(s). Measured: ContextGraph (this project) 29/29; CodeGraph (third-party) 29/29; bash (base-system shell only) 29/29; ripgrep (third-party) 29/29. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 20.0% | 12.4% | 11.0% | 11.0% |
| precision@10 | 12.4% | 6.2% | 8.3% | 8.3% |
| recall@5 | 38.7% | 28.0% | 27.0% | 27.0% |
| recall@10 | 46.0% | 28.0% | 35.1% | 35.1% |
| MRR | 0.485 | 0.326 | 0.218 | 0.218 |

**That table is a pooled mean over 29 question(s) drawn from 4 repo(s). It is not a verdict, and it is not a per-repo result.** The repos contribute unequal shares of the pool -- `calcom` 7 of 29 (24.1%), `excalidraw` 8 of 29 (27.6%), `gin` 7 of 29 (24.1%), `keycloak` 7 of 29 (24.1%) -- so one repo's column moving moves every pooled row with it, in rough proportion to that share, whether or not anything changed on any other repo. A row where one side leads here is a lead **on this pool**; whether it is also a lead on each repo in it is a separate question, and this is the answer to it:

_Each figure below is this pool sliced by repo -- the same questions, the same metrics, one repo at a time. That is deliberately **not** the same aggregation as the per-repo tables under "By Repo", which also include each repo's negative controls, so the two will disagree wherever a repo has any. Compared here is like with like; compared across the two sections it is not._

| Headline metric | Leads the pooled row | Where another side leads |
|---|---|---|
| `precision@5` | ContextGraph (this project) | `gin` -- CodeGraph (third-party) leads there, 34.3% against 31.4% for ContextGraph (this project) |
| `precision@10` | ContextGraph (this project) | _leads on every repo measured_ |
| `recall@5` | ContextGraph (this project) | `gin` -- CodeGraph (third-party) leads there, 81.0% against 72.6% for ContextGraph (this project); `keycloak` -- bash (base-system shell only), ripgrep (third-party) lead there, 28.6% against 12.1% for ContextGraph (this project) |
| `recall@10` | ContextGraph (this project) | `keycloak` -- bash (base-system shell only), ripgrep (third-party) lead there, 38.1% against 19.8% for ContextGraph (this project) |
| `MRR` | ContextGraph (this project) | `gin` -- CodeGraph (third-party) leads there, 0.857 against 0.714 for ContextGraph (this project) |

### By Category

#### GRAPH_HEAVY

n=20 question(s). Measured: ContextGraph (this project) 20/20; CodeGraph (third-party) 20/20; bash (base-system shell only) 20/20; ripgrep (third-party) 20/20. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 20.0% | 14.0% | 12.0% | 12.0% |
| precision@10 | 13.0% | 7.0% | 10.0% | 10.0% |
| recall@5 | 30.3% | 24.6% | 19.1% | 19.1% |
| recall@10 | 39.9% | 24.6% | 30.9% | 30.9% |
| MRR | 0.453 | 0.335 | 0.254 | 0.254 |

#### NEUTRAL

n=9 question(s). Measured: ContextGraph (this project) 9/9; CodeGraph (third-party) 9/9; bash (base-system shell only) 9/9; ripgrep (third-party) 9/9. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 20.0% | 8.9% | 8.9% | 8.9% |
| precision@10 | 11.1% | 4.4% | 4.4% | 4.4% |
| recall@5 | 57.2% | 35.6% | 44.4% | 44.4% |
| recall@10 | 59.4% | 35.6% | 44.4% | 44.4% |
| MRR | 0.556 | 0.306 | 0.140 | 0.140 |

#### NEGATIVE_CONTROL

n=4 question(s). Measured: ContextGraph (this project) 4/4; CodeGraph (third-party) 4/4; bash (base-system shell only) 4/4; ripgrep (third-party) 4/4. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 10.0% | 10.0% | 5.0% | 5.0% |
| precision@10 | 5.0% | 5.0% | 5.0% | 5.0% |
| recall@5 | 50.0% | 37.5% | 25.0% | 25.0% |
| recall@10 | 50.0% | 37.5% | 37.5% | 37.5% |
| MRR | 0.313 | 0.175 | 0.185 | 0.185 |

### By Repo

#### `calcom`

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (third-party) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 12.5% | 2.5% | 5.0% | 5.0% |
| precision@10 | 6.3% | 1.3% | 3.8% | 3.8% |
| recall@5 | 33.8% | 3.1% | 5.0% | 5.0% |
| recall@10 | 33.8% | 3.1% | 7.5% | 7.5% |
| MRR | 0.369 | 0.125 | 0.152 | 0.152 |

#### `excalidraw`

n=9 question(s). Measured: ContextGraph (this project) 9/9; CodeGraph (third-party) 9/9; bash (base-system shell only) 9/9; ripgrep (third-party) 9/9. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 22.2% | 8.9% | 4.4% | 4.4% |
| precision@10 | 12.2% | 4.4% | 2.2% | 2.2% |
| recall@5 | 39.8% | 22.2% | 14.8% | 14.8% |
| recall@10 | 42.6% | 22.2% | 14.8% | 14.8% |
| MRR | 0.556 | 0.244 | 0.140 | 0.140 |

#### `gin`

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (third-party) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 30.0% | 32.5% | 22.5% | 22.5% |
| precision@10 | 20.0% | 16.3% | 17.5% | 17.5% |
| recall@5 | 76.0% | 83.3% | 63.5% | 63.5% |
| recall@10 | 92.7% | 83.3% | 82.3% | 82.3% |
| MRR | 0.656 | 0.813 | 0.352 | 0.352 |

#### `keycloak`

n=8 question(s). Measured: ContextGraph (this project) 8/8; CodeGraph (third-party) 8/8; bash (base-system shell only) 8/8; ripgrep (third-party) 8/8. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 10.0% | 5.0% | 10.0% | 10.0% |
| precision@10 | 7.5% | 2.5% | 8.8% | 8.8% |
| recall@5 | 10.6% | 8.8% | 25.0% | 25.0% |
| recall@10 | 17.3% | 8.8% | 39.6% | 39.6% |
| MRR | 0.264 | 0.056 | 0.223 | 0.223 |

### Every Question

Every section above this one is a mean. This one is the run itself: all 33 question(s) it scored, one row each, grouped by repo and ordered by question id -- negative controls included, where the sections above hold them apart. It is here so that a repo's aggregate can be traced to the questions that produced it, and so that one catastrophic miss is distinguishable from a uniformly mediocre spread, which no mean can tell you.

**Each cell is that side's reciprocal rank for the question, then the rank at which it first returned a gold-cited file.** The score is the per-question figure the MRR rows above are the mean of, so a row can be traced to its table; the rank is what the mean erases. "Found it at rank 1", "found it at rank 9" and "never found it at all" are three different results, and every capped metric here renders the last two identically whenever the hit sits past `k`. precision@k and recall@k are deliberately **not** printed per question: at 4 sides and 2 `k` value(s) they are 16 further columns, and a table nobody can read is not a disclosure. They are in this run's own result document (`retrieval-1787568863031.json`), per question, per side, for every reader who wants them.

`n/a` means that side has **no measurement** for that question -- excluded from every mean above, never folded in as 0.0. `0.000 (not found)` means the opposite: that side ran, and nothing anywhere in its ranked list was a gold-cited file. The two are different claims and are printed differently.

Rows are ordered by question id, never by score. A table like this makes every question where ContextGraph (this project) loses much easier to find than the aggregates did, and that is the point of printing it: those rows are here, in their place, formatted exactly like the ones where it wins.

**1 of 33 question(s) were missed by every side that measured them**: `excalidraw-q8`. Not one side put a gold-cited file anywhere in its ranked list -- not below `k`, nowhere at all. A row like that says something about the question, its wording or the gold set it was given, rather than about any of the tools, so it is named here and marked in its own verdict rather than left to be noticed.

#### `calcom` — 8 question(s)

| Question | Category | Gold files | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) | Verdict |
|---|---|---|---|---|---|---|---|
| calcom-q1 | GRAPH_HEAVY | 4 | 0.000 (not found) | 0.000 (not found) | 0.040 (rank 25) | 0.040 (rank 25) | bash (base-system shell only), ripgrep (third-party) tie at rank 25 |
| calcom-q2 | GRAPH_HEAVY | 4 | 0.250 (rank 4) | 0.000 (not found) | 0.005 (rank 199) | 0.005 (rank 193) | ContextGraph (this project) leads |
| calcom-q3 | GRAPH_HEAVY | 4 | 0.200 (rank 5) | 1.000 (rank 1) | 0.007 (rank 145) | 0.007 (rank 140) | CodeGraph (third-party) leads |
| calcom-q4 | GRAPH_HEAVY | 5 | 0.000 (not found) | 0.000 (not found) | 0.500 (rank 2) | 0.500 (rank 2) | bash (base-system shell only), ripgrep (third-party) tie at rank 2 |
| calcom-q5 | GRAPH_HEAVY | 5 | 0.500 (rank 2) | 0.000 (not found) | 0.500 (rank 2) | 0.500 (rank 2) | ContextGraph (this project), bash (base-system shell only), ripgrep (third-party) tie at rank 2 |
| calcom-q6 | NEUTRAL | 1 | 0.000 (not found) | 0.000 (not found) | 0.091 (rank 11) | 0.091 (rank 11) | bash (base-system shell only), ripgrep (third-party) tie at rank 11 |
| calcom-q7 | NEUTRAL | 1 | 1.000 (rank 1) | 0.000 (not found) | 0.000 (not found) | 0.000 (not found) | ContextGraph (this project) leads |
| calcom-q8 | NEGATIVE_CONTROL | 1 | 1.000 (rank 1) | 0.000 (not found) | 0.071 (rank 14) | 0.071 (rank 14) | ContextGraph (this project) leads |

Expected files -- the gold-fact-derived set each row above was scored against:

- `calcom-q1` (4 files): `packages/features/bookings/lib/handleSeats/create/createNewSeat.ts`, `packages/lib/errorCodes.ts`, `packages/prisma/migrations/20220413173832_add_seats_to_event_type_model/migration.sql`, `packages/prisma/schema.prisma`
- `calcom-q2` (4 files): `docs/developing/guides/automation/webhooks.mdx`, `packages/features/webhooks/lib/sendPayload.ts`, `packages/prisma/migrations/20220614090326_add_webhook_secret/migration.sql`, `packages/prisma/schema.prisma`
- `calcom-q3` (4 files): `docs/developing/guides/api/how-to-setup-api-in-a-local-instance.mdx`, `docs/self-hosting/license-key.mdx`, `packages/features/ee/common/server/LicenseKeyService.ts`, `packages/features/ee/deployment/lib/getDeploymentKey.ts`
- `calcom-q4` (5 files): `docs/developing/guides/automation/webhooks.mdx`, `packages/features/bookings/lib/handleNewBooking/getRequiresConfirmationFlags.ts`, `packages/features/bookings/lib/service/RegularBookingService.ts`, `packages/prisma/migrations/20210717120159_booking_confirmation/migration.sql`, `packages/prisma/schema.prisma`
- `calcom-q5` (5 files): `docs/api-reference/v2/openapi.json`, `packages/lib/errorCodes.ts`, `packages/lib/hashedLinksUtils.ts`, `packages/prisma/migrations/20250707145503_add_private_links_expiration_capability/migration.sql`, `packages/prisma/schema.prisma`
- `calcom-q6` (1 file): `packages/prisma/schema.prisma`
- `calcom-q7` (1 file): `docs/self-hosting/database-migrations.mdx`
- `calcom-q8` (1 file): `packages/features/bookings/lib/handleCancelBooking.ts`

#### `excalidraw` — 9 question(s)

| Question | Category | Gold files | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) | Verdict |
|---|---|---|---|---|---|---|---|
| excalidraw-q1 | GRAPH_HEAVY | 2 | 0.500 (rank 2) | 0.000 (not found) | 0.000 (not found) | 0.000 (not found) | ContextGraph (this project) leads |
| excalidraw-q2 | GRAPH_HEAVY | 2 | 0.000 (not found) | 0.200 (rank 5) | 0.000 (not found) | 0.000 (not found) | CodeGraph (third-party) leads |
| excalidraw-q3 | GRAPH_HEAVY | 4 | 1.000 (rank 1) | 0.500 (rank 2) | 0.000 (not found) | 0.000 (not found) | ContextGraph (this project) leads |
| excalidraw-q4 | GRAPH_HEAVY | 3 | 1.000 (rank 1) | 0.000 (not found) | 1.000 (rank 1) | 1.000 (rank 1) | ContextGraph (this project), bash (base-system shell only), ripgrep (third-party) tie at rank 1 |
| excalidraw-q5 | GRAPH_HEAVY | 4 | 1.000 (rank 1) | 1.000 (rank 1) | 0.000 (not found) | 0.000 (not found) | ContextGraph (this project), CodeGraph (third-party) tie at rank 1 |
| excalidraw-q6 | NEUTRAL | 4 | 0.500 (rank 2) | 0.000 (not found) | 0.022 (rank 46) | 0.022 (rank 46) | ContextGraph (this project) leads |
| excalidraw-q7 | NEUTRAL | 1 | 1.000 (rank 1) | 0.000 (not found) | 0.038 (rank 26) | 0.038 (rank 26) | ContextGraph (this project) leads |
| excalidraw-q8 | NEGATIVE_CONTROL | 1 | 0.000 (not found) | 0.000 (not found) | 0.000 (not found) | 0.000 (not found) | **no side found a gold-cited file** |
| excalidraw-q9 | NEUTRAL | 1 | 0.000 (not found) | 0.500 (rank 2) | 0.200 (rank 5) | 0.200 (rank 5) | CodeGraph (third-party) leads |

Expected files -- the gold-fact-derived set each row above was scored against:

- `excalidraw-q1` (2 files): `packages/excalidraw/element/binding.ts`, `packages/excalidraw/element/dragElements.ts`
- `excalidraw-q2` (2 files): `packages/excalidraw/actions/actionDeleteSelected.tsx`, `packages/excalidraw/element/binding.ts`
- `excalidraw-q3` (4 files): `packages/excalidraw/components/canvases/StaticCanvas.tsx`, `packages/excalidraw/renderer/renderElement.ts`, `packages/excalidraw/renderer/staticScene.ts`, `packages/excalidraw/scene/ShapeCache.ts`
- `excalidraw-q4` (3 files): `excalidraw-app/collab/Collab.tsx`, `packages/excalidraw/data/reconcile.ts`, `packages/excalidraw/index.tsx`
- `excalidraw-q5` (4 files): `packages/excalidraw/actions/actionHistory.tsx`, `packages/excalidraw/actions/manager.tsx`, `packages/excalidraw/components/App.tsx`, `packages/excalidraw/history.ts`
- `excalidraw-q6` (4 files): `package.json`, `packages/excalidraw/package.json`, `packages/math/package.json`, `packages/utils/package.json`
- `excalidraw-q7` (1 file): `packages/excalidraw/element/types.ts`
- `excalidraw-q8` (1 file): `excalidraw-app/app_constants.ts`
- `excalidraw-q9` (1 file): `packages/excalidraw/constants.ts`

#### `gin` — 8 question(s)

| Question | Category | Gold files | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) | Verdict |
|---|---|---|---|---|---|---|---|
| gin-q1 | GRAPH_HEAVY | 3 | 0.500 (rank 2) | 1.000 (rank 1) | 0.250 (rank 4) | 0.250 (rank 4) | CodeGraph (third-party) leads |
| gin-q2 | GRAPH_HEAVY | 2 | 0.500 (rank 2) | 1.000 (rank 1) | 0.333 (rank 3) | 0.333 (rank 3) | CodeGraph (third-party) leads |
| gin-q3 | GRAPH_HEAVY | 3 | 1.000 (rank 1) | 1.000 (rank 1) | 0.333 (rank 3) | 0.333 (rank 3) | ContextGraph (this project), CodeGraph (third-party) tie at rank 1 |
| gin-q4 | GRAPH_HEAVY | 4 | 0.500 (rank 2) | 0.000 (not found) | 0.200 (rank 5) | 0.200 (rank 5) | ContextGraph (this project) leads |
| gin-q5 | GRAPH_HEAVY | 3 | 1.000 (rank 1) | 1.000 (rank 1) | 0.500 (rank 2) | 0.500 (rank 2) | ContextGraph (this project), CodeGraph (third-party) tie at rank 1 |
| gin-q6 | NEUTRAL | 1 | 0.500 (rank 2) | 1.000 (rank 1) | 0.200 (rank 5) | 0.200 (rank 5) | CodeGraph (third-party) leads |
| gin-q7 | NEUTRAL | 1 | 1.000 (rank 1) | 1.000 (rank 1) | 0.500 (rank 2) | 0.500 (rank 2) | ContextGraph (this project), CodeGraph (third-party) tie at rank 1 |
| gin-q8 | NEGATIVE_CONTROL | 1 | 0.250 (rank 4) | 0.500 (rank 2) | 0.500 (rank 2) | 0.500 (rank 2) | CodeGraph (third-party), bash (base-system shell only), ripgrep (third-party) tie at rank 2 |

Expected files -- the gold-fact-derived set each row above was scored against:

- `gin-q1` (3 files): `context.go`, `gin.go`, `routergroup.go`
- `gin-q2` (2 files): `gin.go`, `routergroup.go`
- `gin-q3` (3 files): `gin.go`, `routergroup.go`, `tree.go`
- `gin-q4` (4 files): `binding/binding.go`, `binding/json.go`, `context.go`, `deprecated.go`
- `gin-q5` (3 files): `context.go`, `gin.go`, `render/html.go`
- `gin-q6` (1 file): `mode.go`
- `gin-q7` (1 file): `logger.go`
- `gin-q8` (1 file): `gin.go`

#### `keycloak` — 8 question(s)

| Question | Category | Gold files | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) | Verdict |
|---|---|---|---|---|---|---|---|
| keycloak-q1 | GRAPH_HEAVY | 5 | 0.500 (rank 2) | 0.000 (not found) | 0.007 (rank 134) | 0.008 (rank 131) | ContextGraph (this project) leads |
| keycloak-q2 | GRAPH_HEAVY | 3 | 0.000 (not found) | 0.000 (not found) | 0.200 (rank 5) | 0.200 (rank 5) | bash (base-system shell only), ripgrep (third-party) tie at rank 5 |
| keycloak-q3 | NEUTRAL | 5 | 1.000 (rank 1) | 0.250 (rank 4) | 0.012 (rank 81) | 0.013 (rank 80) | ContextGraph (this project) leads |
| keycloak-q4 | GRAPH_HEAVY | 4 | 0.500 (rank 2) | 0.000 (not found) | 0.029 (rank 34) | 0.029 (rank 34) | ContextGraph (this project) leads |
| keycloak-q5 | GRAPH_HEAVY | 3 | 0.111 (rank 9) | 0.000 (not found) | 0.167 (rank 6) | 0.167 (rank 6) | bash (base-system shell only), ripgrep (third-party) tie at rank 6 |
| keycloak-q6 | NEUTRAL | 1 | 0.000 (not found) | 0.000 (not found) | 0.200 (rank 5) | 0.200 (rank 5) | bash (base-system shell only), ripgrep (third-party) tie at rank 5 |
| keycloak-q7 | GRAPH_HEAVY | 3 | 0.000 (not found) | 0.000 (not found) | 1.000 (rank 1) | 1.000 (rank 1) | bash (base-system shell only), ripgrep (third-party) tie at rank 1 |
| keycloak-q8 | NEGATIVE_CONTROL | 2 | 0.000 (not found) | 0.200 (rank 5) | 0.167 (rank 6) | 0.167 (rank 6) | CodeGraph (third-party) leads |

Expected files -- the gold-fact-derived set each row above was scored against:

- `keycloak-q1` (5 files): `server-spi-private/src/main/java/org/keycloak/authentication/AuthenticatorFactory.java`, `server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java`, `services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java`, `services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticatorFactory.java`, `services/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
- `keycloak-q2` (3 files): `model/jpa/src/main/java/org/keycloak/models/jpa/entities/ClientScopeEntity.java`, `model/jpa/src/main/resources/META-INF/jpa-changelog-1.8.0.xml`, `model/jpa/src/main/resources/META-INF/jpa-changelog-4.0.0.xml`
- `keycloak-q3` (5 files): `server-spi-private/src/main/java/org/keycloak/credential/hash/AbstractPbkdf2PasswordHashProviderFactory.java`, `server-spi-private/src/main/java/org/keycloak/credential/hash/PasswordHashProviderFactory.java`, `server-spi-private/src/main/java/org/keycloak/credential/hash/Pbkdf2PasswordHashProvider.java`, `server-spi-private/src/main/java/org/keycloak/credential/hash/Pbkdf2Sha256PasswordHashProviderFactory.java`, `services/src/main/resources/META-INF/services/org.keycloak.credential.hash.PasswordHashProviderFactory`
- `keycloak-q4` (4 files): `docs/updating-database-schema.md`, `model/jpa/src/main/java/org/keycloak/models/jpa/entities/RealmEntity.java`, `model/jpa/src/main/resources/META-INF/jpa-changelog-26.7.0.xml`, `model/jpa/src/main/resources/META-INF/jpa-changelog-master.xml`
- `keycloak-q5` (3 files): `server-spi-private/src/main/java/org/keycloak/authentication/RequiredActionProvider.java`, `services/src/main/java/org/keycloak/authentication/requiredactions/UpdatePassword.java`, `services/src/main/java/org/keycloak/services/managers/AuthenticationManager.java`
- `keycloak-q6` (1 file): `docs/building.md`
- `keycloak-q7` (3 files): `server-spi/src/main/java/org/keycloak/provider/ProviderFactory.java`, `services/src/main/java/org/keycloak/provider/ProviderManager.java`, `services/src/main/java/org/keycloak/services/DefaultKeycloakSessionFactory.java`
- `keycloak-q8` (2 files): `server-spi-private/src/main/java/org/keycloak/policy/LengthPasswordPolicyProvider.java`, `themes/src/main/resources/theme/base/login/messages/messages_en.properties`

### Negative Controls

Questions where `grep` is expected to clearly win (AC-5, AC-26). Reported separately from the headline above, including every place ContextGraph loses -- that is this section's entire purpose. With the fourth side present that expectation is now testable against `grep` itself rather than only against a third-party stand-in for it.

n=4 question(s). Measured: ContextGraph (this project) 4/4; CodeGraph (third-party) 4/4; bash (base-system shell only) 4/4; ripgrep (third-party) 4/4. Where a count is short of n, those questions are listed under "Skipped" — they are excluded from that column's mean, not counted as zero.

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | 10.0% | 10.0% | 5.0% | 5.0% |
| precision@10 | 5.0% | 5.0% | 5.0% | 5.0% |
| recall@5 | 50.0% | 37.5% | 25.0% | 25.0% |
| recall@10 | 50.0% | 37.5% | 37.5% | 37.5% |
| MRR | 0.313 | 0.175 | 0.185 | 0.185 |

Per-question breakdown (recall@10, higher is better):

| Question | Repo | derived query tokens | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) | Verdict |
|---|---|---|---|---|---|---|---|
| calcom-q8 | calcom | `packages/features/bookings/lib/handleCancelBooking.ts`, `handleCancelBooking.ts`, `handleCancelBooking`, `calendar/payment` | 100.0% | 0.0% | 0.0% | 0.0% | ContextGraph (this project) leads |
| excalidraw-q8 | excalidraw | `localStorage` | 0.0% | 0.0% | 0.0% | 0.0% | no side found a gold file |
| gin-q8 | gin | `404`, `405` | 100.0% | 100.0% | 100.0% | 100.0% | tie at 100.0% |
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
