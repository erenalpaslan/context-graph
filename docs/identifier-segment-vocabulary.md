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
6 m 39.1 s (+27%) and 1,550,897,152 B → 1,580,851,200 B (+1.9%). The code shipped at `9aa58dc`;
a post-ship correctness/performance fix moved that forward without changing these numbers (§14).
A second close-out round (decision D18, §16) then reverted a read-path de-duplication fix that
had shown no measurable benefit anywhere it was tested, and separately kept the rarity guard
after confirming — at a scale these nine questions cannot exercise — that it saves real
wall-clock. That round moved the shipped commit forward again without changing any of the
headline numbers above; §16 has the final commit and its own five-cycle re-measurement.

**Two mechanisms this run checked against real measurements rather than intuition: one earned
nothing and was reverted, the other earned something real and was kept.** A read-path fix that
de-duplicates the ids `searchNodes` reads back from `nodes_fts` (a countermeasure to the
write-path duplicate-row defect §6 describes) was built and measured against both the cold
baseline and A2, five cold cycles each, and changed nothing — not one metric, on either arm, on
any cycle. Because it earned nothing under this run's own ship rule, and the brief's escape
clause for touching `nodes_fts` ("unless it blocks the measurement") never applied, it was
reverted rather than shipped (§15, §16). The rarity guard (`SEGMENT_RARITY_MAX_FRACTION`) was
*also* retrieval-neutral on these nine questions with the guard removed entirely — but a
synthetic, Keycloak-scale measurement built specifically because retrieval metrics cannot see
this dimension (§16) found the guard saves real wall-clock — honestly accounted for the cost of
the guard's own decision query, 7.4×–8.7× cheaper than removing it whenever a query term happens
to be a common identifier sub-word, against a small (~0.03 ms) fixed tax when it has nothing to
catch. The conservative candidate budget bounds how many rows come *back*, but SQLite still has to
do real work to decide what to skip, and on this measurement that work is reliably cheaper than
not deciding at all. The guard stays.

**§6 itself needed a correction, not just a caveat.** An earlier version of this document
credited part of A2's gain to the `nodes_fts` duplicate-row defect, illustrated with a live probe
that was, on re-examination, misread. §6 below is the corrected account: the defect is real, the
probe was not evidence of it, and the separating experiment this run actually ran (§15) found
A2's gain does not depend on the defect either way.

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

## 6. Corrected: the duplicate-row defect is real, but A2's gain does not depend on it

An earlier version of this document credited part of A2's gain to a defect this section names:
`INSERT OR REPLACE INTO nodes_fts` never actually replaces, because `nodes_fts` is FTS5 with `id
UNINDEXED` and therefore has no unique index for a conflict clause to target. Every re-upsert of
a node **appends a duplicate search row**, so a node that has been written more than once can
occupy several ranks in the same result set. `docs/retrieval-improvements.md` and
`docs/ingest-cost.md` both name this defect too, and it stays open and unassigned in this
document as well. **That part of the original claim stands, confirmed present in this run's own
final cold index, not merely asserted from elsewhere:** `nodes_fts` carries 10,602 rows against
`nodes`' 10,383 (219 excess), and one id alone (a `CHANGELOG.md` section) occupies 33 of them by
itself (`scripts/segvocab/probe-a2-scene.txt`).

