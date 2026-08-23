# `nodes_fts`: one row per node, and what fixing it did to run B's gain

Written 2026-08-24. Every number here was measured in this run, cold, on both arms, through the
unmodified `scripts/segvocab/` rig. §10 is the caveats and they bound everything above.

## 1. The result, in two lines

**The defect was real and is fixed: excalidraw's search index went from 10,602 rows against
10,383 nodes — growing to 10,608 on a second index — to exactly 10,383, unchanged across a
second and a third.**

**Run B's measured gain does not depend on it. Retrieval did not move at all:** MRR 0.5556,
R@5 0.3981, R@10 0.4259, identical on every one of five cold cycles on each arm, to four
decimal places. That is a null result, and it is reported here as the finding it is — the
brief for this run asked specifically whether B's advantage would shrink against a correct
index, and the answer, on this corpus and this question set, is that it does not shrink at all.

## 2. The defect

`SqliteStorageAdapter` wrote the search index with
`INSERT OR REPLACE INTO nodes_fts(id, label, properties)`, on both the single-row and the bulk
path. The table is:

```sql
CREATE VIRTUAL TABLE nodes_fts USING fts5(id UNINDEXED, label, properties)
```

**`UNINDEXED` means "not searchable", not "unique".** FTS5 offers no way to declare a unique
index on a column, so there was nothing anywhere for `OR REPLACE` to conflict against and it
never replaced anything. Every re-upsert of a node appended another search row. Nothing ever
deleted one either — before this change, no code path deleted from `nodes_fts` at all, so a
node removed by a reindex left its rows behind as orphans.

Confirmed directly against `sqlite3` 3.43.2 before any code was written: two upserts of the
same node leave two rows. `V6__name_segment_vocab.sql`'s header had already described the same
defect from the other side, explaining that *that* table could use `INSERT OR IGNORE` precisely
because it has a real primary key.

Found by run C (`docs/ingest-cost.md`), re-confirmed by run B
(`docs/identifier-segment-vocabulary.md` §6), fixed by neither, because the search layer was
another run's territory both times.

## 3. The fix

The one key FTS5 *does* enforce is its rowid. Both write paths now supply it, taken from the
node's own row in `nodes`:

```sql
INSERT OR REPLACE INTO nodes_fts(rowid, id, label, properties)
SELECT n.rowid, v.column1, v.column2, v.column3
FROM (VALUES (…), (…), …) v JOIN nodes n ON n.id = v.column1
```

`nodes.id` is a `TEXT PRIMARY KEY`, so its rowid is a 1:1 key for the node and needs no second
source of truth. Reading it through a join *in the same statement* rather than in a separate
lookup pass is what keeps this at one prepared statement per chunk — the property that made run
C's grouped FTS writes worth doing. Three things follow structurally, none of which needs a
runtime check:

- a node written twice occupies one row, carrying the later value, with the superseded value's
  terms **removed** from the index rather than shadowed;
- a search row can only exist for a node that exists, because the join is what supplies its key;
- `COUNT(*)` of the two tables agree.

`deleteNodesForArtifact` now deletes a node's search row *before* the node row it is keyed on
disappears. That is not an extra: `writeArtifactBatch(clearExisting = true)` deletes an
artifact's nodes and writes them again, and a re-inserted node gets a **new** rowid — so
without the delete, the old row would strand at the old rowid and the defect would return in a
new form. Deleting by rowid rather than by `id` matters too: `id` is `UNINDEXED`, so
`DELETE … WHERE id IN (…)` plans as `SCAN nodes_fts VIRTUAL TABLE INDEX 0:` — a full scan of
the search index per statement, quadratic over a chunked reindex — while the rowid form plans
as `INDEX 0:=`.

## 4. What was rejected, and why

Correctness first, then cost. All four candidates below are correct; they are separated by what
they cost and what else they drag in.

