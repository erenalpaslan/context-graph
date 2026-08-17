# Task eval harness — results (slice 19, AC-34)

**No pass/fail threshold is set anywhere in this document.** This is a measurement; deciding
what counts as "good enough" comes after, with evidence. What follows is what happened,
including where `explore` lost.

## Re-run notice (2026-08-17): the previous run was against a broken extractor

The run this document previously reported was taken while `KotlinSymbolExtractor` threw
`StringIndexOutOfBoundsException` on `modules/cli/src/main/kotlin/io/contextgraph/cli/Main.kt`
(a byte-offset-vs-char-offset defect: tree-sitter reports UTF-8 byte offsets, and
`Node.text()` was slicing a char-indexed Kotlin `String` with them) — `IngestPipeline`'s
per-extractor catch silently dropped the whole file, so `cli/Main.kt` had **zero** nodes in
the graph. That bug has since been fixed (`SourceText`/`Node.textIn` in
`modules/tree-sitter/.../LanguageSupport.kt`, a file this slice does not own). This document
reports a fresh run against the fixed extractor. **Verified before re-running the eval:**
indexing this repo now gives `cli/Main.kt` 101 provenance rows, not 0
(`SELECT count(*) FROM provenance WHERE path LIKE '%cli/.../Main.kt'` → `101`).

Everything below — questions, grading, methodology — is unchanged from the original run.
Only the graph being queried is different: same 10 questions, same grading logic
(`Grading.kt`, untouched), same target repo. The old explore numbers are kept alongside the
new ones in the results table and the two loss/win writeups below explicitly say which regime
(broken or fixed extractor) each one came from.

**No `ExploreEngine` code was changed to produce the numbers below**, including after seeing a
result this document did not expect (see the new L4 loss). If a change to `ExploreEngine`
looked warranted, that is flagged here as a follow-up, not made.

## Target repo and setup

The real target repo is **ContextGraph itself** — this worktree, ~260 source files across
11 Kotlin modules — not a fixture. There is no fixture repo big enough for the graph-vs-grep
difference to show (spec non-goals cap the design point at "repos over ~5k source files";
ContextGraph's own repo is a genuine, non-trivial multi-module codebase, just smaller than
that ceiling — see Caveats).

It was indexed fresh via the real CLI (`index` command, unmodified) into a scratch
`.contextgraph/graph.local.db`, **not** the worktree's own `.contextgraph/graph.db` (out of
bounds per file ownership) and not the committed baseline. `litellm.enabled=false` throughout
(the environment default), so module matching in `explore` used its keyword/tokenized
fallback rather than embeddings — this affects the `LOCATE` questions in particular, since
embedding-based module search is what the LOCATE mechanism is designed around.

This re-run's fresh index: 273 artifacts, 3583 nodes, 5951 edges (`cli index` output). The
graph now includes real nodes for every source file the extractors support, including
`cli/Main.kt`.

## The ten questions

Unchanged from the original run. Every `correctAnswer` below was fixed by reading the source
directly, before running either method, so grading is not circular. Full definitions:
`modules/eval/src/main/kotlin/io/contextgraph/eval/EvalQuestion.kt`.

| ID | Type | Question | Correct answer |
|----|------|----------|-----------------|
| L1 | LOCATE | Where is the SQLite-backed implementation of StorageAdapter? | `storage-sqlite/.../SqliteStorageAdapter.kt` |
| L2 | LOCATE | Where does the code read verbatim source text off disk for an explore response? | `query/.../VerbatimSource.kt` |
| L3 | LOCATE | Where are the confidence values for the call-resolution ladder defined? | `core/.../ConfidenceDefaults.kt` |
| L4 | LOCATE | Which file computes per-language rung distribution for resolved Calls edges? | `ingest/.../RungDistribution.kt` |
| I1 | IMPACT | What breaks if `ReferenceResolver.resolveAll`'s signature changes — what calls it? | `ingest/.../IngestPipeline.kt:85` (only caller) |
| I2 | IMPACT | What production code depends on `GraphDb.forLocalWrite`'s seeding behavior? | `cli/.../Main.kt`'s `resolveWriteDbPath()` |
| I3 | IMPACT | What needs to change if ExploreEngine's blast-radius/verbatim-source dependencies change shape? | `mcp-server/.../ExploreEngine.kt` (sole caller of both) |
| C1 | CROSS_FILE | Which class constructs ExploreEngine in production, and what does it pass in? | `mcp-server/.../McpServer.kt` |
| C2 | CROSS_FILE | After IngestPipeline writes nodes/edges, which class runs the second pass resolving Calls edges? | `ingest/.../ReferenceResolver.kt` |
| C3 | CROSS_FILE | Where are module descriptions/embeddings generated, and which CLI command triggers both? | `ingest/.../describe/ModuleDescriptionService.kt` + `ModuleEmbeddingService.kt` |

