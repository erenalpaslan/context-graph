# Run A — making retrieval ranking a function of the query

Ablation over the seventeen-question gold set, run 2026-08-23 with the instrument merged in PR #3.
Every number below is a measurement from a run whose result JSON is committed alongside it; nothing
is estimated, and the rows that failed are here with the rows that worked.

---

## 1. The result, in one line

**ContextGraph goes from last on every retrieval metric to first on every retrieval metric**, on the
nine questions where all three tools can be compared:

| | MRR | R@5 | R@10 | docs share of top-10 |
|---|---|---|---|---|
| ContextGraph, before | 0.1328 | 0.0278 | 0.0648 | 35.62% |
| ripgrep | 0.1400 | 0.1481 | 0.1481 | 18.18% |
| CodeGraph 1.5.0 | 0.2444 | 0.2222 | 0.2222 | 4.65% |
| **ContextGraph, after** | **0.4815** | **0.3426** | **0.3704** | 33.85% |

3.6× the baseline's MRR, 12× its R@5, 2.0× CodeGraph's MRR and 3.4× ripgrep's. The comparators are
unchanged throughout — they were re-measured on every run of this ablation and returned
the same figure every time, which is what says the instrument is stable and the movement is ours.

The docs share is the exception and section 5 is about it: **it barely moved, and the reason is not
the one the analysis document predicted.**

---

## 2. The ablation

Cumulative, in the brief's order. Every row is a commit on this branch; check it out and re-run the
command in section 7 to reproduce it. Nine questions (excalidraw) on every side — see §6 for why
not seventeen.

| # | configuration | commit | MRR | R@5 | R@10 | docs share | verdict |
|---|---|---|---|---|---|---|---|
| 0 | baseline (`main`, unchanged) | `c4c7436` | 0.1328 | 0.0278 | 0.0648 | 35.62% | — |
| – | prefactor: scoring seam, scores nothing | `539775d` | 0.1328 | 0.0278 | 0.0648 | 35.62% | inert, as intended |
| 1 | **+ relevance in the sort** | `d12c3b9` | **0.3926** | **0.3148** | **0.3148** | 34.67% | **ships** |
| 2 | **+ docs/test de-prioritisation** | `586d5d5` | **0.4259** | 0.3148 | **0.3426** | 32.00% | **ships** |
| 3a | + name ladder, CodeGraph's five tiers | `a62dd6c` | 0.3000 | 0.2593 | 0.3426 | 33.33% | regresses |
| 3b | + name ladder, harmful tier removed | `1e9bf24` | 0.4259 | 0.3148 | 0.3426 | 32.00% | inert |
| 3− | − name ladder removed entirely | `34803f1` | 0.4259 | 0.3148 | 0.3426 | 32.00% | **does not ship** |
| 4 | + kind ladder | `d673a58` | 0.4259 | 0.3148 | 0.3426 | 31.08% | no movement |
| 5 | **+ path relevance** | `b730824` | 0.4259 | **0.3426** | **0.3704** | 32.31% | **ships** |
| 6a | + exact-name supplement, entering first | `b10da99` | 0.3148 | 0.2500 | 0.3426 | **12.36%** | regresses |
| 6b | + exact-name supplement, entering last | `a8dce53` | 0.4259 | 0.3426 | 0.3426 | 22.50% | regresses R@10 |
| 6− | − supplement removed | `3a15af8` | 0.4259 | 0.3426 | 0.3704 | 32.31% | **does not ship** |
| 4− | − kind ladder removed — **shipped** | `91a29a5` | **0.4815** | **0.3426** | **0.3704** | 33.85% | **shipped configuration** |
| ? | interaction check: 6b re-applied to the shipped base | not committed | 0.4815 | 0.3148 | 0.3704 | 24.36% | confirms 6− |

Comparators, identical in every run of this ablation: **ripgrep** 0.1400 / 0.1481 / 0.1481 / 18.18% ·
**CodeGraph** 0.2444 / 0.2222 / 0.2222 / 4.65%.

### The rule, fixed before the numbers were read

**A signal ships only if it improves at least one of MRR, R@5, R@10.** Docs share is a diagnostic —
it explains *why* a configuration behaves as it does — but the brief's question is retrieval
quality, and a signal that trades retrieval quality for a cleaner-looking top ten has not earned
anything. The rule was applied uniformly, and it cost three of the six signals their place. It is
also the rule that would have shipped item 6 if it had been written the other way round, so §5 says
what that alternative would have bought.

Where a signal regressed, exactly **one** hypothesis about the cause was stated in advance and
tested (rows 3b and 6b). That is a diagnosis, not a search: no constant was swept, and no variant
was tried because the first one failed to raise the number.