| Candidate | Verdict |
|---|---|
| **Explicit rowid** (shipped) | One statement, no schema change, no new table, no read-back pass. Verified against sqlite3 before it was written: replaces cleanly, purges the superseded row's terms, survives a multi-row `VALUES` list, passes FTS5's own `integrity-check`. |
| **Delete-then-insert keyed on `id`** | **Rejected on measured cost.** `EXPLAIN QUERY PLAN` gives `SCAN nodes_fts VIRTUAL TABLE INDEX 0:` — a full scan of the search index for every chunk, because `id` is `UNINDEXED`. Over a chunked ingest of a repository the size of Keycloak that is quadratic. |
| **External content (`content='nodes'`) with sync triggers** — what CodeGraph does (`src/db/schema.sql:117-136`) | **Rejected on what it drags in.** FTS5 would read the label straight out of `nodes`, which destroys `ftsLabelFor`'s index-time identifier-segment augmentation — run A and B's shipped behaviour. Preserving it means denormalising a Kotlin-computed `search_label` column into the domain table purely so a SQL trigger can copy it: a bigger diff and a worse boundary than the one-line write it replaces. |
| **Contentless (`content=''`, `contentless_delete=1`)** | **Rejected as out of scope, with the size it would have bought now measured** — see §9. It buys the same index-size win as external content without the extra column, but costs a read-path change for a benefit nobody had quantified. This run quantified it instead of paying for it blind. |

## 5. The measurement

Both arms cold-indexed, both built through the unmodified rig, `before` from the pinned
pre-fix commit in an isolated detached worktree, `after` from this branch's own commit. Run B's
published numbers were re-derived, not quoted — the brief was explicit that a defect which makes
a warm index differ from a fresh one is exactly what makes a second-hand number untrustworthy.

| | before (`69a67aa`) | after (`1ee758e`) |
|---|---|---|
| MRR (n=9, 5 cold cycles) | **0.5556** on all 5 | **0.5556** on all 5 |
| R@5 | **0.3981** on all 5 | **0.3981** on all 5 |
| R@10 | **0.4259** on all 5 | **0.4259** on all 5 |
| `nodes` / `nodes_fts`, excalidraw, 1st index | 10,383 / **10,602** (219 orphaned) | 10,383 / **10,383** (0 orphaned) |
| same, after a 2nd index | 10,383 / **10,608** (225 orphaned) | 10,383 / **10,383** (0 orphaned) |
| same, after a 3rd index | not measured | 10,383 / **10,383** (0 orphaned) |
| `nodes` / `nodes_fts`, Keycloak | 234,090 / **235,152** (1,062 orphaned) | 234,090 / **234,090** (0 orphaned) |
| Gold-file coverage, excalidraw | 21/21 | 21/21 |
| Gold-file coverage, Keycloak | 25/26 | 25/26 |
| Excalidraw cold ingest (interleaved, 4 pairs) | 9.1–12.5 s, mean 11.03 s | 9.9–12.6 s, mean 11.28 s |
| Excalidraw index size (same interleaved runs) | 32,665,600–32,747,520 B | 32,714,752–32,772,096 B |
| Keycloak cold ingest | 429.5 s | 289.7 s — **not comparable, see §10** |
| Keycloak index size | 1,579,855,872 B | 1,579,651,072 B (−204,800 B, −0.013%) |

R@5 showed none of the wobble run B documented for its own baseline (0.3426 in 6 of 8 cycles,
0.3148 in 2). Every cycle of both arms here read identically on every metric.

**Keycloak is not scored on retrieval and never has been.** `IndexIntegrityGate` gates its
ContextGraph side out, which this run re-confirmed rather than assumed: of Keycloak's 8 scored
questions, ContextGraph measured **0** (every `contextGraph` field is `null`) while ripgrep
measured 8. ContextGraph's retrieval denominator is excalidraw's nine questions, on both arms.

## 6. Only the search index changed

The two arms' cold excalidraw indexes were dumped table by table, ordered, and compared:

| table | before | after | |
|---|---|---|---|
| `nodes` | 10,383 | 10,383 | identical |
| `edges` | 16,869 | 16,869 | identical |
| `provenance` | 9,418 | 9,418 | identical |
| `artifacts` | 1,065 | 1,065 | identical |
| `name_segment_vocab` | 17,772 | 17,772 | identical |
| `nodes_fts` | 10,602 rows | 10,383 rows | **differs — this is the fix** |

