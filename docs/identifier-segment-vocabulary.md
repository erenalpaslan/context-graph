# Identifier segment vocabulary — the verdict, its cost, and what it's covering for

Written 2026-08-23 for the run at `.harness/runs/2026-08-23-145625-materialise-identifier-segments-at-index/`,
which asked whether materialising CodeGraph's `name_segment_vocab(segment, name)` table — a
dedicated lookup from an identifier's sub-words back to the names that contain them — earns its
place next to what this codebase already does. Every number below is either read from a
committed row in this run's own records or arithmetic on numbers that are. Nothing is estimated.

## 1. The result, in one line

**It ships.** Adding the table (arm A2) raises MRR from the cold baseline's 0.4815 to 0.5556 (+15%
relative), R@5 from 0.3426 to 0.3981 (+16%), and R@10 from 0.3704 to 0.4259 (+15%) — on
excalidraw's nine questions, the only denominator ContextGraph is scored on here (§3). It costs
excalidraw's ingest roughly 15.3 s → 18.7–22.5 s and its index roughly 27.6 MB → 32.1 MB, about
+17%; on Keycloak, measured once at this document's final cost step (§5), it costs 5 m 15.1 s →
6 m 39.1 s (+27%) and 1,550,897,152 B → 1,580,851,200 B (+1.9%). The code is committed at
`9aa58dc`.

**But read §6 before trusting the size of that gain.** A live probe against the shipped code
(re-run and its output saved for this document at `scripts/segvocab/probe-a2-scene.txt` — the
original run of it was never saved anywhere durable) confirms the mechanism §6 describes is
real: full-text search's ranked window can fill with results that are not the caller's best
answer — via a confirmed, unrelated defect *and* via an ordinary pattern the original probe's
own output could not tell apart from that defect — before a segment candidate ever gets a
chance to add one. The measurement is sound and the comparison is fair (every arm was
cold-indexed against the same instrument), but the *mechanism*'s value is not shown to be
independent of what already fills that window, and this run has no data on how much of A2's
gain either contributing cause accounts for. A reader should not leave this document thinking
the gain is the vocabulary table's alone, uncontaminated by what else was going on in the index
it was measured against — nor should a reader take the size of that contamination as known,
because it is not.

## 2. What was actually new here

`SqliteStorageAdapter.ftsLabelFor` (commit `d8dfb18`, already on `main` before this run started)
already splits every node's label into sub-words with `IdentifierSplitter` and writes them into
`nodes_fts.label` at index time — for every node, file nodes included. Segments were therefore
already materialised at index time before this run touched anything. The brief's framing of that
commit as a query-time fix is wrong, and this run's spec says so.

What a dedicated table adds over that is three separable things, each measured on its own arm:

1. **File-node exclusion** — a file's basename duplicates the symbols declared inside it and
   skews segment rarity; `ftsLabelFor` does not exclude it, the table does (arm A1).
2. **An exact segment→name lookup**, instead of segments diluted into one BM25 column where a
   name covering three query words scores barely better than one covering one (arm A2).
3. **Segment rarity** computed from the vocabulary itself, which is what lets a worthless query
   word ("how", "the") be suppressed with no stopword list at all (also arm A2 — rarity is the
   guard that makes the exact lookup usable on prose).

## 3. The measurement, and its real denominator

Five arms, all cold-indexed (database deleted, then rebuilt, before every timed run — this is
what keeps the `nodes_fts` duplicate-row bug from making two indexes of different dirtiness look
like two different code states), all against a private frozen copy of excalidraw's working tree
so a concurrent run touching the shared corpus could not contaminate a row in flight.

**ContextGraph's retrieval denominator is `n = 9`, always** — excalidraw's nine questions.
Keycloak's ContextGraph side is refused by `IndexIntegrityGate` before scoring even starts, over
one gold-cited file missing from its index:
`services/src/main/resources/META-INF/services/org.keycloak.credential.hash.PasswordHashProviderFactory`.
That skip and its reason are recorded in §5, §7 and §12 below — never as a zero, never averaged
into any mean, and never as a column in §4's table, since §4 carries only what every arm actually
has: `n = 9` on excalidraw. Nothing in this run touches extraction or the gate; unblocking it
would widen the denominator to 17 and was explicitly ruled out as a decision this run should not
make as a side effect of an unrelated change (decision D2).