Spread: 4 LOCATE (module descriptions/embeddings), 3 IMPACT (graded `Calls` edges / blast
radius), 3 CROSS_FILE (two-pass resolution) — per the task file's warning that one question
type "will mislead."

## Results, per question — explore vs grep/read

Grading is automatic and identical for every question: does the response surface the known
answer path, either as a matched symbol's own file, a matched module's path, an edge target,
or a blast-radius hit (`modules/eval/src/main/kotlin/io/contextgraph/eval/Grading.kt`).

The grep/read columns are **carried forward unchanged from the original run**, not
re-measured — see "Why grep numbers are carried forward, not re-done" below for why that is
valid here. The explore columns are entirely new, from the fixed-extractor graph. The
previous explore numbers (broken extractor) are shown alongside for comparison.

| ID | Type | explore hit (new) | explore tokens (new) | via (new) | explore hit (old, broken extractor) | explore tokens (old) | grep/read: hit | grep calls | grep tokens |
|----|------|:---:|---:|---|:---:|---:|:---:|---:|---:|
| L1 | LOCATE | **NO** | 4,691 | — | **NO** | 4,925 | YES | 2 | 229 |
| L2 | LOCATE | YES | 4,605 | symbol | YES | 4,763 | YES | 1 | 69 |
| L3 | LOCATE | YES | 3,314 | symbol | YES | 3,232 | YES | 2 | 174 |
| L4 | LOCATE | **NO** | 2,579 | — | YES | 3,296 | YES | 1 | 67 |
| I1 | IMPACT | YES | 3,771 | edge | YES | 3,816 | YES | 1 | 908 |
| I2 | IMPACT | YES | 4,425 | edge | **NO** | 880 | YES | 1 | 217 |
| I3 | IMPACT | YES | 9,302 | symbol | YES | 9,737 | YES | 1 | 51 |
| C1 | CROSS_FILE | YES | 9,673 | edge | YES | 9,796 | YES | 1 | 108 |
| C2 | CROSS_FILE | YES | 5,819 | symbol | YES | 5,531 | YES | 1 | 278 |
| C3 | CROSS_FILE | YES | 14,898 | symbol | YES | 14,501 | YES | 1 | 138 |
| **Total** | | **8/10** | **63,077** | | **8/10** | **60,477** | **10/10** | **13** | **2,239** |

**The headline hit rate is unchanged: 8/10, both before and after the extractor fix.** What
changed is *which* two questions explore misses: **I2 flipped from a loss to a win** (the
extraction bug is exactly what caused it to lose before — see below), and **L4 flipped from a
win to a loss**, for an unrelated reason discovered only by this re-run (see below). Net
effect on the score: zero. This is reported plainly rather than framed as an improvement,
because it is not one — it is a different 8/10.

Every explore call is still 1 tool call by design. Grep/read tool-call counts are carried
forward from the original hand-run, not re-measured.

## Losses, reported exactly as prominently as wins

**L1 — explore lost, grep won. Same cause as before, still present.** Re-verified against the
fixed-extractor graph: all candidate symbols explore returned were methods of the *interface*
(`core/StorageAdapter.kt`) — the concrete `SqliteStorageAdapter` never appeared as a candidate
at all. Root cause, read from `ExploreEngine.matchSymbols`/`tokenize`: the question's tokens
are `sqlite`, `storageadapter` (camelCase words collapse to one token each). A search for
`storageadapter` matches the interface's own declaration (label `StorageAdapter` lowercases to
exactly `storageadapter`) but not the impl (label `SqliteStorageAdapter` lowercases to
`sqlitestorageadapter` — a whole different token to a search with no substring/prefix
matching). Unaffected by the extractor fix, since `StorageAdapter.kt` and
`SqliteStorageAdapter.kt` were never touched by the `Main.kt` bug.