---

## 3. What shipped

Three signals, all in `modules/query/.../QueryRelevance.kt`, all added to `pageRank × confidence`
rather than replacing it — PageRank on the induced subgraph is ~1e-3 per node, so it survives as
the tie-breaker among candidates the query cannot distinguish and is dominated wherever it can.

1. **The search layer's own ordering reaches the final sort** (item 1). The best hit carries 40
   points, decaying to 40/n for the last of n; a candidate that entered by graph expansion carries
   none. *This one change is three quarters of the whole improvement in MRR* (0.2598 of 0.3487).
   The defect the brief named was real, and it was most of the story.
2. **Documentation and tests lose 15 points**, waived when the query asks about documentation or
   about tests (item 2). Both classes are recognised by general rules over the *words* of a path
   segment — which is how `dev-docs`, `developer_docs` and `api-documentation` fall out of one rule,
   and how `latest.ts` avoids being read as a test.
3. **Path relevance** (item 5): each query word scores once, at the most specific place it reaches —
   filename 10, directory 5, elsewhere in the path 3.

## 4. What did not ship, and why

- **Item 3, the name-match ladder.** CodeGraph's five tiers cost MRR 0.4259 → 0.3000. The cause was
  isolated: the 60-point tier for "a multi-word query contains one word that is exactly this name".
  Their query is a search box's few words; ours is a thirty-word sentence, which shares a common
  word with almost any repository. Asked how a bound arrow's position is recomputed, that tier
  lifted `components/Stats/Position.tsx` over the file that answers the question, on the word
  "position"; asked which component invokes the static-scene render, it put `TopErrorBoundary` and
  `DebugCanvas` at ranks 1 and 2 and pulled `tests/test-utils.ts` into the top five past its own
  −15 test penalty. Removing that tier returned every metric to exactly where it had been — the
  other four tiers are inert on prose, because no sentence is ever equal to, a prefix of, or a
  substring of an identifier. Inert is not an improvement, so none of it ships.
- **Item 4, the kind ladder.** Measured neutral where the brief's order put it, and then *harmful*
  once path relevance was in place: removing it took MRR 0.4259 → 0.4815. A kind bonus of up to 10
  is the same order as a filename hit's 10, so it can outvote where the query's own words fall.
- **Item 6, the exact-name supplement.** Measured three times (entering first, entering last, and
  re-applied to the shipped configuration). It never improved a retrieval metric and cost R@5 or
  R@10 every time. It is also the signal with the clearest reason for failing *here* rather than in
  general: CodeGraph injects at the top of the range **and** differentiates the arrivals with the
  name ladder — the other signal this run removed. The supplement needs a way to tell one
  exact-name match from another, and this project does not have one.

---

## 5. The finding that matters more than the table

**The docs share barely moved, and the analysis document's explanation for it was wrong.**

`docs/retrieval-improvements.md` attributed the 35.6% to PageRank on the induced subgraph rewarding
the documentation clique. Fixing the ordering was expected to clear it. It did not: 35.62% →
33.85%. What actually improved retrieval was ordering the *search hits*, and the documentation that
remains is not expansion noise — **it is the search hits themselves.**

The evidence is a clean split across the nine questions. `IntentClassifier` picks a node-type filter
for the seed search, and:

| intent | questions | docs in the shipped top-10s |
|---|---|---|
| DEVELOPER (seeds: Function, Class, Method, Module) | q1, q3, q5, q6 | **0 of 25 files** |
| GENERAL (no filter) | q4, q7, q8, q9 | 12 of 30 files |
| RESEARCHER (seeds: Concept, Claim, Methodology) | q2 | **10 of 10 files** |

Every documentation file in the entire run is in a question the classifier routed away from the
DEVELOPER filter. q2 is the extreme: it contains the word "referenced", `IntentClassifier` counts
that as a researcher keyword, and the seed search is then restricted to Concept / Claim /
Methodology — node types that, in a code repository, exist almost exclusively inside markdown. All
ten of its slots are prose, and its reciprocal rank is 0.000 before and after everything in this
run. A −15 penalty cannot out-shout the 40 points those seeds carry, and it should not: the defect
there is the seed filter, not the ranking.

**The next change to make is the seed type filter, not another ranking signal.** It is out of scope
here (items 1–6 only) and it is worth more than any of them: four of the nine questions are
currently answered from a candidate set that structurally cannot contain the answer.

