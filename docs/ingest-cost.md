# Ingest: where the seventy-one minutes went

Written 2026-08-23 from a Java Flight Recording and a phase-instrumented ingest of the same
corpus `docs/retrieval-improvements.md` measures retrieval against.

Every number here is either measured in this run or cited to a specific line of source.
Nothing is inferred from behaviour alone. §7 is the caveats and they bound everything above —
in particular **the host's concurrent load moved between 6 and 140 across the session**, so
absolute seconds are inflated and unstable. Load is recorded beside every measurement, and
Keycloak's result is given as a range rather than a single number because of it.

**The short version:** the cost was one SQLite connection per row. Keycloak's ingest went from
**142 m 55.6 s to 8 m 16.4 s** — 17.3×, or 9.6× against the most heavily loaded after-run — and
the graph it produces is byte-identical, table for table, with retrieval reproducing the
committed `main` benchmark exactly.

---

## 1. The starting point

From `modules/benchmark/results/three-way/retrieval-1787434672667.json`, field `ingestCosts`,
on `main`:

| repo | ContextGraph | CodeGraph | ratio |
|---|---|---|---|
| Keycloak | **71 m 15 s**, 1.55 GB | 39.7 s, 812 MB | **107× slower**, 1.9× larger |
| excalidraw | 1 m 1 s, 27 MB | 2.7 s, 36 MB | 23× slower |

That 71 m 15 s was recorded on a quieter machine than the one this run had. Re-measured here,
the same unchanged code takes **142 m 55.6 s** on Keycloak — which is why this document
measures its own baseline rather than quoting that one (§7).

---

## 2. The answer, in one sentence

**ContextGraph opened, prepared, executed and closed a separate SQLite connection for every
individual row it wrote or read.**

Keycloak's index holds 234,090 nodes, 622,541 edges, 234,523 provenance rows and 508,017
unresolved references. Every one was written inside its own `transaction { }` block in
`SqliteStorageAdapter`, and Exposed's `Database.connect(url, driver)` hands each top-level
transaction a brand-new JDBC connection. So one row cost: open a connection, prepare a
statement, step it, commit — an fsync, since `PRAGMA journal_mode` on the produced database
reports `delete` — and close the connection. About 1.5 million times.