**L4 — a new loss, not present in the broken-extractor run, and NOT caused by any bug fixed
here.** `RungDistribution.kt`'s own symbols (`RungDistribution`, `compute`, `languageOf`,
`rungOf`) never appear anywhere in the candidate list at all — the miss is not a near-tie,
it is a total absence. What *is* in the top candidates are generically-named symbols from
unrelated files that happen to share a literal token with the question (an `edges` field in
`Freshness.kt`, `EdgeType.Calls`, a `resolved` val in `ReferenceResolver.kt`, `calls` locals in
three test files). Read `ExploreEngine.matchSymbols`/`symbolScore`
(`modules/mcp-server/.../ExploreEngine.kt:270-302`, out of this slice's ownership, not
edited): the token pool this question produces after stopword removal is `file`, `computes`,
`per`, `language`, `rung`, `distribution`, `resolved`, `calls`, `edges` — none of which is a
whole-token match for `rungdistribution` or `compute` (`computes` vs `compute` differ by a
letter; `rung` is a substring of, not equal to, `rungdistribution`, and the search has no
prefix/substring mode). This is the identical shape of bug as L1 — compound-identifier
tokenization — just triggered by a different question. Why it hit *before* and misses *now*
is not fully diagnosed (candidate scoring is a sort over many roughly-tied matches, and 101
new nodes from the `Main.kt` fix changed that competition), and is flagged here as an open
question for a follow-up, not fixed. **This is a design limitation of keyword-only symbol
search, consistent with L1, not a new defect this run introduced.**

**Grep was cheaper on every single question, including the eight explore got right** — often
by one or two orders of magnitude (e.g. C3: 138 tokens via grep vs. 14,898 via explore; C1:
108 vs. 9,673). See Caveats for why this comparison is not entirely apples-to-apples, but the
raw numbers are not softened here.

## Where explore's design showed real value

- **I2 is now a clean win, and the mechanism is exactly the one I1 already demonstrated.**
  With the extractor fixed, `Main.kt` has real nodes, `GraphDb.forLocalWrite` has a real
  incoming edge from `Main.kt`'s `resolveWriteDbPath()`, and explore's top-ranked symbol match
  (`forLocalWrite` in `GraphDb.kt`) surfaced the caller via blast radius (`matchedVia: "edge"`)
  — landing exactly on `Main.kt#resolveWriteDbPath()`, the graded answer. This loss in the
  previous run was a pure artifact of the extraction bug, not of explore's design; fixing the
  bug converted it into exactly the kind of win the edge-traversal mechanism exists for.
- **I1 remains the case the mechanism was built for.** The question never names
  `IngestPipeline` — it asks "what calls this" in English. explore found it via the matched
  symbol's own incoming edge, not text matching. Grep also gets I1 right, but only because
  `resolveAll` is a distinctive enough literal string that a whole-repo grep happens to surface
  the one production call site — a lucky literal match, not impact analysis.
- **One call, everything included.** Every explore hit above delivered verbatim source, all
  resolved edges (with confidence and rung), and blast radius in that single call. Grep only
  delivers file:line; the grep numbers do **not** include a confirmatory Read of the target
  file's actual content (see Caveats).

## Confidence floor sensitivity (blast radius)

Ran twice against the identical (fixed-extractor) graph: `blastRadiusConfidenceFloor=0.80`
(default) and `0.45` (includes every ambiguous rung too).

**Hit/miss was not sensitive to the floor** — identical 8/10, same two questions missed (L1,
L4) at both floors. Token cost *was* sensitive: lowering the floor pulls more edges into
blast radius, adding tokens without changing any answer in this set — e.g. I1 3,771 → 4,291
tokens, C1 9,673 → 9,813, I3 9,302 → 9,482 (misses L1/L4 unaffected either way, since no
blast radius is computed for a symbol that was never a candidate). Same finding as the
original run: on this question set, the floor mainly costs tokens rather than changing
correctness.

## Rung distribution (slice 10's `RungDistribution`, read as-is)

```
kotlin:     repo_unique_name=422  file_imports=410  local_scope=348  same_directory=167
typescript: file_imports=2   local_scope=1
python:     local_scope=1
```

(Previous run: `kotlin: local_scope=306 file_imports=314 same_directory=109
repo_unique_name=383`; `typescript: file_imports=2 local_scope=1`; `python: local_scope=1`.)
Every rung count went up — expected, since fixing `Main.kt`'s extraction added a real file's
worth of call sites and resolution edges that the broken-extractor graph never had. No
java/swift/objc/javascript/tsx `Calls` edges appear, for the same reason as before:
ContextGraph's own repo is pure Kotlin outside `test-fixtures/`.