And the excess was pure duplication, not different content: the before arm's 10,602 rows carry
only **10,383 distinct** `(id, label, properties)` triples. The fix removed exactly the
duplicates and nothing else, which is why retrieval could not move — the ranked result set never
contained information the duplicates were adding, only repeats of information already there.

## 7. Databases that already exist

**They repair themselves the first time they are opened. No re-index is required.**

Re-indexing would not have worked as a remedy: extraction skips any artifact whose checksum
still matches, so re-running `index` over an unchanged repository rewrites almost nothing — the
measured growth on a second cold excalidraw index is 6 rows out of 10,608. And after this
change a legacy row sits at whatever rowid FTS5 auto-assigned it, unrelated to its node's, so
the fixed write path would insert *beside* it rather than over it. Without a repair the fix
would be inert on every database that already exists.

The repair is a Flyway migration (`V7__Rebuild_nodes_fts_row_keying`) that empties `nodes_fts`
and rebuilds it from `nodes`, keyed on rowid, through the **same** `FtsIndexText.labelFor` the
write path uses — so a repaired index and a freshly built one are searchable identically, which
is asserted rather than assumed (a repaired database's `nodes_fts` dump is compared byte-for-byte
against a fresh one's). It is registered by instance rather than found by classpath scanning, so
it cannot silently fail to be discovered in a packaged distribution. Flyway runs it in its own
transaction and records it in the schema history: exactly once per database, and a process
killed mid-repair leaves the database untouched, to be repaired on the next open.

**The committed baseline is affected, and this is worth saying plainly.** `.contextgraph/graph.db`
in this repository carries the defect today — 3,597 nodes against 3,636 search rows, 39 of them
orphaned. It will be repaired the first time any process opens it, and because storage adapters
migrate whatever path they are handed (`GraphDb`'s own header says so), that includes a
*read-only* caller on a fresh clone with no overlay yet. The practical consequence is that such
a read will leave `graph.db` showing as modified in `git status`. That is a pre-existing property
of the baseline design, not something this change introduces — every migration since V1 has had
it — but this is the first one that rewrites a whole table, so it is the first one likely to be
noticed. Re-committing the baseline from CI (`scripts/ci-reindex.sh`) settles it.

## 8. Cost

**Ingest: no detectable change.** Measured by interleaving the two arms' cold indexes pair by
pair, so slow drift in host load cancels rather than landing on whichever arm ran later:

| pair | before | after | delta |
|---|---|---|---|
| 1 | 9,103 ms | 10,326 ms | +1,223 |
| 2 | 12,451 ms | 9,864 ms | −2,587 |
| 3 | 10,115 ms | 12,648 ms | +2,533 |
| 4 | 12,466 ms | 12,272 ms | −194 |
| **mean** | **11,034 ms** | **11,278 ms** | **+244 ms (+2.2%)** |

The per-pair spread (−2,587 to +2,533 ms) is an order of magnitude larger than the mean
difference. **This measurement cannot resolve a 2% change and does not claim to** — what it
supports is the weaker, honest statement that the fix did not make ingest noticeably slower,
which is what one would expect from a change that replaces one statement per chunk with one
statement per chunk.

**Index size: unchanged within noise.** The two arms' excalidraw ranges overlap outright
(before 32,665,600–32,747,520 B, after 32,714,752–32,772,096 B). Removing 219 duplicate rows
saves on the order of tens of kilobytes, well under the ~100 KB page-allocation swings between
runs of identical code. Keycloak, with 1,062 rows removed, came out 204,800 B smaller — 0.013%.
**Fixing this defect is a correctness fix, not a space saving**, and anyone hoping the row
counts explained the 1.9× size gap against CodeGraph should read §9.

## 9. The index-size number this run did not spend (hand-off)

Run C left index size open, and two of the designs rejected in §4 would have shrunk the
database by dropping FTS5's *content* shadow table — a second copy of `id`, `label` and
`properties` that `nodes` already holds. This run deliberately did not take that win, because
bundling a second change into an arm whose whole purpose is isolating one defect makes both
unmeasurable. Instead it measured what is on the table, from real indexes:

| index | database | `nodes_fts*` shadow tables | of which `nodes_fts_content` |
|---|---|---|---|
| excalidraw (after) | 32,727,040 B | 2,822,144 B (8.62%) | **2,084,864 B (6.37%)** |
| Keycloak (after) | 1,579,651,072 B | 88,678,400 B (5.61%) | **69,799,936 B (4.42%)** |

So an external-content or contentless design is worth roughly **4.4–6.4% of the database** —
real, but not the order of magnitude the 1.9× size gap would need. Whoever picks that up now has
the number rather than the hypothesis. Note that the contentless route also costs a read-path
change (`searchNodes` would join `rowid` back to `nodes` instead of selecting `id` directly),
and the external-content route costs a denormalised column, for the reasons in §4.

## 10. What bounds these numbers

- **Nine questions, one repository.** Every retrieval figure here is excalidraw's. Keycloak
  contributes coverage, ingest cost and row counts, never a retrieval score, because its
  ContextGraph side is gated out — re-confirmed in this run, not assumed. Widening that
  denominator needs a human decision nobody has made.
- **A null result on retrieval is not proof of no effect anywhere.** It says that on this
  corpus and these nine questions, removing the duplicates changed no ranked answer. §6 explains
  why that is the expected shape rather than a surprise: the duplicated rows carried no
  information the surviving rows did not.
- **Keycloak was indexed once per arm, not twice.** The stability-across-reindex property is
  established on excalidraw (three consecutive indexes) and in the tests; doubling Keycloak
  would have cost hours and several GB on a volume at 96% capacity for a property already
  established. The bound is stated rather than quietly taken.
- **The two Keycloak ingest times are not a comparison.** 429.5 s and 289.7 s are single,
  unrepeated, non-interleaved runs minutes apart on a shared host whose load moved the
  *excalidraw* number between 7.1 s and 23.8 s for identical code within the same session. No
  claim is made from them; they are published because the brief asked for ingest duration on
  both repositories, and suppressing the pair would be worse than printing it with this warning.
- **Index size and ingest duration on this host are load-bound**, exactly as
  `docs/ingest-cost.md` §7 already documents.

## 11. Verification — recorded, not asserted

- **The tests fail against the pre-fix code, and that was run rather than assumed.** Both new
  test classes were copied into the pinned `69a67aa` worktree and executed there. All six
  `NodesFtsOneRowPerNodeTest` cases failed: five upserts of one node left **5** rows, 640 nodes
  written three times left **1,282**, four delete-and-rewrite cycles left **16** rows for 4
  nodes, and deleting an artifact's nodes left **3** search rows behind.
- **The unchanged-project double-index case passed against the broken code**, which is exactly
  why the modified-file case exists. Re-indexing an unchanged project is nearly a no-op, so a
  test that only did that would have been green on `main` and proved nothing. The modified-file
  case failed on the pre-fix code at 53 nodes against 61 search rows.
- **Prohibited files:** `scripts/segvocab/check-prohibited.sh` printed `VERDICT: PASS` — the six
  named harness files and all four question YAMLs byte-identical to `main` both by `git diff`
  and by blob hash against the tracked baseline, and nothing under `modules/benchmark/src`
  changed or added.
- **No ranking change.** `searchNodes`, `ContextBundler`, `QueryRelevance`, `segmentCandidates`
  and `SEGMENT_RARITY_MAX_FRACTION` are untouched. Retrieval did not move, so nothing was
  compensated for; had it moved, §1 would say so instead.
- **The shared corpus was never opened for writing.** Every arm ran on this run's own private
  copy, and the probe in `scripts/nodes-fts/` opens every database through a `file:…?mode=ro`
  URI by construction.
- **Test suite:** green apart from `FreshnessTest`'s FileWatcher case, which fails in this
  sandbox because FSEvents is denied — confirmed first-hand by running that same test against
  the pinned pre-fix worktree, where it fails identically.
- **Both Keycloak ingests logged the same 4 extractor warnings**, so the fix changed nothing
  about what extraction produced.