The ship rule, fixed before any arm was measured: **an arm ships only if it beats the cold
baseline (A0c) on MRR without losing R@5 or R@10.** A cheaper arm that already clears the
baseline becomes the comparator for anything more expensive built on top of it, so a costly
table cannot inherit a free change's gain (decision D16) — concretely, arm A1 is nearly free (no
table, no added index size, no added ingest cost) and it does *not* beat A0c (0.4259 < 0.4815, a
regression, unanimous across all 5 cycles), so A0c remains the comparator A2 has to clear.

## 4. The table

Every arm, one row each. **Every ContextGraph retrieval cell states `n = 9`.** A cell that was
not measured for a given arm says so and states why — never blank, never a zero. "Cycles" is the
number of independent cold index+score passes behind that row's retrieval figures.

| Arm | MRR (n=9) | R@5 (n=9) | R@10 (n=9) | Cycles | Excalidraw coverage | Keycloak coverage | Excalidraw ingest | Keycloak ingest | Excalidraw index size | Keycloak index size |
|---|---|---|---|---|---|---|---|---|---|---|
| **A0** as-found — shared-corpus index, no rebuild, pinned `22713e3` | 0.4815 | 0.3426 | 0.3704 | 1 | 21/21 | 25/26 | **not comparable** — 61.1 s / 27,435,008 B is the stale pre-`run C` manifest value already on disk, not a fresh cold measurement | **not comparable** — 71 m 15.75 s / 1,550,958,592 B, same stale-manifest caveat | see ingest column | see ingest column |
| **A0c** cold — pinned `22713e3`, `.contextgraph` deleted and rebuilt | 0.4815 | 0.3426 (mode; 0.3148 in 2 of 8 cycles — see §7) | 0.3704 | 8 | 21/21 | 25/26 | **15,265–16,095 ms** (≈15.3–16.1 s; only 2 of the 8 retrieval cycles also wrote a matching ingest-duration record under this label — the rig's `rows.jsonl` is append-only and carries no third) | **5 m 15.1 s** (315,050 ms) | **27,594,752 B** (≈27.6 MB) | **1,550,897,152 B** (≈1.55 GB) |
| **A1** — file nodes excluded from `ftsLabelFor`'s split, no new table | 0.4259 (regression vs A0c) | 0.3426 | 0.3704 | 5 | 21/21 | not measured for this arm — Keycloak indexing is capped at two runs total for this study (D5); every ablation arm is excalidraw-only by design | 12.3–19.6 s | not measured (D5) | 27,303,936–27,435,008 B (≈27.3–27.4 MB) | not measured (D5) |
| **A2 — SHIPS** — segment table added, candidates re-verified against `nodes`, `ftsLabelFor` left untouched | **0.5556** (beats A0c) | **0.3981** (beats A0c) | **0.4259** (beats A0c) | 5 | 21/21 | **25/26** (this run's final, cold, `9aa58dc` — §5) | 18.7–22.5 s | **399,088 ms = 6 m 39.1 s** (1 cold cycle, `9aa58dc` — §5) | 32,088,064–32,215,040 B (≈32.1–32.2 MB) | **1,580,851,200 B** (≈1.58 GB, §5) |
| **A2-growth** — A2 with the candidate budget widened from `limit − results.size` to a fixed 5 | 0.5556 (identical to A2) | 0.3981 (identical to A2) | 0.4259 (identical to A2) | 5 | 21/21 | not measured for this arm (D5) | 19.3–22.7 s | not measured (D5) | 32,149,504–32,292,864 B (≈32.1–32.3 MB) | not measured (D5) |
| **A3** — A2 minus `ftsLabelFor`'s split (the table alone, no index-time augmentation) | 0.3333 (worse than A0c *and* A2) | 0.2778 | 0.3056 | 5 | 21/21 | not measured for this arm (D5) | 13.9–21.1 s | not measured (D5) | 31,571,968–31,797,248 B (≈31.6–31.8 MB) | not measured (D5) |

A0's ingest/size cells are not a usable "before" figure: as-found means reading whatever the
shared corpus already had on disk, which for excalidraw was 61.1 s / 27,435,008 B — the
*pre*-`run C` optimisation figure `docs/ingest-cost.md` §1 records as `main`'s original 1 m 1 s,
not a controlled cold measurement of the pinned commit this run built A0c from. A0c is the real
"before" — it re-indexed the same pinned commit from a deleted database, and its 15.3 s /
27,594,752 B is what A2's final cost is measured against in §5.

**A0 and A0c are identical on MRR, R@5 and R@10** (0.4815 / 0.3426 / 0.3704, both rows above)
despite A0 reading whatever dirtiness the shared corpus's index already carried and A0c
rebuilding cold. That identity is itself a finding, not just a sanity check: it means the
`nodes_fts` duplicate-row growth these nine questions' full-text matches encounter does not
move retrieval on them one way or the other — contrary to what the brief assumed when it asked
for A0c specifically so a dirty index could not be mistaken for a code-state difference. §6
still applies to A2 (a different code state, different candidates, different window), but on
these questions, at the baseline, index dirtiness alone bought nothing and cost nothing.

## 5. The final cost, at the shipped state

AC-10's "after" half. Both repos, at `9aa58dc` — the commit A2 ships on — cold-indexed from a
deleted database, against slice 03's baseline figures at pinned `22713e3` (§4's A0c row).

**Excalidraw** is A2's own row from §4 (5 cold cycles, product-code-identical to `9aa58dc` — the
snapshot that produced them was built from commit `5384db7`, whose only diff against `9aa58dc`
is the addition of `scripts/segvocab/` itself; `git diff 5384db7 9aa58dc --stat` shows nothing
outside that directory). No separate excalidraw measurement was taken for this section — the
existing five cycles already are the shipped state's cost, so re-running it would not be a new
measurement, only a repeat of one already on record.