**What did not hold up is the illustration, and the inference this document originally drew from
it.** Slice 07's original liveness probe ran `SqliteStorageAdapter.searchNodes("scene",
types=[Function], limit=3)` against a built A2 index and got back the same *label* three times
("Module | ../scene/Scene"), read at the time as the same node three times exhausting the
`limit` before any other candidate was considered — offered as a concrete case of the defect
narrowing FTS's ranked window and giving the segment vocabulary's appended candidates room to
add anything. Re-run for this document (`scripts/segvocab/probe-a2-scene.txt` — the original's
output was never saved anywhere durable) with the underlying node ids also printed, those three
rows turn out to be **three distinct nodes**: three different import sites (`frame.ts`,
`mutateElement.ts`, `dragElements.ts`), each with its own id, that legitimately share the label
"../scene/Scene" because excalidraw imports that module from many files. That is *not* the
`nodes_fts` id-duplication defect — it is ordinary label collision among genuinely different
nodes — and the original probe's output (type and label only, never an id) could not have told
the two apart. The defect itself is separately confirmed present in the same index (previous
paragraph); this specific illustrative example simply was not evidence of it in action, and the
original wording overstated what a type-and-label-only probe had actually shown.

`QueryEngine.buildContext` already narrows the FTS search to a `typeFilter`'d top-N *post hoc*;
either mechanism — a genuinely repeated id, or several distinct nodes that happen to share a
label — can fill that narrowed budget with results that are not necessarily the caller's single
best answer, which is exactly the condition under which the segment vocabulary's appended
candidates (§2's item 2 and 3) get a chance to add anything: the budget opens because the top of
the list was already spent on something that was not the best *distinct* answer, for one reason
or the other.

**This never invalidated §4's numbers.** Every arm in that table was cold-indexed and scored
against the identical instrument, so the comparison between arms is sound regardless of what is
happening inside any one of them. What was missing, until this run's own close-out measured it
directly, was the experiment that isolates whether A2's gain actually *depends* on the defect —
rather than reasoning from one probe's misread output either way.

**§15 runs that experiment, and the answer is: A2's gain does not depend on it, measurably.**
De-duplicating the ids `searchNodes` reads out of `nodes_fts` before they consume a result slot —
the direct read-path countermeasure to the defect described above — and re-measuring both A0c and
A2 on top of that fix, five cold cycles each, reproduced both arms' original numbers exactly, on
every cycle: A0c stayed 0.4815 / 0.3426 (mode) / 0.3704 and A2 stayed 0.5556 / 0.3981 / 0.4259. If
the duplicate-row defect were responsible for any material share of A2's gain, removing its
effect on the read path would have narrowed the gap between A0c and A2; it did not narrow at all.
**A2's gain is therefore not attributable to the `nodes_fts` duplicate-row defect, on this corpus
and this question set.**

Because that read-path fix earned nothing measurable, and the brief scoped `nodes_fts` work out
of this run entirely "unless it blocks the measurement" — which this experiment shows it does
not — the fix itself was reverted rather than shipped (decision D18; the revert and the final
re-measurement on top of it are in §16). Fixing the *write*-path defect itself — `INSERT OR
REPLACE INTO nodes_fts` still never replaces — remains out of scope and open (§12); whoever picks
up that bug next now has a direct answer to the question this section used to leave open, on this
corpus and this question set. The "scene" example above stays in this document as the honest
record of what one probe did and did not show, not as evidence for a magnitude nothing here ever
measured.

**This is a correction, not a footnote.** This document originally shipped with the "scene" probe
read as evidence for a claim it did not actually establish. The value of saying so here plainly is
larger than the cost of admitting it: a reader can see that this run checked its own headline
caveat against real evidence and found its first reading of that evidence wrong, before finding,
independently, that the underlying worry does not change the verdict either way.

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

**Full diff against `main`** (recomputed after this document's own close-out edits, so it
includes itself): **21 files, 2,461 insertions, 2 deletions** — `core/NodeType.kt` (+18, the
file-type predicate AC-7 needs), a matching `NodeTypeTest.kt` (+21), `SqliteStorageAdapter.kt`
(+320, the write and read paths plus the close-out rework's SEARCH-vs-SCAN and Unicode-fold
fixes), the `V6__name_segment_vocab.sql` migration (+34), `SqliteStorageAdapterTest.kt` (+18, the
`nodes_fts` read-side de-duplication case added during this close-out window),
`SegmentCandidatesTest.kt` (+235) and `SegmentVocabularyTest.kt` (+255, the two dedicated test
files), this document itself (+440, all thirteen original sections plus §14), and the measurement
rig under `scripts/segvocab/` (13 files, +1,122 — including this close-out's own
`prohibited-files-baseline.json` and the probe re-run's saved output,
`probe-a2-scene.txt`). Nothing under `modules/benchmark/src` differs from `main` at all
(confirmed above).

**Full `check`**, re-run for this close-out rework with `:modules:storage-sqlite:test` and
`:modules:cli:test` forced to actually execute (`cleanTest` first) rather than reused
`UP-TO-DATE` from an earlier invocation (`--continue check`, all 13 modules, 55 actionable tasks:
2 executed, 53 up-to-date once the forced two had already run): **BUILD FAILED in 46 s, exactly
one failure anywhere** —

```
io.contextgraph.cli.FreshnessTest > FileWatcher: with the watcher enabled, creating a source file updates the graph with no explicit command FAILED
    io.kotest.assertions.AssertionFailedError at FreshnessTest.kt:206

> Task :modules:cli:test FAILED

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':modules:cli:test'.
> There were failing tests. See the report at: file:///Users/erenalpaslan/Projects/context-graph/.harness/worktrees/2026-08-23-145625-materialise-identifier-segments-at-index/modules/cli/build/reports/tests/test/index.html
```

`:modules:storage-sqlite:test` executed (not `UP-TO-DATE`) and reported all 60 tests green,
including `SegmentVocabularyTest` (10/10) and `SegmentCandidatesTest` (5/5, the new non-ASCII
reachability case among them). A first attempt at this same re-run, before this forcing, hit a
second, spurious `FreshnessTest` failure
(`ReindexPrimitive: two triggers firing close together...`, a Flyway `ZipException` reading a
JAR mid-write) caused by a concurrent build in this same shared, live worktree — re-running that
one test alone, and then the full `check` again once the concurrent build had cleared, both came
back with only the one pre-existing failure below. Not counted as a finding; recorded here
because it is the same class of interference this section's own build-worktree workaround (§14)
exists to route around, and a reader re-running `check` on a busy host might see it too.

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

### Re-run for close-out round 3 (decision D18)

Every check above was re-run against the tree as it stands after §16's revert and this document's
own edits, at commit `2e815afd81236f8a93e62931f47b5bcf31e04ab1` for the product code (the
document's own commit is necessarily later, since a diff-stat and a `check` result cannot describe
a commit that has not been made yet — the same structural point §14 already made about its own
close-out commit).

**Prohibited-file check**, re-run:

```
=== check 1: git diff --name-only main -- <named files> ===
PASS: all named files byte-for-byte identical to main

=== check 2: working-tree blob hash vs scripts/segvocab/prohibited-files-baseline.json's blobs ===
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

**Shared-corpus check**, re-run:

```
  ok   excalidraw: /tmp/claude/benchmark-corpus/excalidraw/with/.contextgraph/graph.local.db unchanged (size=27525120, mtime=1787489009)
  ok   keycloak: /tmp/claude/benchmark-corpus/keycloak/with/.contextgraph/graph.local.db unchanged (size=1550520320, mtime=1787488612)

VERDICT: PASS -- shared corpus unmodified
```