## Why grep numbers are carried forward, not re-done

The grep/read side of this comparison is a hand-run with no programmatic form (see "How to
re-run" below) — re-running it means re-answering all 10 questions blind, by hand, again.
This re-run carries the original grep numbers forward instead of re-doing that hand-run,
because:

1. **Grep reads bytes off disk, not the graph.** It is structurally immune to the extraction
   bug this re-run exists to correct — nothing about the bug or its fix changes what a
   whole-repo `grep` finds for any of these 10 questions.
2. **None of the 10 questions' ground truths changed.** The files being asked about
   (`SqliteStorageAdapter.kt`, `Main.kt`, `RungDistribution.kt`, etc.) were not touched between
   the two runs — only the tree-sitter extractor's handling of `Main.kt` changed, and grep
   never depended on that extractor.
3. **Re-doing it now would introduce a worse confound than the one already disclosed below.**
   I have now read every one of these files at least twice (writing the original harness, and
   diagnosing L4's failure for this re-run) — a "blind" hand-run today would be less
   representative of a cold read than the original numbers were.

This is a judgment call, not a proof — flagged explicitly rather than left implicit.

## Caveats on this measurement

- **The grep/read baseline was performed by an agent who had already read most of this code
  while building this harness**, and is now carried forward across two runs (see above) — a
  real and growing confound in grep's favor. The tool-call and token counts are not a lower
  bound for a blind agent, and not necessarily reproducible by one.
- **Grep's token counts do not include reading the file afterward.** For most questions,
  correctness was judged from the grep line + file name alone, without opening the file — a
  real task would often still need a Read call to extract the actual implementation, which
  explore's answer already includes inline. This makes the token gap look larger than it
  would be for a fully-substantiated answer.
- **This repo is well within the size where an experienced engineer can hold much of the
  structure in their head** (~260 files) — smaller than the "repos over ~5k source files" the
  spec's non-goals place outside this system's design point. The premise this system exists
  for — a codebase too large to keyword-guess through — does not fully manifest at this
  scale.
- **litellm is disabled**, so `explore`'s module-description/embedding LOCATE path never ran;
  every LOCATE hit above came from the keyword fallback. Whether embeddings change L1 or L4's
  outcome is untested here.
- **Neither of this run's two losses is a known bug with a filed fix.** (Unlike the previous
  run, where I2 was directly attributable to the `Main.kt` extraction crash.) L1 and L4 are
  both instances of the same underlying design limitation — keyword/substring symbol search
  on compound identifiers — not implementation bugs.

Raw output from the run this document reports: `modules/eval/last-run/explore-results-floor0.80.json`
and `modules/eval/last-run/explore-results-floor0.45.json` (the sensitivity check above).
Both are regenerated, not hand-edited, the next time this is re-run.

## How to re-run

Nothing here is fixture-frozen; re-running regenerates real numbers against whatever the
graph currently looks like.

```bash
./gradlew :modules:cli:installDist :modules:eval:installDist

# Index a real target repo into its own scratch overlay (never the worktree's own
# .contextgraph/graph.db — that file is CI-owned and out of this slice's bounds).
mkdir -p /path/to/scratch && cd /path/to/scratch
/path/to/repo/modules/cli/build/install/cli/bin/cli init
/path/to/repo/modules/cli/build/install/cli/bin/cli index /path/to/target-repo

# Run the explore side of the eval against it.
/path/to/repo/modules/eval/build/install/eval/bin/eval \
    /path/to/scratch/.contextgraph/graph.local.db /path/to/target-repo eval-results.json

# Optionally, check blast-radius floor sensitivity:
.../eval/bin/eval <dbPath> <projectRoot> eval-results-045.json 0.45
```

The grep/read side has no equivalent program — answering blind *is* the thing being compared
against — so it must be redone by hand against `EvalTaskSet.QUESTIONS`' known answers,
following the same one-question-at-a-time methodology as above.

## Unit tests

`modules/eval/src/test/kotlin/io/contextgraph/eval/GradingTest.kt` — 8 tests, pure, no
database/network, covering symbol/module/edge/blast-radius matching and the tie-breaking
edge cases that mattered for grading I1 correctly. Verified: `./gradlew :modules:eval:test`
→ `BUILD SUCCESSFUL`, 8/8.