Reads were worse. `ResolutionLadder` decided rung 2 ("does the referring file import this
candidate?") by asking the database what a file imports **once per reference** rather than
once per file: one query for the file's `Imports` edges, then one more per import edge to read
the imported node's label. Keycloak has 508,017 references over 7,183 code files carrying
105,149 import edges, so a file's answer — which cannot change during the pass — was rebuilt
hundreds of times at roughly sixteen single-statement round trips a go.

---

## 3. Where the time went, by phase

Measured with per-phase timers now built into the ingest path (`IngestPhase`,
`IndexStats.timingReport`), printed by every `index` run.

**Keycloak, before:**

| phase | time | share |
|---|---|---|
| module detection | 2.5 s | 0.0% |
| **pass 1** (discover, extract, write) | **72 m 56.8 s** | **51.0%** |
| **pass 2** (reference resolution) | **67 m 48.9 s** | **47.4%** |
| pass 3 (sibling grouping) | 2 m 07.4 s | 1.5% |
| unattributed | 3 ms | 0.0% |
| **total** | **142 m 55.6 s** | |

Inside pass 1, summed across the threads that paid it — these overlap each other and pass 1's
own wall-clock, because extraction is concurrent while one consumer writes:

| cost | summed |
|---|---|
| **database writes** | **72 m 42.4 s** |
| extraction | 23 m 25.5 s |
| file triage (checksum, stat, artifact lookup) | 20 m 00.5 s |

**Database writes summed to 72 m 42.4 s inside a 72 m 56.8 s phase — the single write consumer
was busy 99.7% of pass 1, and everything else was waiting on it.** Extraction, the thing an
indexer is supposed to spend its time on, was 23 minutes of CPU spread across many threads.

excalidraw shows the same shape at 1/25 the scale: pass 1 2 m 34.4 s (48.1%), pass 2
2 m 35.6 s (48.5%), pass 3 10.8 s, total 5 m 21.0 s, with database writes summing to
2 m 33.2 s of pass 1's 2 m 34.4 s.

---

## 4. What the profile said

Java Flight Recorder, `settings=profile` — JDK-bundled, so no new dependency and no network.

### 4.1 Keycloak, before — 235,701 samples over 5,934 s

The largest and most representative recording here. (This ingest was killed by the harness at
5,934 s, still inside pass 2, so its *total* is not a usable number — but the recording dumped
on exit, and what it says about where time goes is unaffected.)

**By top frame — what a thread was literally executing:**

| frame | share |
|---|---|
| `org.sqlite.core.NativeDB.step` | 56.4% |
| `org.sqlite.core.NativeDB.prepare_utf8` | 15.4% |
| `org.sqlite.core.NativeDB._open_utf8` | 8.7% |
| `org.sqlite.core.NativeDB._close` | 6.9% |

**87.4% of the run is inside SQLite's native calls, and 31.0% of it is opening, closing and
preparing** — setup and teardown that does no work at all.

**By the deepest ContextGraph frame — our code that asked for it:**

| call | share | rows on Keycloak |
|---|---|---|
| `SqliteStorageAdapter.insertUnresolvedReference` | 31.2% | 508,017 |
| `SqliteStorageAdapter.upsertNode` | 22.5% | 234,090 |
| `SqliteStorageAdapter.upsertEdge` | 15.3% | 622,541 |
| `SqliteStorageAdapter.upsertProvenance` | 13.7% | 234,523 |
| `SqliteStorageAdapter.getNode` | 12.2% | pass 2's per-reference import lookup |

**94.9% of all samples are inside single-row `SqliteStorageAdapter` calls.**

By phase: pass 1 writes 85.0%, pass 2 resolution 14.5% (under-represented — the run was killed
part-way through it), everything else 0.5%. **`pass1/extract` and `pass1/triage` are 0.0%.**

### 4.2 excalidraw, before — 9,272 samples

Same shape at 1/10 the scale, and it adds the thread breakdown: `step` 41.3%, `prepare_utf8`
21.3%, `_open_utf8` 13.1%, `_close` 5.9%; `getNode` alone 30.1% of project frames; **96.5% of
samples on the `main` thread** — the single write consumer plus passes 2 and 3. Extraction,
running concurrently on the IO dispatcher, was about 2%.

### 4.3 What that settles

The brief asked for likely candidates to be ruled in or out rather than assumed. **Parsing,
extraction and language coverage are ruled out** — together they are single-digit percent, and
the "extract less" failure mode the brief prohibits would not have helped even if it had been
allowed. The cost is persistence, and specifically per-row persistence overhead.

---

## 5. What changed

Three changes, all on the write path. None changes what is extracted; none touches retrieval.

### 5.1 Bulk write verbs on `StorageAdapter`

`writeArtifactBatch`, `upsertNodes`, `upsertEdges`. Each has a **default implementation that
is exactly the per-row loop it replaces, in the same order** — so an adapter that does not
override one behaves identically to before, and the meaning of a batch is pinned down by code
rather than by a comment. `SqliteStorageAdapter` overrides all three to run in one transaction
(one connection, one commit) with one prepared statement re-bound per row instead of one
prepared per row.

The pipeline's single write consumer now hands over one artifact's whole result as a unit. It
is still exactly one writer, and the previous run's rows are still cleared exactly once per
artifact per run — on the first of possibly several extractor results for it, which is why
`clearExisting` is the caller's decision and not the adapter's.

`BulkWriteEquivalenceTest` builds two databases from identical inputs, one through the per-row
verbs and one through the bulk verbs, and compares every row of every table including
`nodes_fts`.

### 5.2 Import tokens computed once per file, not once per reference

`ImportedTokens` memoises rung 2's input per file. Nothing else about resolution changed: same
rungs, same candidate cap, same confidences.

### 5.3 Artifact checksums read once, not once per file

Pass 1's triage asked the database for each discovered file's previous checksum, from the
extraction coroutines, against the file the writer holds a lock on. Those answers provably
cannot change during pass 1 — an artifact row is written only by the processing of that same
artifact — so they are read once, up front, off the contended path.

### 5.4 And what deliberately did not change

- **Write-ahead logging.** It would let readers run alongside the writer, but §3 shows the
  writer saturated, so there is little for reader concurrency to buy — and `journal_mode` is
  persisted *in the database file*, which would change the committed graph baseline that
  `GraphDbGitIntegrationTest` pins down (fresh-clone reads, baseline non-modification). A
  speculative gain against a documented invariant is the wrong trade. Named here with the
  numbers so the next person can weigh it.
- **A second writer**, as CodeGraph's `store-worker.ts` has. SQLite serialises writers at the
  file level, so a second one buys contention, not throughput.
- **Anything in the search or ranking path** — `searchNodes`, its term extraction,
  `ContextBundler`, `QueryEngine`. A concurrent run owns those.

---

## 6. Result

### 6.1 Cost

Both repos indexed from a **deleted** graph database — non-negotiable, because the pipeline
skips any artifact whose checksum already matches, so re-indexing over an existing graph
measures the skip path and means nothing.

| repo | before | after | ratio |
|---|---|---|---|
| **Keycloak** | **142 m 55.6 s**, 1,551,826,944 B | **8 m 16.4 s**, 1,550,520,320 B | **17.3× faster**, size −0.08% |
| **excalidraw** | **5 m 21.0 s**, 27,508,736 B | **28.3 s**, 27,471,872 B | **11.3× faster**, size −0.13% |

The excalidraw pair was taken **41 seconds apart**, at load average 80.4 and 80.6 — the
tightest control this host allowed.

Keycloak was measured three times, and all three are reported because the host's load moved
underneath them:

| run | pipeline time | load (start → end) |
|---|---|---|
| before | 142 m 55.6 s | 87.9 → 6.0 |
| after, run 1 | 14 m 54.4 s | 100.2 (throughout) |
| after, run 2 | **8 m 16.4 s** | 17.6 → 107.0 |

**The honest range is 9.6× – 17.3×.** The low end compares the baseline against the after-run
that was under constant heavy load; the high end compares it against the after-run whose load
profile most resembles the baseline's own. Both clear the bar this run set itself (5×), and
the low end is a floor, not a best case: the baseline enjoyed a *quieter* machine for its
entire second half.

Keycloak by phase, before → after (run 2):

| phase | before | after | ratio |
|---|---|---|---|
| module detection | 2.5 s | 0.5 s | — |
| pass 1 (discover, extract, write) | 72 m 56.8 s | 4 m 03.9 s | **17.9×** |
| pass 2 (reference resolution) | 67 m 48.9 s | 3 m 30.1 s | **19.4×** |
| pass 3 (sibling grouping) | 2 m 07.4 s | 41.9 s | 3.0× |

The dominant cost named in §4 is gone. `pass1/write` was 85.0% of the Keycloak profile, with
94.9% of samples inside single-row adapter calls and the writer busy 99.7% of pass 1. Pass 1
is now 49.1% of a run that is itself 17× shorter, its summed database writes are 4 m 00.7 s
rather than 72 m 42.4 s, and no single call dominates it.

**Against CodeGraph**, carefully: its 39.7 s and our 71 m 15 s come from the same committed run
on the same machine, so the sound comparison applies the measured speed-up to that pair rather
than mixing hosts. 71 m 15 s ÷ 17.3 ≈ **4 m 07 s**, which would put the gap at roughly **6×**
rather than 107×. Stated as the extrapolation it is: no run on that quieter machine was made
with this change in it. The directly measured figure here — 8 m 16.4 s against their recorded
39.7 s — is 12.5×, and it is pessimistic, because this host was busy and theirs was not.

One counter-intuitive number, stated because it looks like a regression and is not: **summed
extraction time went up** (excalidraw: 3 m 06 s → 5 m 46 s). It is summed *across threads*.
With the writer no longer the bottleneck, far more extraction runs concurrently, so each parse
takes longer in wall-clock — while the phase containing them all got 11× shorter. Keycloak
shows the opposite sign for the same reason (23 m 25 s → 8 m 11 s summed), because there the
before-run's extraction threads spent most of their summed time blocked rather than parsing.

### 6.2 The graph did not get worse

Three independent checks, all on the same corpus, before and after.

**Row-level content diff.** Dump every table as sorted rows and compare — excluding only
`artifacts.indexed_at` and `provenance.extracted_at`, which are wall-clock stamps, and
`provenance.id`, an AUTOINCREMENT surrogate whose value depends on insertion order.

| repo | artifacts | nodes | edges | provenance | unresolved_references |
|---|---|---|---|---|---|
| excalidraw | identical | identical | identical | identical | identical |
| Keycloak | identical | identical | identical (364 MB) | identical (83 MB) | identical (193 MB) |

Both repos are byte-identical on every table. excalidraw is **still identical after a further
incremental reindex** (1,065 artifacts skipped), so the checksum-skip path is unaffected too.

**One row, and what it turned out to be.** The first Keycloak comparison differed on exactly
one node out of 234,090: `concept:realm_settings`, labelled `Realm settings` before and
`Realm Settings` after. Rather than wave it through, it was chased down:

- Provenance shows that node is written by **two artifacts** — `docs/tests.md`, which spells
  it `Realm settings`, and `docs/documentation/upgrading/topics/changes/changes-26_6_0.adoc`,
  which spells it `Realm Settings`.
- The concept id is normalised (`realm_settings`); **the label is not**. So both artifacts
  upsert the same row with different labels, and the surviving label is whichever extraction
  result reaches the single write consumer last — an order that concurrent extraction does not
  fix. Twelve Keycloak concept nodes are written by more than one artifact and so are exposed
  to this.
- Demonstrated, not merely argued: **a second run of the identical after-code produced
  `Realm settings` again**, and that run is byte-identical to the before-graph on every table.
  A difference that flips between two runs of the same binary is not attributable to a change
  in that binary.

Pre-existing nondeterminism, unrelated to this work, and worth its own fix by whoever owns
concept extraction — a deterministic tie-break (lowest artifact id wins, say) would remove it.

**Gold-file coverage**, computed by the unmodified harness:

| repo | before | after |
|---|---|---|
| excalidraw | 21 / 21 | **21 / 21** |
| Keycloak | 25 / 26 | **25 / 26** |

**Retrieval.** The benchmark re-run over the same 17 questions with the harness untouched
reproduces the committed `main` run **exactly** — not approximately:

| | MRR | R@5 | R@10 | P@5 | P@10 |
|---|---|---|---|---|---|
| ContextGraph, `main` | 0.149436090225564 | 0.03125 | 0.072916666666667 | 0.025 | 0.025 |
| ContextGraph, after | 0.149436090225564 | 0.03125 | 0.072916666666667 | 0.025 | 0.025 |

`diff` reports no difference in the summary headline for **any** of the three sides, in the
**per-question ranked file lists**, or in gold-file coverage. That is the expected consequence
of §6.2's first check: a byte-identical graph cannot retrieve differently.

Keycloak's ContextGraph side is skipped, before and after, for the same pre-existing
missing-`META-INF/services` reason and no new one.

### 6.3 Index size

Unchanged, and that is the intended outcome — the brief asked for size to be *reported*, and
fixed only where obviously wasteful. Keycloak: 1,551,826,944 B before, 1,550,520,320 B after
(**−0.08%**). Every per-object figure in §8 is within 20 KB of its before value. §8 says what
dominates it and what a size-focused follow-up should attack.

---

## 7. Caveats

- **The machine was under heavy and *moving* concurrent load** — 1-minute load average between
  6 and 140 across the session, from other work on the same host. The clearest illustration: a
  fresh baseline of excalidraw measured 5 m 21 s against the 1 m 1 s recorded in the committed
  benchmark. Same code, same corpus, five times slower because of what else was running. This
  is why §6.1 reports a **range** for Keycloak rather than a single ratio, and why the load
  average is printed beside every measurement. The floor of that range (9.6×) is the
  defensible claim; the ceiling (17.3×) is the better-matched one.
- **One run per configuration for the headline pairs, no variance bars.** The same limitation
  `docs/retrieval-improvements.md` records. Repeated excalidraw baselines during this session
  ranged 228.6 s – 321.0 s and repeated after-runs 22.9 s – 36.7 s; Keycloak after-runs ranged
  8 m 16 s – 14 m 54 s. That spread is the honest measure of how noisy this host was, and it
  is smaller than the effect being measured by roughly an order of magnitude.
- **The baseline is `main` plus this run's phase timers**, not literally `main`: the
  attribution in §3 could not exist otherwise. The timers cost a bounded number of
  `System.nanoTime` reads per file — microseconds against minutes — and never one per row.
- **Keycloak's ContextGraph retrieval side is not scored, before or after.** The index
  integrity gate skips it over one gold-cited file that is a `META-INF/services/` resource
  rather than source. That was already true on `main`; this run did not touch the gate, and
  chasing the missing file would have meant changing extraction and making before and after
  non-comparable on exactly the repo the headline is about.

---

## 8. Still on the table

Measured but not acted on, with the numbers that would justify acting.

1. **`INSERT OR REPLACE INTO nodes_fts` never replaces.** `nodes_fts` is an FTS5 table with
   `id UNINDEXED`, so there is no unique index for the conflict clause to target: every
   re-upsert of a node **appends a duplicate search row**. Measured on excalidraw: 10,383 rows
   in `nodes` against 10,602 in `nodes_fts` after one cold index, and 10,608 after one further
   reindex — exactly the six module nodes re-upserted. Pre-existing, on both the single-row and
   the bulk path. It is the search layer, so it belongs to the concurrent retrieval run rather
   than this one, but it means the search index grows without bound across reindexes.

   **Fixed since, and re-measured: see `docs/nodes-fts-one-row-per-node.md`.** Search rows are
   now keyed on `nodes.rowid`, which is the one key FTS5 does enforce; excalidraw reads 10,383
   against 10,383 and no longer grows on reindex, and databases built before the fix repair
   themselves on the next open. Two findings there bear on this document in particular: fixing
   it changed **no** retrieval metric (so the duplicates were carrying no information the
   surviving rows were not), and it recovered essentially **no space** — 0.013% on Keycloak,
   within noise on excalidraw. If the size question in this section is what interests you, the
   number that matters is the one that document measures instead: `nodes_fts`'s *content* shadow
   table, the copy of `id`/`label`/`properties` that `nodes` already holds, is 4.42% of the
   Keycloak index and 6.37% of excalidraw's — which is what an external-content or contentless
   FTS5 table would actually reclaim, and it is still on the table.
2. **Edge storage is 56% of the Keycloak index.** `edges` 407 MB, its shadow primary-key index
   `sqlite_autoindex_edges_1` 238 MB, `idx_edges_source` 111 MB, `idx_edges_target` 108 MB. The
   mean `edges.id` is **279 characters**, because an id is the literal concatenation of a type
   prefix with the source and target declaration-site ids — which are *also* stored in
   `source_id` and `target_id`, and *again* in two indexes over them. A `WITHOUT ROWID` edges
   table would drop the 238 MB shadow index without changing a single row's content.
3. **`unresolved_references` is 18% of the index** (210 MB plus 74 MB of indexes) and is pass-2
   scratch, kept permanently so a reference in an unchanged file can still resolve on a later
   incremental run. A real design tension rather than waste, but worth naming.
4. **Passes 2 and 3 each call `getAllNodes()`**, materialising every node with its properties
   JSON — twice per run, 234,090 nodes each on Keycloak. Small in the excalidraw profile
   (1.3%); the obvious next candidate if pass 2 is worth attacking again.