Both read the same size and mtime this entire run has recorded for the shared corpus from the
start — unmodified across every arm, every cost measurement, and this round's revert.

**Full diff against `main`, recomputed for this round** (git-tracked files only; includes this
document's own edits, §16's two new scripts and saved probe output, and the peer-review
correction to the guard-cost measurement described in §16.2 and §17): **23 files changed, roughly
3,400-3,450 insertions(+), 1 deletion(-)**, as close as this line can get to exact — a diff-stat
that quotes its own file's size necessarily describes the tree from just before the edit that adds
the quote, the same self-reference §14 already flagged for its own diff-stat, compounded here by
a second correction pass after peer review. `SqliteStorageAdapterTest.kt` no longer appears in
this diff at all — the de-duplication pinning case it carried in the previous round was the
entirety of its difference from `main`, and reverting that case (§16.1) brought the file back to
byte-identical with `main`. `SqliteStorageAdapter.kt` drops from the previous round's +320 to
+306, the same `.distinct()`-and-comment removal. `docs/identifier-segment-vocabulary.md` itself
is roughly +1,000 against `main` (up from +440 two rounds ago, reflecting every section added or
rewritten across all three close-out rounds plus §17). Three new files account for the rest of
this round's growth in `scripts/segvocab/`: the synthetic-scale builder and cost-measurement script
(§16.2), and the saved measurement output they produced.

**Full `check`**, re-run for this round with `:modules:storage-sqlite:cleanTest` forced before
`check` (so `:modules:storage-sqlite:test` executes rather than reusing a stale `UP-TO-DATE`
result from before the revert): **BUILD FAILED in 1 m 26 s, 56 actionable tasks (15 executed, 41
up-to-date), exactly one failure anywhere** —

```
io.contextgraph.cli.FreshnessTest > FileWatcher: with the watcher enabled, creating a source file updates the graph with no explicit command FAILED
    io.kotest.assertions.AssertionFailedError at FreshnessTest.kt:206

20 tests completed, 1 failed

> Task :modules:cli:test FAILED

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':modules:cli:test'.
> There were failing tests. See the report at: file:///Users/erenalpaslan/Projects/context-graph/.harness/worktrees/2026-08-23-145625-materialise-identifier-segments-at-index/modules/cli/build/reports/tests/test/index.html
```

The same pre-existing `FreshnessTest` FileWatcher failure as every earlier `check` in this
document, for the same FSEvents-denial reason (previous paragraph) — nothing about this round's
revert touches the ingest path, the watcher, or the CLI's freshness command. `:modules:storage-sqlite:test`
executed fresh (confirmed via its test-result XML timestamps, not reused `UP-TO-DATE`) and
reported **59/59 tests green** — one fewer than the previous round's 60, exactly the removed
de-duplication pinning case, with `SegmentVocabularyTest` (10/10) and `SegmentCandidatesTest`
(5/5) both still green and untouched by this round. Every other module is green.

## 12. Non-goals, unchanged