**Keycloak** — this run's second and last permitted Keycloak index (decision D5: one before,
slice 03's, one after, this one). `prepareCorpus` cannot be used here: `CorpusPreparationStep.kt`
runs `IndexIntegrityGate.verify` immediately after indexing and before the manifest is written,
so a Keycloak prep pays the full cost and aborts without recording it (decision D6). Cost was
instead captured by driving the CLI's `index` command directly — the same `ReindexPrimitive`,
the same `GraphDb.forLocalWrite` resolver `prepareCorpus` would have used — against a private
copy of Keycloak's clean `without` tree, timed externally with `date +%s%N` around the process
and sized with `stat`, using the exact CLI binary built from `9aa58dc`'s product code (the same
snapshot used for A2's excalidraw rows, since `5384db7`'s product code is `9aa58dc`'s).

| | before (A0c, pinned `22713e3`) | after (A2, `9aa58dc`) | delta |
|---|---|---|---|
| Keycloak ingest | 315,050 ms (5 m 15.1 s) | 399,088 ms (6 m 39.1 s) | +84,038 ms, **+26.7%** |
| Keycloak index size | 1,550,897,152 B (≈1.551 GB) | 1,580,851,200 B (≈1.581 GB) | +29,954,048 B, **+1.9%** |
| Excalidraw ingest | 15.3 s (11.2–16.1 s range) | 18.7–22.5 s | roughly **+22% to +47%** (≈+37% at the mean) |
| Excalidraw index size | 27,594,752 B | 32,088,064–32,215,040 B | **+16.28% to +16.74%** (≈+17%) |

**Read next to the retrieval gain this section is spending against**: excalidraw's MRR rose 15%
relative for a ~17% larger index and ~37% longer ingest; Keycloak's ContextGraph side cannot be
scored at all (§3), so Keycloak's cost here has no retrieval number to sit next to — it is spent
on a repository this run has no evidence the mechanism helps or hurts on (§7).

**Proportionally, Keycloak's size cost (+1.9%) is far smaller than excalidraw's (+17%).**
Consistent with `docs/ingest-cost.md` §8: edges and their indexes already dominate a large
Keycloak graph (56% of the pre-change index), so a new table sized by distinct identifier
sub-words is a much smaller fraction of a much bigger whole. Keycloak's *time* cost (+26.7%) is
closer to excalidraw's, since every one of the 235,152 nodes this index wrote (2 files failed
extraction — a pre-existing tree-sitter bounds bug unrelated to this change, see the run's raw
log) pays the same per-node `writeSegmentVocab` call regardless of the repository's edge density.

**Coverage.** Keycloak: 25/26 (`INDEX_QUERY` basis) — identical to the pre-change baseline,
confirming AC-16 that extraction was not weakened to pay for the added index work. Excalidraw:
21/21, matching every other row in §4. (The result file this Keycloak figure comes from,
`retrieval-1787508392729.json`, also reports excalidraw's MRR as 0.3333 — that is not a second
excalidraw measurement for this document; the private excalidraw index still held A3's build at
the moment this run scored, and only the Keycloak side of that same result file is used here.)

**Disk**, `df -H /`:

| when | avail |
|---|---|
| before the private Keycloak copy was made | 20 GiB |
| mid-index (private copy + partially-built index on disk) | 19 GiB |
| immediately after indexing (private copy + full ~1.58 GB index) | 19 GiB |
| after the private copy and index were deleted | 20 GiB |

Never dropped near the ~4 GiB stop threshold; the private copy and its index were deleted
immediately after duration, size and coverage were captured, before this document's verification
section ran. `scripts/segvocab/check-shared-corpus-unmodified.sh` passed both before and after
this measurement, and again after the deletion — the shared corpus at
`/tmp/claude/benchmark-corpus` was never opened for writing (§11).

## 6. The most important caveat: part of this gain is compensating for what fills FTS's window

`docs/retrieval-improvements.md` and `docs/ingest-cost.md` both name a defect here, and it stays
open and unassigned in this document too: `INSERT OR REPLACE INTO nodes_fts` never actually
replaces, because `nodes_fts` is FTS5 with `id UNINDEXED` and therefore has no unique index for a
conflict clause to target. Every re-upsert of a node **appends a duplicate search row**, so a
node that has been written more than once can occupy several ranks in the same result set. This
is real and confirmed present in this run's own final cold index, not merely asserted from
elsewhere: `nodes_fts` carries 10,602 rows against `nodes`' 10,383 (219 excess), and one id alone
(a `CHANGELOG.md` section) occupies 33 of them by itself
(`scripts/segvocab/probe-a2-scene.txt`).