The runner-up finding: **item 6 is the only lever that moved the docs share** — to 12.36%. That is a third of the baseline and below ripgrep's 18.18%, though still some way above
CodeGraph's 4.65%. If a future run
decides the docs share is worth buying at the price of R@5, the mechanism exists and is measured;
this run declined to buy it because the brief asked for retrieval quality.

---

## 6. What bounds these numbers

- **One run per row. No repeats, no variance estimate.** Every figure is a single measurement. The
  comparators returning identical figures across fourteen runs bounds the *instrument's* noise at
  zero, but says nothing about how these signals would behave on a different corpus.
- **Nine questions, one repository, one language.** Keycloak's ContextGraph side is refused by
  `IndexIntegrityGate` over one gold-cited file missing from its index — a `META-INF/services/`
  resource. The gate is off limits for this run and re-indexing Keycloak is run C's, at 71 minutes,
  so the ContextGraph column is excalidraw's nine questions. All three sides are reported over the
  same nine, which is a fair comparison but a narrow one. **Nothing here is evidence about Java.**
- **The ablation is one cumulative path, not a search of the space.** Each signal was measured in
  one position of one sequence. That is exactly how item 4 was first recorded as neutral and later
  found to be harmful — the prune caught it, but only because the prune was measured too. Other
  interactions between the removed signals and the kept ones are not ruled out.
- **Constants are CodeGraph's, adopted a priori** (name 80/60/`10+30r`/15/10, kind 10..0, path
  10/5/3, penalty −15), not tuned against this question set. The one constant with no counterpart —
  the 40 points a top search hit carries — was chosen by stated reasoning before anything was
  measured and never adjusted. With nine questions, tuning would be fitting noise.
- **The corpus was being written by another run mid-measurement.** The concurrent ingest-cost run
  re-indexed the shared corpus's excalidraw graph at 10:41 local, during this run's row 1. Rows were
  moved onto a frozen private copy the moment it was detected, and the baseline was re-measured
  against that copy: it reproduced `main`'s published figures digit for digit (0.1328 / 0.0278 /
  0.0648 / 35.62%), which is what says the two indexes were equivalent for these questions. Row 1
  was also re-measured and returned the identical figure on both.

---

## 7. Reproducing a row

From the repo root, having checked out the row's commit:

```bash
./gradlew :modules:benchmark:runRetrieval \
  --args="--output-dir results/ablation-<n> --corpus-root <corpus> \
          --rg-path /opt/homebrew/bin/rg --codegraph-path /opt/homebrew/bin/codegraph"
```

`<corpus>` is `/tmp/claude/benchmark-corpus` for the seventeen-question runs and the frozen
excalidraw-only copy for the ablation rows. Output lands under `modules/benchmark/results/`
(the task's working directory is the benchmark module). The four columns come from
`modules/benchmark/results/ablation/ablation_row.py <result.json>`, which is deliberately outside
the benchmark sources — see §8.

Nothing was re-indexed at any point. Every change in this run is query-time, so the prepared indexes
stayed valid throughout; the 71-minute Keycloak re-index the brief warned about never became
relevant.

Each row's raw result JSON and generated `BENCHMARKS.md` are committed under
`modules/benchmark/results/ablation/priv-*/`.

## 8. The prohibitions, verified rather than asserted

`modules/benchmark/results/ablation/check_prohibited.sh main`, run at review time:

```
$ git diff --stat main -- <the eight prohibited paths>
(no output)
$ git diff --name-only main -- <those paths> | wc -l
       0
```

The paths: `RipgrepQueryDeriver.kt`, `RipgrepBaselineRunner.kt`, `RipgrepProcess.kt`,
`RetrievalMetrics.kt`, `ExpectedFileSet.kt`, `IndexIntegrityGate.kt`, `modules/benchmark/questions`,
`modules/benchmark/questions-set` — all byte-for-byte identical to `main`.

Stronger than asked, and also verified: **no file under `modules/benchmark/src` changed at all**
(`git diff --stat main -- modules/benchmark/src` is empty). The docs-share column is computed by a
script outside the harness precisely so that adding it would not require touching a benchmark
source. The whole diff against `main`, results aside, is four production files and three test files
in `core`, `query` and `storage-sqlite`.

**No gold-set vocabulary in ranking code.** No file path, symbol name or file extension from the
question set appears in `QueryRelevance.kt`. Every rule is stated generally — prose extensions,
documentation-directory *words*, test-directory *words*, the kind ladder over `NodeType` — and the
constants can be read on their own to check that claim. The one place where a general rule and this
corpus coincide is `dev-docs/`, which is caught because `dev-docs` splits to the word `docs`, not
because it is named anywhere.

**No new Gradle dependency**; `./gradlew check` passes, and no benchmark task is wired into it.