- **`nodes_fts`'s duplicate-row bug itself is not fixed here.** `INSERT OR REPLACE INTO
  nodes_fts` still never replaces, and the search index still grows on every reindex. §15 built
  and measured a *read*-path countermeasure (`searchNodes` de-duplicating the ids it reads back,
  commit `3294605`) specifically to test whether the write-path defect was inflating A2's gain —
  it was not, measurably (§15) — so the countermeasure itself was reverted rather than shipped
  (§16, decision D18): it earned nothing under this run's own ship rule, and the brief's escape
  clause for touching `nodes_fts` ("unless it blocks the measurement") never applied once the
  measurement showed it did not block anything. The write-path defect that produces the duplicate
  rows in the first place stays open and unassigned, exactly as before.
- **Keycloak's ContextGraph side is not unblocked.** Extending extraction to cover
  `META-INF/services/` resources would widen the denominator from 9 to 17 questions and is
  tempting, but whether a service registry belongs in a gold set about code structure is a human
  judgement this run declined to make as a side effect of an unrelated change.
- **No ranking constants changed.** This change adds a candidate source at the end of
  `searchNodes`'s existing return order; it does not touch `QueryRelevance` or anything an
  earlier ranking-ablation run (`docs/retrieval-ranking-ablation.md`) already settled.
- **No configuration flag.** Arms were code states applied and reverted with git throughout; the
  shipped code has no way to turn this off short of reverting the commit.

## 13. Reproducing a row

Check out `9aa58dc` (or any arm's commit — §4, §14, §15 and §16 name each arm's commit and
dirty-file state inline) and run:

```bash
scripts/segvocab/measure-arm.sh <label>
```

which prepares the private corpus if needed, builds the CLI and benchmark distributions from
whatever is checked out, cold-indexes excalidraw, scores the retrieval axis, and prints the row.
See `scripts/segvocab/README.md` for the full pipeline and its determinism proof.

## 14. Close-out rework: the correctness/performance fix, re-measured (2026-08-23)

Two independent post-ship reviews found defects in `segmentCandidates` (the read path): it
wrapped the indexed `segment` column in SQLite's own `LOWER()` in two places, which disables the
`(segment, name)` primary key — a full `SCAN` + `TEMP B-TREE` where `SEARCH ... USING PRIMARY
KEY` was available (measured at Keycloak scale: ~1.5 s per call, ~99% avoidable, and
`ExploreEngine.matchSymbols` pays this once per query token). Worse, because SQLite's `LOWER()`
folds ASCII `A`-`Z` only while Kotlin's `String.lowercase()` is full Unicode, every non-ASCII
uppercase identifier segment was permanently unreachable regardless of query casing. Fixed by
lower-casing the segment once, in Kotlin, at write time (`writeSegmentVocab`) and comparing with
a plain `segment IN (...)` at read time — lossless, since `segment` is never read back out as
text, and symmetric with the query side in a way `COLLATE NOCASE` (itself ASCII-only) would not
have been.

`EXPLAIN QUERY PLAN`, both affected queries, before → after:
`SCAN name_segment_vocab` / `SCAN v` → `SEARCH ... USING PRIMARY KEY (segment=?)`. A new test
(`SegmentCandidatesTest`, non-ASCII reachability) proves the fix with a Cyrillic segment, seeded
directly since `IdentifierSplitter`'s own separator regex is ASCII-only and cannot itself produce
a non-ASCII segment from a real identifier — a separate, pre-existing limitation this fix does
not touch.

While in that function: `minConfidence`, a caller-supplied `Double`, was interpolated into raw
SQL text — `NaN`/`Infinity` render as a bare identifier and throw a SQL parse error out of an
unguarded `exec`, unlike the FTS `MATCH` path beside it, which is wrapped and logged. Moved to
the Exposed re-fetch that already re-reads every row. The four-times-repeated
`joinToString(",") { "'${sqlQuote(it)}'" }` pattern was extracted into one `sqlInList` helper.
Ordering the candidates by accepted-segment match count descending was tried and **measured** —
found to regress R@5 and R@10 below the cold baseline, and rejected on that measurement rather
than on the intuition that motivated it (§17 elevates why this matters beyond this one fix). MRR
0.5000 / R@5 0.3148 / R@10 0.3426, reproducible identically across 5 cold cycles, against baseline's
0.4815 / 0.3426 / 0.3704), so `ORDER BY n.id` (A2's original) was kept, with a comment recording
that the alternative was tried and why it was rejected.

**Because this changes the shipped code, A2's row in §4 no longer traces to what ships (D14).**
Re-measured with the same rig, five cold cycles each, both reproducing A2's original numbers
exactly:

| label | commit | MRR | R@5 | R@10 | dirty | notes |
|---|---|---|---|---|---|---|
| `a2-final` (×5) | `4e341d149a667aebdb5479c54807c406110af227` | 0.5556 | 0.3981 | 0.4259 | 0 | ordinary live-worktree build |
| `a2-final-pinned` (×5) | `95cb23b11b846c784a0b4245db87dac2eecab05c` | 0.5556 | 0.3981 | 0.4259 | 0 | built from an isolated, pinned `git worktree` after a concurrent build in the shared live worktree corrupted one prior attempt (`SQLITE_TOOBIG`, garbage bytes in a log line) — see this rig's README, "When the live worktree won't compile" |

Identical to A2's original 0.5556 / 0.3981 / 0.4259 on every one of the 10 cycles above, at both
commits measured. The fix is real (`SEARCH` replaces `SCAN`; a non-ASCII segment is now
reachable), but on excalidraw's nine ASCII-identifier questions it changes nothing about which
candidates are proposed or in what order — exactly what a comparison-and-Unicode-only, no
ranking-shape change should do.

This repository saw further commits after `95cb23b` during this same close-out rework (this
document's own corrections among them, plus independent fixes to `nodes_fts` read-side
de-duplication, this migration's citations, and test-comment accuracy) — a shared, actively
worked-on host, consistent with §7's caveat about this run's conditions generally.
`git diff 95cb23b HEAD -- modules/storage-sqlite/src/main/kotlin/io/contextgraph/storage/SqliteStorageAdapter.kt`
was checked immediately before this document was finalised and carried no line outside a comment,
so the measurement above still describes `HEAD`'s actual behaviour at the commit named in §11's
verification block, even though that commit's hash is later than the two measured above.

**One caveat on the two rows above, resolved by §15 rather than restated here:** `95cb23b`
already contains the `nodes_fts` read-side de-duplication fix §15 describes — it was committed
(`3294605`) before `95cb23b`, not after — so neither `a2-final` nor `a2-final-pinned` is a
measurement of A2 *without* that fix. Both rows are correct as measurements of what shipped at
that point; they are not, on their own, evidence about what the de-duplication fix changed. §15
is the section that isolates that question with its own dedicated arms, and §16 records that the
fix was subsequently reverted once §15's own measurement showed it earned nothing — this
section's `SEARCH`-vs-`SCAN` and Unicode-fold fix is a separate change and is unaffected by that
revert; both `a2-final` rows above remain valid measurements of the commits they name, they are
simply no longer measurements of `HEAD`.

## 15. The separating experiment and the guard-removed arm (second close-out round, 2026-08-23)

Two more experiments this rework round requires before the caveats above can be resolved rather
than merely restated: does any of A2's gain trace to the `nodes_fts` duplicate-row defect (§6),
and does the rarity guard (§2 item 3) contribute anything measurable on this corpus? Both are
answered here, both with a null result, and both nulls make the shipped verdict more solid, not
less — a gain that survives removing a real confound, and a guard whose absence changes nothing,
are both good news for the arm that ships.

**The separating experiment.** For the duration of this experiment, `searchNodes`'s FTS branch was
changed to de-duplicate the ids it reads back from `nodes_fts` before mapping them to nodes
(`.distinct()`, keeping the first — highest-ranked — occurrence of each id), committed at
`3294605` and described in that commit's own message: a read-path countermeasure to the
write-path defect in §6, kept in its own commit exactly so it can be isolated like this (and, per
§16, later reverted on exactly the strength of what this experiment found). `3294605` was
committed *before* `95cb23b`, so it was already part of both `a2-final` rows in §14 above (the
caveat immediately above this section says so) — meaning neither of those two rows, nor a build of
`HEAD` at that point in the run, could show what A2 looked like *without* the fix. The genuinely
new comparison this experiment needed is on the other side: what
does the **baseline** (no segment vocabulary at all) look like with only the de-duplication patch
applied, isolated from every other change on this branch? That state does not exist anywhere else
in this document, so it was built for this section alone: the pinned pre-feature commit
(`22713e3`) with only the one-line de-duplication patch applied on top, in an isolated `git
worktree` per this rig's README (never touching the live branch, never committed). Five cold
cycles, same corpus, same protocol as every other arm in §4:

| label | code state | MRR | R@5 | R@10 | Cycles | Excalidraw ingest | Excalidraw index size |
|---|---|---|---|---|---|---|---|
| `a0c-dedup` | pinned `22713e3` + the de-duplication patch only, no segment vocabulary, applied in an isolated worktree, never committed | 0.4815 | 0.3426 (4/5 cycles; 0.3148 in 1/5 — the same noise band §7 already documents for A0c) | 0.3704 | 5 | 6.2–8.2 s | 27,488,256–27,529,216 B |
| `a2-dedup` | `95cb23b` (the same commit as §14's `a2-final-pinned`, which already carries the de-duplication fix — see above), ordinary live-worktree build, re-measured here as an independent confirmation rather than a fresh isolation | 0.5556 | 0.3981 | 0.4259 | 5 | 6.3–7.5 s | 32,645,120–32,780,288 B |

**`a0c-dedup` is identical, metric for metric, to A0c's own un-de-duplicated 0.4815 / 0.3426
(mode) / 0.3704 (§4).** De-duplicating the FTS read path changed nothing on the baseline side, on
any of its five cycles. `a2-dedup` reproduces §14's own `a2-final-pinned` row exactly (as it
should — same commit), which is itself the confirmation on the A2 side: A2's *original*, entirely
pre-de-duplication measurement (§4, commit `9aa58dc`) already reads 0.5556 / 0.3981 / 0.4259, the
identical figures A2 reads *with* de-duplication applied (§14, §15). De-duplicating the read path
therefore changed nothing on **either** side of the comparison this experiment needed, which is a
direct answer to §6's open question: if the duplicate-row defect were responsible for any
material share of A2's gain over the baseline, removing its effect on the read path would have
narrowed the 0.4815 → 0.5556 gap; it did not narrow it at all, on either endpoint. **A2's gain is
not attributable to the `nodes_fts` duplicate-row defect, on this corpus and this question set.**
The defect is still real (§6's counts stand — 219 excess rows, one id repeated 33 times), and the
write-path bug that produces it is still open (§12); it simply is not where this measurement's
headline number comes from.

**The guard-removed arm.** `SEGMENT_RARITY_MAX_FRACTION` (§2 item 3, corrected in this rework to
say what it actually catches — a handful of high-frequency domain words, not a stopword
substitute) was set to `1.0` — disabling the guard entirely, since `reached / totalNames <= 1.0`
is then true for every segment with at least one match — in the same kind of isolated,
throwaway `git worktree` as above, on top of `HEAD`. Five cold cycles:

| label | code state | MRR | R@5 | R@10 | Cycles | Excalidraw ingest | Excalidraw index size |
|---|---|---|---|---|---|---|---|
| `a2-guard-removed` | `HEAD` (`95cb23b`) with `SEGMENT_RARITY_MAX_FRACTION = 1.0`, applied in an isolated worktree, never committed | 0.5556 | 0.3981 | 0.4259 | 5 | 6.4–7.1 s | 32,743,424–32,804,864 B |

**Identical to A2 on every metric, every cycle.** The guard was live and had the opportunity to
matter: `element` — the guard's largest catch, reaching 359 of excalidraw's 4,764 distinct names,
well past the 238-name cut — appears as a literal word in the raw text of three of the nine
questions (`excalidraw-q1`, `-q3`, `-q4`). Removing the guard did not change which candidates
made it into the scored top-k anyway. Read together with the A2-growth null (§8) — the candidate
budget past a full FTS page is usually zero, because the FTS branch usually fills the page on its
own — the same explanation applies here: the conservative candidate budget, not the rarity
guard, is the binding constraint on this corpus. On a corpus or question set where the FTS branch
returns a shorter page (more budget left for segment candidates), the guard could plausibly bind
where it does not here; this run has no data on that case, and does not claim to.

**Reading §1's headline against both nulls.** Neither experiment moves the ship decision for A2
itself: A2 still beats A0c on MRR without losing R@5 or R@10, unconditionally, and the two
mechanisms this close-out round measured — the duplicate-row defect's contribution, and the
rarity guard's contribution on these nine questions — both come back at zero on this corpus. The
gain is not a measurement artefact of either one. But a null result is still a result, and this
run's ship rule applies to each mechanism on its own record, not just to A2 as a whole: the
de-duplication fix earned nothing anywhere it was measured (retrieval, here) and was reverted
rather than kept as dead code (§16, decision D18). The rarity guard also earned nothing on
retrieval here, but — unlike the de-duplication fix — retrieval is not the only dimension a code
change can earn its place on; §16 measures the guard on a dimension these nine questions cannot
exercise (candidate-set size and latency at Keycloak scale) and finds it does earn something real
there, so it stays. **The verdict in §1 for A2 strengthens accordingly: it ships, and neither of
the two caveats this document carried is a live discount on that gain any more — and each of the
two side-mechanisms this round checked was judged on its own measured merit, not carried along for
free.**

**Provenance, verification, and what this measurement does and does not have.** All three new
arms' cold-index and retrieval steps ran through the unmodified rig (`scripts/segvocab/measure-arm.sh`);
`check-shared-corpus-unmodified.sh` passed before, during and after every one of them, confirming
the shared corpus at `/tmp/claude/benchmark-corpus` was never opened for writing. `a0c-dedup` and
`a2-guard-removed` are built from isolated, detached `git worktree`s and were never committed to
this branch — the patches that produced them exist only for the duration of the measurement, per
this rig's README's own guidance for building a pinned or patched state without touching the live
tree. `a2-dedup` is an ordinary live-worktree build of a real commit already on this branch. None
of the four arms above added, removed or reordered a query term, file path or symbol name from
any question set — the de-duplication fix and the guard's disabling constant are both general
code changes, not tuned to these nine questions.

## 16. Close-out round 3 (decision D18): the de-duplication reverted, the rarity guard cost-tested and kept

§15's two experiments both came back null on retrieval. This section acts on them: one null
result is grounds to revert the code that produced it, the other is grounds to measure a
dimension retrieval cannot see before deciding anything — and once that measurement ran, it
changed the decision.

### 16.1 The de-duplication fix: reverted

The brief scoped `nodes_fts` work out of this run entirely, "unless it blocks the measurement."
§15's separating experiment is the direct test of that clause: de-duplicating the FTS read path
and re-measuring both A0c and A2, five cold cycles each, moved neither, on any metric, on any
cycle. The escape clause never applied — the read-path fix does not block the measurement, so it
does not belong in this run's diff. Keeping a change with no measured benefit, on any arm, on any
dimension this document has data for, is exactly the dead code path this run's own decisions warn
against.

Reverted: `searchNodes`'s FTS branch back to plain `ids` (no `.distinct()`), the same line
`4e341d1` already held before `3294605` added the call, plus the KDoc-style comment that
justified it; and `SqliteStorageAdapterTest`'s "searchNodes with duplicate nodes_fts rows" case,
which existed only to pin the reverted behaviour. **The write-path defect itself is untouched and
stays open and unassigned** — `INSERT OR REPLACE INTO nodes_fts` still never replaces (§6, §12).

### 16.2 The rarity guard: cost-tested at Keycloak scale, and kept

§15 established the guard is retrieval-neutral on these nine questions. That is not the same
question as whether it is *worthless* — the note in this run's own brief is exactly right that the
budget (`limit - results.size`) already bounds how many candidates *return*, so the guard can never
change result *size*, only what work gets done to decide the result: whether a common segment's
posting list is walked and sorted through the full `JOIN ... DISTINCT ... ORDER BY n.id`
machinery, or whether that walk is replaced with a cheaper aggregate that decides to skip the join
altogether. Excalidraw's ~5,000-row vocabulary is too small for that cost to be visible — this
document's own KDoc-quoted numbers already put the scale where it matters at Keycloak's ~600K
rows — and D5 caps this run at two real Keycloak indexes, both spent, with nothing here touching a
cost column that would justify a third. So the measurement was built synthetically instead.

**The synthetic database.** `scripts/segvocab/guard-cost-build-synthetic.py` builds a scratch
SQLite database with the real V6 schema (`name_segment_vocab`, `WITHOUT ROWID`, PK
`(segment, name)`; `nodes` with `idx_nodes_label`, matching `V1__init.sql`), no `ANALYZE` (grepped:
production never runs one either), and a deliberately skewed frequency distribution instead of a
uniform one — 600,000 `(segment, name)` rows (the same order this codebase's own
`cachedTotalVocabNames` KDoc already cites for Keycloak scale) over ~120,000 distinct names, with
five Java/Keycloak-flavoured segments (`service`=32,000, `get`=27,000, `config`=21,000,
`provider`=16,000, `user`=12,500 — all comfortably over the 5% cut, which lands at 5,962 names at
this scale) and a long tail of tens of thousands of segments reaching from a couple of hundred
names down to a single one, the same shape this document's KDoc already reports for excalidraw at
1/25th the scale (`element` reaching 359 of 4,764 names). One node per name (1:1) is the
conservative floor for this measurement: it cannot make the join artificially cheaper than reality
would by collapsing lookups onto fewer distinct labels than a real corpus has.

**The measurement, corrected.** An earlier pass of this section timed only the join query on both
sides — `join(accepted terms)` for guard-on against `join(all terms)` for guard-off — and reported
ratios up to ~3,400×. That comparison was not honest about what production actually runs:
`segmentCandidates` always computes the reach query (`namesReached`, the
`SELECT segment, COUNT(DISTINCT name) ... GROUP BY segment` that decides which terms the guard
accepts) *unconditionally*, before the join ever runs — there is no code path today where the join
executes without it. So the guard's true cost is `reach_query(all terms) + join(accepted terms)`,
not the join alone; timing only the join let the guard-on number skip the very query that buys it
its answer. "Guard off," properly, is what the code looks like if `SEGMENT_RARITY_MAX_FRACTION`
and its use are removed entirely (D18's stated fallback) — no reach query at all, since nothing
downstream would need that statistic — so `join(all terms)` alone is the fair guard-off number.
`scripts/segvocab/guard-cost-measure.py` was rewritten to time both queries separately and report
the honest totals; the version below is that rewrite's output, not the original. Same five query
shapes as before: a single common term alone, a realistic mix of one common term and five rare
ones (at both `budget=5` and the `ExploreEngine.matchSymbols` ceiling of `budget=25`), a stress
case of three common terms, and an all-rare query where the guard has nothing to catch. Timed with
Python's `sqlite3` module (median of 25 repetitions per query, page cache warmed first). Full
output saved at `scripts/segvocab/guard-cost-probe.txt`.

| scenario | budget | rows considered, guard OFF (join only) | rows considered, guard ON (reach + join) | wall-clock median, guard OFF | wall-clock median, guard ON (reach + join) | ratio (on ÷ off) |
|---|---|---|---|---|---|---|
| single common term ("get") | 5 | 27,000 | 27,000 (all in the reach query; join is a short-circuit) | 12.46 ms | 1.55 ms | **0.12× — guard 8.0× cheaper** |
| single common term ("get") | 25 | 27,000 | 27,000 | 11.97 ms | 1.55 ms | **0.13× — guard 7.7× cheaper** |
| mixed: 1 common + 5 rare | 5 | 27,206 | 27,412 | 12.99 ms | 1.71 ms | **0.13× — guard 7.6× cheaper** |
| mixed: 1 common + 5 rare | 25 | 27,206 | 27,412 | 12.51 ms | 1.70 ms | **0.14× — guard 7.4× cheaper** |
| stress: 3 common + 2 rare | 5 | 80,006 | 80,012 | 40.67 ms | 4.67 ms | **0.11× — guard 8.7× cheaper** |
| all-rare (nothing to suppress) | 5 | 206 | 412 | 0.114 ms | 0.140 ms | **1.22× — guard ~0.03 ms costlier** |

Two things this table makes visible that the first pass did not:

**Rows considered stops being a reliable proxy for cost the moment the query shapes differ.** In
four of six scenarios guard-ON's *total* rows considered (reach + join) is equal to or slightly
*higher* than guard-OFF's (e.g. the mixed scenario: 27,412 vs 27,206) — the reach query and the
join both walk the common segment's full posting list, so the guard does not reduce rows touched
overall, it changes what kind of work is done per row. `EXPLAIN QUERY PLAN` shows why: the reach
query is `SEARCH name_segment_vocab USING PRIMARY KEY (segment=?)` alone — one table, an aggregate
`COUNT`, no second index lookup, no sort — while the join additionally does
`SEARCH n USING INDEX idx_nodes_label (label=?)` per row (a second B-tree descent) and
`USE TEMP B-TREE FOR DISTINCT` to materialise `DISTINCT n.id ORDER BY n.id` before `LIMIT` can trim
it. Walking a posting list once for a cheap aggregate and conditionally skipping the expensive
join is cheaper than walking it once for the expensive join unconditionally, even though the
*first* option can touch marginally more rows in total. Wall-clock, not rows considered, is the
number that matters here.

**The guard is not free — it costs a fixed, small tax when it has nothing to catch.** The all-rare
scenario is the control, and it is the one case where guard-OFF wins: guard-ON pays the reach
query's ~0.02 ms even though every term is already under the cut and nothing gets filtered, for a
net ~0.03 ms loss against skipping the reach query entirely. That is real and is reported as such,
not rounded away — but it is three orders of magnitude smaller than the saving on a query that
does contain a common segment, and per §15's own finding, most of excalidraw's nine questions'
terms are already in that "nothing to catch" band, which is exactly why the guard is
retrieval-neutral there and this tax is invisible to any retrieval metric.

**Decision: the guard stays.** Honestly accounted — reach query included on the guard-on side,
excluded (correctly, since removing the guard removes that query entirely) on the guard-off side —
the guard is **7.4×–8.7× cheaper** than removing it on every query shape that contains a common
identifier sub-word, and costs a **~0.03 ms** tax on a query that does not. `ExploreEngine.matchSymbols`
pays this once per query token, so a multi-word query mixing common and rare terms nets solidly in
the guard's favour. This is a smaller effect than the first pass's uncorrected 100×–3,400× claim,
and the correct one: **the earlier numbers are wrong and this table supersedes them.** The
mechanism is not a stopword substitute (§2 item 3 already corrected that framing) — it is what
lets a common segment's long posting list get counted once, cheaply, instead of joined and sorted
unconditionally. No code changes as a result of this measurement: `SEGMENT_RARITY_MAX_FRACTION`
and its call site are unchanged from what §5's cost figures and §7's caveats already describe.

**On taking a real Keycloak index instead.** The synthetic database's mechanism is confirmed by
`EXPLAIN QUERY PLAN`, not just by wall-clock noise — the reach query structurally omits a join and
a sort that the candidate query structurally requires, which is a property of the schema and the
query shape, not of this particular synthetic distribution. A real Keycloak index would not change
which query plan SQLite picks for either query. Judged unnecessary: a third Keycloak index was not
taken.

### 16.3 The final re-measurement

§16.1 is the only code change in this round — §16.2 concluded "keep, unchanged." The revert
landed at commit `2e815afd81236f8a93e62931f47b5bcf31e04ab1`. Re-measured with the unmodified rig,
five cold cycles, ordinary live-worktree build:

| label | commit | MRR | R@5 | R@10 | coverage | dirty | ingest | index size |
|---|---|---|---|---|---|---|---|---|
| `d18-final` (×5) | `2e815afd81236f8a93e62931f47b5bcf31e04ab1` | 0.5556 (all 5 cycles) | 0.3981 (all 5) | 0.4259 (all 5) | 21/21 (all 5) | 0 (all 5) | 6.65–7.19 s | 32,673,792–32,813,056 B |

Identical to A2's original 0.5556 / 0.3981 / 0.4259 on every one of the five cycles — the expected
result, since §15 already measured the de-duplication fix as retrieval-neutral on both arms
individually, and this round's only code change is removing it; the rarity guard is unchanged.
**The combination confirms rather than contradicts what the two pieces measured separately: no
divergence to report.** Ingest duration and index size are not re-measured here beyond what the
five cycles above already show in passing — this revert touches only `searchNodes`'s read path,
not ingest, so §5's cost figures (measured at `9aa58dc`, before either the LOWER()/Unicode fix or
this round's revert) remain the right numbers for what shipping A2 costs; nothing in this round
changes them.

### 16.4 The three findings from this close-out, together

Each of these is a result in its own right, not an absence of one:

1. **The de-duplication null.** De-duplicating the FTS read path changed nothing, on either A0c
   or A2, across ten cold cycles (§15) — so the fix was reverted (§16.1) rather than kept as code
   with no measured benefit.
2. **The rarity-guard finding.** Disabling the guard changed nothing on these nine questions
   (§15) — but at Keycloak scale, honestly measured against the cost of the guard's own decision
   query (§16.2 corrects an earlier pass of this measurement that omitted it), it is 7.4×–8.7×
   cheaper than removing it whenever a query term is a common identifier sub-word, and costs a
   small (~0.03 ms) fixed tax otherwise. Both halves are true at once: retrieval-neutral here,
   cost-relevant at scale. The guard stays.
3. **The A0 = A0c identity.** As-found and cold baselines were identical on all three retrieval
   metrics (§4) — which contradicts the brief's own assumption that duplicate-row growth in a
   stale, as-found index would move these questions' scores. It does not, on this corpus and this
   question set.

### 16.5 Verification for this round

`scripts/segvocab/check-shared-corpus-unmodified.sh` passed after every one of the five
`d18-final` cycles. `scripts/segvocab/guard-cost-build-synthetic.py` and
`scripts/segvocab/guard-cost-measure.py` never touch `/tmp/claude/benchmark-corpus` or any
tracked corpus — both read and write only a throwaway database under `/tmp/claude/segvocab-run/`,
outside this rig's own private-root convention only because they measure a schema shape, not a
real index. §11 above (the run's full `check`, prohibition check, and shared-corpus check) was
re-run after this round's own commits and reflects this round's product-code and test changes;
its verbatim output is current as of the commit named there.

## 17. Why a code state and a measured row must travel together

Two findings in this document, from two different close-out rounds, are the same lesson twice.
Naming the lesson once, here, where a reader meets it directly, rather than leaving it as a
sub-paragraph inside an unrelated fix's writeup (§14) or an implicit moral of §16.2's correction.

**The first time: a plausible ranking change that regressed.** §14's `segmentCandidates` fix was
a comparison-and-Unicode correctness fix, not a ranking change — but while in that function, a
second idea was sitting right there and looked like a free improvement: order the candidates by
how many of the query's accepted segments they matched, descending, instead of the arbitrary
`ORDER BY n.id`. A candidate matching three query segments *should* rank ahead of one matching
only one — the intuition is not unreasonable. It was tried and measured anyway, because this run's
own discipline is that no ranking-shape change ships unmeasured. The result: MRR 0.5000, R@5
0.3148, R@10 0.3426 — every one of those below the cold baseline (0.4815 / 0.3426 / 0.3704),
reproducibly across five cold cycles, not noise. The idea that looked obviously better made every
retrieval number worse. `ORDER BY n.id` — the arbitrary, unglamorous tie-break — was kept, not
because it was defended in the abstract, but because it was the one actually measured to work.

**The second time: a cost measurement that was wrong until it was checked against what production
actually runs.** §16.2's first pass at the rarity guard's cost timed only the join query on both
sides, arrived at ratios up to ~3,400×, and would have shipped that number if a peer reviewer had
not asked the obvious question: does guard-on's number include the query that decides which terms
the guard accepts? It did not. Once corrected, the guard still wins — but by 7.4×–8.7×, not
3,400×, and with an honestly reported ~0.03 ms tax on the case where it has nothing to catch. The
direction of the finding survived; the magnitude, uncorrected, would have overstated the guard's
value by two orders of magnitude in a document whose whole premise is that a claim here traces to
a number, not to a plausible-sounding argument.

**Both are the same failure mode, caught the same way.** In neither case did the code that looked
better, or the measurement that looked favourable, get shipped or published on the strength of how
it looked. Both were caught only because they were actually run against the real instrument — a
cold retrieval cycle in one case, an honest wall-clock comparison in the other — rather than
reasoned about from the shape of the change. A codebase (or a document) that ships the
plausible-looking version because measuring it felt like due diligence rather than a real
possibility of being wrong would have shipped a regression once and overstated a real finding by
100× the other time. The generalisable point is not "measure everything" as a slogan; it is that
*this specific class of change* — anything where intuition and measurement could plausibly
diverge, whether that is a ranking order or a cost comparison — is exactly the class where a
reviewer's first question should be "was this actually run," not "does this sound right."