Slice 07's original liveness probe ran `SqliteStorageAdapter.searchNodes("scene",
types=[Function], limit=3)` against a built A2 index and got back the same *label* three times
("Module | ../scene/Scene"), read at the time as the same node three times exhausting the
`limit` before any other candidate was considered. Re-run for this document
(`scripts/segvocab/probe-a2-scene.txt` — the original's output was never saved anywhere
durable) with the underlying node ids also printed, those three rows turn out to be **three
distinct nodes**: three different import sites (`frame.ts`, `mutateElement.ts`,
`dragElements.ts`), each with its own id, that legitimately share the label "../scene/Scene"
because excalidraw imports that module from many files. That is *not* the `nodes_fts`
id-duplication defect — it is ordinary label collision among genuinely different nodes — and the
original probe's output (type and label only, never an id) could not have told the two apart.
The defect itself is separately confirmed present in the same index (previous paragraph); it
just is not what this specific illustrative example demonstrated, and the original wording
overstated what a type-and-label-only probe had actually shown.

`QueryEngine.buildContext` already narrows the FTS search to a `typeFilter`'d top-N *post hoc*;
either mechanism — a genuinely repeated id, or several distinct nodes that happen to share a
label — can fill that narrowed budget with results that are not necessarily the caller's single
best answer, which is exactly the condition under which the segment vocabulary's appended
candidates (§2's item 2 and 3) get a chance to add anything: the budget opens because the top of
the list was already spent on something that was not the best *distinct* answer, for one reason
or the other.

**This does not invalidate §4's numbers.** Every arm in that table was cold-indexed and scored
against the identical instrument, so the comparison between arms is sound regardless of what is
happening inside any one of them. What it means is narrower and still important: **the
mechanism's measured value is not shown to be independent of what already fills FTS's ranked
window**, and this run has no data on how much either the confirmed duplicate-row defect or the
label-collision pattern above contributes to A2's gain over A0c — both are real, neither is
quantified. Fixing the duplicate-row defect was explicitly out of scope (it is scoped out in the
spec's non-goals, and cold-indexing every arm was the allowed way to neutralise its *growth* for
measurement, not a reason to leave it fixed, and not evidence of how much it matters). Whoever
picks up `nodes_fts`'s duplicate-row bug next should read this section first — and should not
assume the "scene" example above is a demonstration of it.

## 7. What else bounds these numbers

- **Nine questions, one repository, one language, and that is a thin basis for any conclusion.**
  Every ContextGraph retrieval figure in this document is excalidraw only. **The Java side of
  this corpus — Keycloak — has never been scored on our own retrieval at all**; its ContextGraph
  side is gated out before scoring starts (§3), so nothing here says whether a segment vocabulary
  helps or hurts on a codebase with Java's longer, more heavily compounded identifiers, which is
  exactly where this mechanism should matter most.
- **One repository, one corpus, a shared and loaded host.** Every arm ran on the same machine
  as everything else this run and its neighbours were doing at the time; absolute seconds in §4
  and §5 move with that load the way `docs/ingest-cost.md` §7 already documents for this host.
  Five cold cycles were run per arm rather than the one cycle the task originally anticipated,
  specifically to give a real value count instead of a single unrepeated number: A0c 8, A1 5, A2
  5, A2-growth 5, A3 5 (A0 is 1 by construction — it reads whatever the shared corpus already
  had). That is better than one run per row, and it is still not variance bars over an
  independent sample — it is repeats of the same cold rebuild on the same fixed code and corpus.
- **R@5 is not perfectly stable across cold rebuilds of otherwise identical code.** A0c's 8
  cycles read R@5 = 0.3426 in 6 of them and 0.3148 in 2 — a swing worth exactly one question out
  of 27 gold-fact hits, most plausibly BM25 tie-break order following the nondeterministic
  insertion order concurrent extraction produces. A2's own 5 cycles showed no such wobble
  (0.3981 every time), but the baseline's wobble means **A2's R@5 gain (0.3426 → 0.3981) should
  be read against that noise band, not as a number precise to the fourth decimal.** MRR and R@10
  were rock stable across every cycle of every arm measured.
- **The ablation is five points on a small graph, not a sweep of the design space.** Each arm is
  one specific code state; no threshold, budget, or constant was tuned against these nine
  questions (AC-15 — no file path, symbol name, or extension from any question set appears in the
  changed code, and the rarity guard in particular is corpus-derived rather than hand-written;
  see §9). A2-growth is the one deliberate probe at a nearby setting, and its null result is
  reported as a finding in its own right (§8), not folded quietly into A2's row.

## 8. The A2-growth null

Widening the candidate budget from A2's conservative `limit − results.size` (never more
candidates than there is room left after the FTS hits) to a fixed 5 changed **nothing** — not
MRR, not R@5, not R@10, on any of its 5 cold cycles, all identical to A2's own five to four
decimal places. The wider budget was never the binding constraint; the rarity guard and the
re-verification join were. Reported here as the finding it is, not substituted for the
conservative row that actually ships.

## 9. The verdict on `d8dfb18`'s splitting (AC-13)

**It stays.** A2 (table added, `ftsLabelFor`'s split left in place) scores MRR 0.5556; A3 (A2
with the split removed, table alone) scores MRR 0.3333 — a **~40% relative drop**, landing below
even the pre-vocabulary cold baseline's 0.4815. The two mechanisms are complementary rather than
redundant: the split is what lets a name's individual sub-words feed BM25 well enough to seed a
rank at all, and the table is what recovers the tail recall BM25's ranking dilutes away once a
name covers only one or two of a query's words. Removing either on its own costs something the
other was not compensating for — A1 alone regresses (§3), and A3 alone regresses harder.

## 10. What did not ship, and why

- **A1** (file-node exclusion alone, no table): MRR 0.4259 vs A0c's 0.4815 — a regression,
  unanimous across all 5 cycles. R@5 and R@10 were unchanged from baseline. Removing file nodes
  from the split without adding the table's exact lookup and rarity guard cost the split some of
  its own signal without buying anything back.
- **A3** (table without the `ftsLabelFor` split): MRR 0.3333 — worse than A0c *and* worse than
  A2. §9 has the reading.
- **A2-growth**: not worse, not better — a null result, not a ship candidate on its own (§8).

## 11. Verification — recorded, not asserted

**Prohibited-file check**, `scripts/segvocab/check-prohibited.sh main`:

```
=== check 1: git diff --name-only main -- <named files> ===
PASS: all named files byte-for-byte identical to main

=== check 2: working-tree blob hash vs .../scripts/segvocab/prohibited-files-baseline.json's blobs ===
  ok   modules/benchmark/questions/calcom.yaml
  ok   modules/benchmark/questions/excalidraw.yaml
  ok   modules/benchmark/questions/gin.yaml
  ok   modules/benchmark/questions/keycloak.yaml
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/corpus/IndexIntegrityGate.kt
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval/ExpectedFileSet.kt
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval/RetrievalMetrics.kt
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepBaselineRunner.kt
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepProcess.kt
  ok   modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepQueryDeriver.kt

