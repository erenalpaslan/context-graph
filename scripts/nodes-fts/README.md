# The `nodes_fts` one-row-per-node measurement instruments

Three scripts, and nothing else. They exist because the measurement this run had to publish
needed three things `scripts/segvocab/` deliberately does not provide — not because that rig
was inadequate. **`scripts/segvocab/` is reused unmodified**; everything here either calls it
or complements it, and `check-prohibited.sh` there is still the instrument that proves the
benchmark harness was never touched.

| Script | What it answers |
|---|---|
| `probe-index.sh <graph.db>` | What a built index says about its own search table: `nodes` vs `nodes_fts` row counts, rows with no live node behind them, nodes with no search row, and the on-disk byte share of FTS5's shadow tables. |
| `reindex-in-place.sh <snapshot-dir> [repo]` | A **warm** second index, nothing deleted first — the "index twice and the search index must not grow" measurement. |
| `keycloak-arm.sh <snapshot-dir> <out.json>` | One arm's whole Keycloak measurement as a single detachable unit: prepare corpus, cold index, probe. |

## Why each one is separate rather than a patch to the segvocab rig

**`probe-index.sh` is read-only by construction.** Every connection is a `file:...?mode=ro`
URI, so it can be pointed at the shared corpus at `/tmp/claude/benchmark-corpus` — which no
script in this run may ever open for writing — without any care at the call site. That is why
it reports FTS5's `integrity-check` as `skipped-read-only` rather than running it: the check is
issued as an `INSERT` command, which a read-only connection refuses, and opening the database
for writing to run it would trade a real guarantee for a diagnostic.

It reports two counts that a single total cannot replace. A search row with no live node behind
it and a node with no search row are different faults, and one of each sums to a total that
looks correct. Worse, they are not always visible at all: in a database written before this
fix, FTS5 auto-assigned rowids `1..n` in insertion order, so the earliest search rows land on
the same rowids the earliest nodes happen to occupy and pass an orphan check while still being
the wrong rows. Only the totals plus both directional counts together describe the state.

**`reindex-in-place.sh` exists because `cold-index.sh` correctly refuses to do this.** That
script always deletes `.contextgraph/` before indexing and has no flag to skip it, so a warm
index can never reach a retrieval row through that rig. That guarantee is worth more than the
convenience of a flag, so this run left it alone and added a separate warm pass whose output is
row counts and **never** retrieval scoring.

**`keycloak-arm.sh` is one unit so it can be detached.** Keycloak is not part of the segvocab
rig's per-arm default, and rightly so — ContextGraph's retrieval denominator is excalidraw's
nine questions, because `IndexIntegrityGate` gates Keycloak's ContextGraph side out. But its
ingest duration, index size and gold-file coverage all require a real Keycloak index, and
coverage in particular is an index query rather than a filesystem check, so it cannot be quoted
from an earlier run's result JSON. A prior run had a 99-minute Keycloak ingest reaped by the
harness as a background task; run C has since cut that to roughly eight minutes, but a
single detachable script is still the right shape.

## Running an arm

Everything is driven by the segvocab rig's own environment variables. This run used its own
private root so it neither read run B's leftovers as its own results nor destroyed them:

```sh
export SEGVOCAB_PRIVATE_ROOT=/tmp/claude/ftsdup-run
export SEGVOCAB_GRADLE_USER_HOME=/tmp/claude/ftsdup-run/gradle-home   # see "Gradle daemons" below

scripts/segvocab/build-arm.sh <arm> [source-root]         # once per arm
scripts/segvocab/measure-arm.sh <arm> --snapshot-dir …    # once per cold cycle
scripts/nodes-fts/probe-index.sh  "$SEGVOCAB_PRIVATE_ROOT/corpus/excalidraw/with/.contextgraph/graph.local.db"
scripts/nodes-fts/reindex-in-place.sh <snapshot-dir> excalidraw
scripts/nodes-fts/probe-index.sh  …                        # the same probe again: the counts must not have moved
```

## Gradle daemons, and a denial that was not a sandbox denial

Building in a fresh worktree failed with `java.io.IOException: Operation not permitted` while
Gradle created its own project cache — even though `mkdir` of that exact path succeeded from a
shell in the same sandbox. The cause was not the sandbox: a **Gradle daemon left running by an
earlier run**, reachable through the shared `GRADLE_USER_HOME`, was sandboxed to a *different*
worktree and could not write into this one. `--no-daemon` made the identical build succeed
immediately.

Point `SEGVOCAB_GRADLE_USER_HOME` at a private directory (as above) and the rig starts a daemon
of its own. Do not reach for `gradle --stop`: several runs can share this repository and that
home, and killing their daemons to fix your own is a wider blast radius than the problem.
