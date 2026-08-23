#!/bin/sh
# Bonus, not one of the four required deliverables but direct evidence for the acceptance
# criterion "the shared corpus is provably unmodified after the rig runs": re-stats the same
# two databases record-shared-corpus-baseline.sh recorded, and PASS/FAILs on exact
# (sizeBytes, mtimeEpoch) equality. Slice 08 re-runs this at the end of the whole run.
#
# Usage: check-shared-corpus-unmodified.sh
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

[ -f "$SEGVOCAB_SHARED_CORPUS_BASELINE" ] || segvocab_die "no baseline recorded yet -- run record-shared-corpus-baseline.sh first"

python3 - "$SEGVOCAB_SHARED_CORPUS_BASELINE" <<'PYEOF'
import json, os, sys

baseline_path = sys.argv[1]
baseline = json.load(open(baseline_path))
ok = True
for entry in baseline["databases"]:
    repo = entry["repoId"]
    path = entry["path"]
    if not entry["present"]:
        print(f"  {repo}: baseline recorded no database at {path} (nothing to compare)")
        continue
    if not os.path.isfile(path):
        print(f"FAIL: {repo}: {path} existed at baseline time and is now MISSING")
        ok = False
        continue
    st = os.stat(path)
    size_ok = st.st_size == entry["sizeBytes"]
    mtime_ok = int(st.st_mtime) == entry["mtimeEpoch"]
    if size_ok and mtime_ok:
        print(f"  ok   {repo}: {path} unchanged (size={st.st_size}, mtime={int(st.st_mtime)})")
    else:
        print(
            f"FAIL: {repo}: {path} changed -- "
            f"size {entry['sizeBytes']} -> {st.st_size} (ok={size_ok}), "
            f"mtime {entry['mtimeEpoch']} -> {int(st.st_mtime)} (ok={mtime_ok})"
        )
        ok = False

print()
print("VERDICT: PASS -- shared corpus unmodified" if ok else "VERDICT: FAIL -- shared corpus was touched")
sys.exit(0 if ok else 1)
PYEOF