=== check 3: git diff --stat main -- modules/benchmark/src (run A's stronger line) ===
PASS: modules/benchmark/src is byte-for-byte identical to main, and no untracked file sits there
either (no file created, modified or removed)

=== VERDICT: PASS -- all prohibitions hold against main ===
```

(Re-run for this close-out rework: check 2's baseline moved from `.harness/runs/<id>/capabilities.json`
— gitignored, and gone once that run directory is cleaned up — to the tracked
`scripts/segvocab/prohibited-files-baseline.json`, the same ten blob hashes, so this check keeps
working after the run record does not.)

**Shared-corpus check**, `scripts/segvocab/check-shared-corpus-unmodified.sh`, run after every
private index this run built and again at the end:

```
  ok   excalidraw: /tmp/claude/benchmark-corpus/excalidraw/with/.contextgraph/graph.local.db unchanged (size=27525120, mtime=1787489009)
  ok   keycloak: /tmp/claude/benchmark-corpus/keycloak/with/.contextgraph/graph.local.db unchanged (size=1550520320, mtime=1787488612)

VERDICT: PASS -- shared corpus unmodified
```

**No new Gradle dependency.** `git diff main -- '**/build.gradle.kts' build.gradle.kts
settings.gradle.kts` against this branch's tip is empty — no build script changed at all.

**Full diff against `main`**: 17 files, 1,685 insertions, 1 deletion — `core/NodeType.kt` (+18,
the file-type predicate AC-7 needs), a matching `NodeTypeTest.kt` (+21), `SqliteStorageAdapter.kt`
(+180, the write and read paths), the `V6__name_segment_vocab.sql` migration (+30), two new test
files (`SegmentCandidatesTest.kt` +195, `SegmentVocabularyTest.kt` +251), and the measurement rig
itself under `scripts/segvocab/` (+991, this document's instrument, outside `modules/benchmark/`
entirely). Nothing under `modules/benchmark/src` differs from `main` at all (confirmed above).

**Full `check`** (`--continue check`, all 13 modules, 55 actionable tasks: 13 executed, 42
up-to-date): **BUILD FAILED in 3 m 43 s, exactly one failure anywhere** —

```
io.contextgraph.cli.FreshnessTest > FileWatcher: with the watcher enabled, creating a source file updates the graph with no explicit command FAILED
    io.kotest.assertions.AssertionFailedError at FreshnessTest.kt:206

> Task :modules:cli:test FAILED

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':modules:cli:test'.
> There were failing tests. See the report at: file://.../modules/cli/build/reports/tests/test/index.html
```

**Pre-existing, not caused by this change.** `FreshnessTest`'s `FileWatcher` case waits for a
`java.nio.file.WatchService` event after writing a file; this sandbox denies the FSEvents stream
the watcher needs (the same denial `capabilities.json`'s `gradleInSandbox.denialsReproducedHere`
already documents for Gradle's own file-system watching), so the event never arrives. This exact
case was already the sole failure in this run's very first full `check`, run before any product
code existed (task 01's completion record: "final full check ... shows exactly one failure
anywhere: FreshnessTest's FileWatcher case (pre-existing, FSEvents denial)"), and it fails
identically on `main` for the same structural reason — nothing in this change touches the ingest
path, the watcher, or the CLI's freshness command. Every other module's `check` — `core`,
`graph`, `query`, `storage-sqlite` (which carries this change's own new tests), `ingest`,
`extractors`, `tree-sitter`, `mcp-server`, `report`, `visualization`, `eval`, `benchmark` — is
green.

## 12. Non-goals, unchanged

- **`nodes_fts`'s duplicate-row bug is not fixed here.** §6 explains why that matters more than a
  usual non-goal note: this run's headline number is partly explained by it. It stays open and
  unassigned.
- **Keycloak's ContextGraph side is not unblocked.** Extending extraction to cover
  `META-INF/services/` resources would widen the denominator from 9 to 17 questions and is
  tempting, but whether a service registry belongs in a gold set about code structure is a human
  judgement this run declined to make as a side effect of an unrelated change (decision D2).
- **No ranking constants changed.** This change adds a candidate source at the end of
  `searchNodes`'s existing return order (AC-11); it does not touch `QueryRelevance` or anything
  run A's ablation (`docs/retrieval-ranking-ablation.md`) already settled.
- **No configuration flag.** Arms were code states applied and reverted with git throughout
  (decision D14); the shipped code has no way to turn this off short of reverting the commit.

## 13. Reproducing a row

Check out `9aa58dc` (or any arm's commit — see this run's `decisions.jsonl` and
`agent-team/tasks/06-*.md` / `07-*.md` for which commit is which arm) and run:

```bash
scripts/segvocab/measure-arm.sh <label>
```

which prepares the private corpus if needed, builds the CLI and benchmark distributions from
whatever is checked out, cold-indexes excalidraw, scores the retrieval axis, and prints the row.
See `scripts/segvocab/README.md` for the full pipeline and its determinism proof.
