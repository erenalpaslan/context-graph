# The identifier-segment-vocabulary measurement rig

Built for `agent-team/tasks/02-measurement-rig.md`. Measures one arm -- one code state of this
repository -- against excalidraw's 9-question retrieval set (D1/D2: the only denominator
ContextGraph is scored on) and prints MRR, R@5, R@10, gold-file coverage and ingest cost.
Lives outside `modules/benchmark/src` entirely (hard prohibition 1); every script here just
drives that module's existing, unmodified entry points.

## Quick start

```sh
scripts/segvocab/measure-arm.sh <arm-label>
```

That is the one command: it prepares the private corpus if needed, builds the CLI and
benchmark distributions from whatever is currently checked out in this worktree, cold-indexes
excalidraw, runs the retrieval axis, and prints the row. Run it again with the same code
checked out and you get the same retrieval row (see "Determinism", below).

## How an arm gets measured, end to end

1. **`prepare-corpus.sh`** (deliverable 1) -- copies excalidraw's pristine working tree out of
   the shared corpus at `/tmp/claude/benchmark-corpus` into
   `/tmp/claude/segvocab-run/corpus/excalidraw/{with,without}`, a private copy this run owns.
   Idempotent; only copies if the private copies don't already exist. Never opens the shared
   corpus for writing.
2. **`build-arm.sh <label> [source-root]`** -- runs
   `:modules:cli:installDist :modules:benchmark:installDist` and snapshots both distributions
   into `/tmp/claude/segvocab-run/snapshots/<label>/`. Everything downstream reads only the
   snapshot, never `modules/*/build/` again, so a concurrent edit to the live source tree
   (another slice's `compileKotlin`) cannot change a measurement already in flight.
3. **`cold-index.sh <snapshot-dir>`** (deliverable 2) -- deletes the private WITH copy's
   `.contextgraph/`, runs the snapshotted CLI's `index` command from inside that directory
   (cwd-driven `.contextgraph` home, D6), and verifies the rebuilt database's mtime is not
   older than the deletion. Prints `{"repoId","durationMillis","indexSizeBytes",...}`. There is
   no flag to skip the delete -- this is the only script that runs `index`, and it always
   deletes first, so a warm-index run cannot reach a row through this rig.
4. **`run-retrieval.sh <snapshot-dir>`** -- runs `RetrievalCliKt` directly
   (`java -cp <snapshot>/benchmark/lib/*`) against the private corpus and the tracked,
   unmodified `modules/benchmark/questions/` directory. Read-only; never indexes anything.
   Prints the path to the written `retrieval-<epoch>.json`.
5. **`extract-row.py <result.json> --label <arm> [--ingest-json <json>] [--snapshot-dir <dir>]`**
   (deliverable 3) -- turns that JSON into one row: MRR / R@5 / R@10 / precision, gold-file
   coverage, `measuredCount` for every side, and this row's `provenance` (the snapshot's
   `gitHead` + `gitDirtyFileCount`, read from `BUILD_INFO.json`). Printed to stdout and
   appended as one line to `/tmp/claude/segvocab-run/results/rows.jsonl`.

   - A side whose `measuredCount` is 0 is printed as `SKIPPED` with the matching
     `skippedRepos` reason -- never as a numeric zero (see the script's own docstring for
     exactly why that distinction is real, not cosmetic).
   - **`--snapshot-dir` is what ties a published row back to the code state that produced
     it** -- D14 makes an arm *be* a git code state, so a row's `armLabel` alone (a
     human-typed string) is not enough provenance on its own. When `--label` is also given and
     disagrees with the snapshot's own recorded `BUILD_INFO.json` label, extraction is
     **refused outright** (non-zero exit, no row written) rather than silently trusting
     whichever value was typed last -- this is what catches a snapshot reused under the wrong
     label, or a `--snapshot-dir` typo pointing at another arm's build. `measure-arm.sh`
     always passes `--snapshot-dir`, so this check is live on every row that rig produces; it
     only needs to be supplied by hand when re-deriving a row from an already-written result
     JSON (see "Determinism" below).
   - A row extracted with no `--snapshot-dir` at all still works (for reprocessing old result
     JSON where the snapshot is gone) but its `provenance` is recorded as explicit `null`s, and
     the printed row says `source=UNKNOWN` -- never silently omitted, never guessed at.

`measure-arm.sh` just runs 1-5 in order. Pass `--snapshot-dir <dir>` to skip step 2 and reuse
an already-built snapshot (see "Determinism").

## Determinism

Acceptance criterion: running the same code state twice produces the same row. Build once
(step 2), then call `measure-arm.sh <label> --snapshot-dir <snapshot>` as many times as wanted
-- each call still cold-indexes and re-scores for real, it just skips rebuilding. Two such
calls against `snapshots/rig-verify-1` produced:

```
arm=rig-verify-1  repo=excalidraw  n=9  runId=retrieval-1787500741119  source=22713e311765 (clean)
  contextGraph measuredCount=9   MRR=0.4815  R@5=0.3426  R@10=0.3704
arm=rig-verify-1  repo=excalidraw  n=9  runId=retrieval-1787500767488  source=22713e311765 (clean)
  contextGraph measuredCount=9   MRR=0.4815  R@5=0.3426  R@10=0.3704
```

identical on every retrieval metric and on `source` (ingest duration and database byte size
vary slightly run to run, same as `docs/ingest-cost.md` records for this host -- that is
wall-clock and page allocation noise, not a different measurement). `source=... (clean)` means
`gitDirtyFileCount` was 0 for the snapshot both rows trace back to; an arm measured from an
uncommitted working state (several of this run's vocabulary arms will be, per D14) instead
prints `source=<head> ** DIRTY: N uncommitted file(s) **`, deliberately loud rather than only
stored in the JSON, since that is not an error but a fact a reader comparing arms needs to see.

## When the live worktree won't compile

This worktree can be shared with another slice mid-edit (see the task's CONCURRENCY WARNING).
If `build-arm.sh` fails with a compile error that traces to files you are not touching, that is
someone else's in-flight change, not this rig. Two ways through:

- **Wait and retry.** The other slice is presumably red-green cycling towards its own green
  build; a short retry often clears it.
- **Build from a pinned, known-good commit instead.** Create a throwaway worktree at a
  specific commit (this does not touch the live worktree's tracked files at all -- it is pure
  metadata plus a fresh checkout elsewhere):

  ```sh
  git worktree add --detach /tmp/claude/segvocab-run/build-worktree <commit>
  cp -R modules/tree-sitter/build /tmp/claude/segvocab-run/build-worktree/modules/tree-sitter/build   # avoid a network fetch
  scripts/segvocab/build-arm.sh <label> /tmp/claude/segvocab-run/build-worktree
  git worktree remove --force /tmp/claude/segvocab-run/build-worktree   # clean up when done
  ```

  This is exactly how this rig's own determinism proof above was built, since slice 04 was
  mid-edit in `modules/storage-sqlite` at the time. Never `git commit`, `git stash`, or
  `git checkout` inside the live worktree to work around this -- those are prohibited here for
  good reason (they would disturb the other slice's in-progress files).

## The prohibition checker

```sh
scripts/segvocab/check-prohibited.sh [base-revision]   # default: main
```

Deliverable 4. Prints an explicit `VERDICT: PASS` or `VERDICT: FAIL` (and exits 0/1 to match)
after three checks: the six named harness files plus the question YAMLs are byte-identical to
`main` (`git diff --name-only`); each of those files' current working-tree blob hash
(`git hash-object`) matches the pre-edit baseline recorded in this run's `capabilities.json`
under `prohibitedFilesBaseline.blobs`; and nothing under `modules/benchmark/src` differs from
`main` at all (`git diff --stat`). Runnable at any point, including right now, before any
product code changes exist.

## The shared corpus

```sh
scripts/segvocab/record-shared-corpus-baseline.sh     # run once, early
scripts/segvocab/check-shared-corpus-unmodified.sh     # re-check any time after
```

Deliverable 5. Records byte size + mtime of both `.contextgraph/graph.local.db` files under the
*shared* corpus at `/tmp/claude/benchmark-corpus` (excalidraw's and keycloak's) into
`/tmp/claude/segvocab-run/shared-corpus-baseline.json`, and re-checks them against that
snapshot. This rig never opens either database for writing -- it operates entirely on the
private copy `prepare-corpus.sh` makes -- so both checks should read PASS for the whole run.
Slice 08 re-runs the check script at the end.

## Paths

| What | Where | Tracked? |
|---|---|---|
| These scripts | `scripts/segvocab/` | yes |
| Private frozen corpus | `/tmp/claude/segvocab-run/corpus/excalidraw/{with,without}` | no (scratch) |
| Build snapshots | `/tmp/claude/segvocab-run/snapshots/<label>/{cli,benchmark}` | no (scratch) |
| Retrieval result JSON + rows log | `/tmp/claude/segvocab-run/results/` | no (scratch) |
| Shared-corpus baseline record | `/tmp/claude/segvocab-run/shared-corpus-baseline.json` | no (scratch, durable for this run) |
| Gradle user home | `/tmp/claude/segvocab-run/gradle-home` | no (scratch, shared with slice 01's build) |

Nothing this rig writes lands under `modules/benchmark/src`, and nothing it writes to
`/tmp/claude/benchmark-corpus` at all -- that directory is read-only from this rig's point of
view for its whole life.
