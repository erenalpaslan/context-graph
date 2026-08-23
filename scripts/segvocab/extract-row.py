#!/usr/local/bin/python3
"""Deliverable 3: the row extractor (agent-team/tasks/02-measurement-rig.md).

Turns one retrieval result JSON (io.contextgraph.benchmark.retrieval.RetrievalRun, written by
RetrievalCli / RetrievalCommand) into one arm's row: MRR, R@5, R@10, gold-file coverage per
repo, and the ContextGraph side's measuredCount, printed alongside every metric so a changed
denominator can never hide (spec AC-3/AC-4, task 02 deliverable 3).

Lives outside modules/benchmark/src on purpose (hard prohibition 1): it reads a JSON file that
module already writes and does per-arm arithmetic in a script, exactly the split run A's own
row script (modules/benchmark/results/ablation/ablation_row.py) drew.

**Why a side's measuredCount of 0 is never printed as MRR 0.000**: RetrievalStats.sideAggregate
returns measuredCount=0 (with every mean forced to 0.0) exactly when no question in the group
produced a SideResult for that side at all -- which only happens when the whole side was
skipped (IndexIntegrityGate failure, missing CodeGraph index, ...), never when a side was
measured and genuinely scored zero (a real SideResult always contributes 1 to measuredCount,
whatever its score). So measuredCount==0 is an unambiguous "skipped", and this script renders
it as the word SKIPPED plus whatever reason RetrievalRun.skippedRepos recorded for that repo --
never as a number. See RetrievalStats.kt / SideAggregate's own KDoc for the source of that
contract; this script only honours it at presentation time.

Usage:
    extract-row.py <retrieval-result.json> [--label ARM_LABEL] [--ingest-json JSON]
                    [--repo REPO_ID] [--rows-log PATH] [--snapshot-dir DIR]

    --label        a name for the arm this measurement represents. Defaults to the result
                   file's own runId, or to --snapshot-dir's BUILD_INFO.json "label" when that
                   is given and --label is not.
    --ingest-json  a JSON object (as produced by cold-index.sh) carrying this arm's
                   durationMillis/indexSizeBytes, folded into the row's ingest columns. Omitted
                   entirely (never zero-filled) when not given.
    --repo         which repo's row to print. Defaults to excalidraw (D1: the only repo this
                   rig's retrieval denominator counts).
    --rows-log     append the row as one JSON line here too, in addition to printing the
                   human-readable line to stdout. Defaults to <result-dir>/rows.jsonl.
    --snapshot-dir a build-arm.sh snapshot directory (holds BUILD_INFO.json). Its "gitHead" and
                   "gitDirtyFileCount" are folded into the row's "provenance" so a published row
                   is traceable to the code state that produced it, not just to a human-typed
                   --label -- D14 makes an arm *be* a git code state, so this is the row's most
                   load-bearing field, not a nicety. When BUILD_INFO.json's own "label" disagrees
                   with --label, this is refused loudly (see assert_label_matches) rather than
                   silently trusting whichever one was typed last -- a snapshot reused under a
                   different label, or a --snapshot-dir pointed at the wrong arm's build, is
                   exactly the mislabelling this guards against. Omitted entirely, the row's
                   provenance is recorded as unknown rather than guessed at.

Exit code 0 with the row printed on success; non-zero with a message on stderr if the result
file doesn't cover the requested repo at all, or if --label disagrees with --snapshot-dir's
BUILD_INFO.json.
"""
import argparse
import json
import sys
from pathlib import Path


def side_row(side_key: str, side_label: str, aggregate: dict | None, skip_reasons: list[str]) -> dict:
    """One side's contribution to the row. `aggregate` is the RetrievalAggregate.<sideKey>
    value (already resolved to the target repo's group) -- None for CodeGraph runs where the
    field was entirely absent from the JSON (an archived schema-v1 result, or -- see
    RetrievalStats.aggregate's own comment -- a group where CodeGraph was never in the run at
    all, which is distinct from present-but-measuredCount-0)."""
    if aggregate is None:
        return {"side": side_key, "status": "NOT_IN_RUN", "measuredCount": None}
    measured = aggregate["measuredCount"]
    if measured == 0:
        reason = next((r for r in skip_reasons if side_label in r), None)
        return {
            "side": side_key,
            "status": "SKIPPED",
            "measuredCount": 0,
            "reason": reason or "measuredCount is 0 and no matching skippedRepos reason was found",
        }
    return {
        "side": side_key,
        "status": "MEASURED",
        "measuredCount": measured,
        "mrr": aggregate["mrr"],
        "recallAtK": aggregate["meanRecallAtK"],
        "precisionAtK": aggregate["meanPrecisionAtK"],
    }


def coverage_row(coverage_entries: list[dict], side_enum: str) -> dict | None:
    for c in coverage_entries:
        if c["side"] == side_enum:
            return {
                "citedFileCount": c["citedFileCount"],
                "presentFileCount": c["presentFileCount"],  # None means NOT_DETERMINABLE, never 0
                "basis": c["basis"],
            }
    return None


def load_provenance(snapshot_dir: Path | None, label: str | None) -> tuple[dict, str]:
    """Reads build-arm.sh's BUILD_INFO.json out of `snapshot_dir` and returns
    (provenance_dict, resolved_label).

    `provenance_dict` always has the same three keys -- snapshotDir/gitHead/gitDirtyFileCount --
    set to None across the board when `snapshot_dir` was not given, so a row's shape never
    depends on whether the caller supplied one; only its content does. "Unknown" is recorded
    explicitly, never left as an absent key a reader could mistake for "clean, no dirty files".

    `resolved_label` is `label` when given; otherwise BUILD_INFO.json's own recorded label (so a
    caller that only supplies --snapshot-dir still gets a real arm name instead of falling back
    to the result JSON's runId). Raises SystemExit -- refuses to proceed at all -- when both are
    given and disagree: this is the mislabelling guard the code-state-is-the-arm design (D14)
    depends on, and a silently-preferred value here is exactly the failure mode that makes a
    wrong verdict indistinguishable from a right one after the fact.
    """
    if snapshot_dir is None:
        return {"snapshotDir": None, "gitHead": None, "gitDirtyFileCount": None}, label

    info_path = snapshot_dir / "BUILD_INFO.json"
    if not info_path.is_file():
        raise SystemExit(
            f"--snapshot-dir {snapshot_dir} has no BUILD_INFO.json -- not a build-arm.sh "
            f"snapshot, or it predates this rig's provenance fix. Refusing to guess at "
            f"provenance rather than publish a row with none."
        )
    info = json.loads(info_path.read_text())
    recorded_label = info.get("label")
    if label is not None and recorded_label is not None and label != recorded_label:
        raise SystemExit(
            f"REFUSING: --label '{label}' does not match --snapshot-dir {snapshot_dir}'s "
            f"BUILD_INFO.json label '{recorded_label}'. This is the exact mislabelling this "
            f"check exists to catch -- either the snapshot was built for a different arm and "
            f"is being reused here by mistake, or --label is a typo. Fix whichever is wrong; "
            f"this script will not publish a row under an unverified label."
        )
    resolved_label = label if label is not None else recorded_label
    provenance = {
        "snapshotDir": str(snapshot_dir),
        "gitHead": info.get("gitHead"),
        "gitDirtyFileCount": info.get("gitDirtyFileCount"),
    }
    return provenance, resolved_label


SIDE_LABELS = {
    "contextGraph": "ContextGraph (this project)",
    "codeGraph": "CodeGraph (third-party)",
    "ripgrep": "ripgrep (baseline)",
}
COVERAGE_ENUM = {"contextGraph": "CONTEXT_GRAPH", "codeGraph": "CODE_GRAPH", "ripgrep": "RIPGREP"}


def build_row(run: dict, repo_id: str, label: str, ingest: dict | None, provenance: dict) -> dict:
    by_repo = run.get("summary", {}).get("byRepo", {})
    if repo_id not in by_repo:
        raise SystemExit(
            f"repo '{repo_id}' has no summary.byRepo entry in this result -- it was never "
            f"scored on any side (check runId={run.get('runId')}'s skippedRepos: "
            f"{run.get('skippedRepos')})"
        )
    aggregate = by_repo[repo_id]
    reasons = [s["reason"] for s in run.get("skippedRepos", []) if s["repoId"] == repo_id]
    coverage_entries = [c for c in run.get("goldFileCoverage", []) if c["repoId"] == repo_id]
    ingest_costs = {c["tool"]: c for c in run.get("ingestCosts", []) if c["repoId"] == repo_id}

    row = {
        "armLabel": label,
        "repoId": repo_id,
        "runId": run.get("runId"),
        "generatedAt": run.get("generatedAt"),
        # D14: an arm IS a git code state, so this is what ties a published row back to the
        # source that produced it -- see load_provenance's docstring. None across the board
        # (never a partially-filled dict) when no --snapshot-dir was given.
        "provenance": provenance,
        "questionCount": aggregate["questionCount"],
        "sides": {
            key: side_row(key, SIDE_LABELS[key], aggregate.get(key), reasons)
            for key in ("contextGraph", "codeGraph", "ripgrep")
        },
        "goldFileCoverage": {
            key: coverage_row(coverage_entries, COVERAGE_ENUM[key])
            for key in ("contextGraph", "codeGraph", "ripgrep")
        },
        # Recorded from the manifest RetrievalRun read (never measured, D6's ingestCosts KDoc)
        # when present; from --ingest-json (this rig's own cold-index.sh measurement) when
        # given, which takes precedence since it is what this rig itself just measured.
        "ingest": {
            "contextGraph": ingest if ingest is not None else ingest_costs.get("CONTEXTGRAPH"),
            "codeGraph": ingest_costs.get("CODEGRAPH"),
        },
    }
    return row


def format_provenance(provenance: dict) -> str:
    """One clause, meant to sit on the row's headline line rather than buried below it --
    which code state a row came from is exactly what the reviewer flagged as invisible, so it
    is surfaced at the same prominence as the arm label itself. A dirty tree is not an error
    (several arms in this run are legitimately measured from uncommitted working states, D14's
    "applied and reverted with git" doesn't require a commit in between) but a reader comparing
    rows across arms needs to see it without opening the JSON."""
    git_head = provenance.get("gitHead")
    dirty = provenance.get("gitDirtyFileCount")
    if git_head is None:
        return "source=UNKNOWN (no --snapshot-dir given at extraction time)"
    short_head = git_head[:12]
    if dirty is None:
        return f"source={short_head} (dirty-file-count unknown)"
    if dirty > 0:
        return f"source={short_head} ** DIRTY: {dirty} uncommitted file(s) **"
    return f"source={short_head} (clean)"


def format_human(row: dict) -> str:
    lines = [
        f"arm={row['armLabel']}  repo={row['repoId']}  n={row['questionCount']}  "
        f"runId={row['runId']}  {format_provenance(row['provenance'])}"
    ]
    for key in ("contextGraph", "codeGraph", "ripgrep"):
        s = row["sides"][key]
        if s["status"] == "SKIPPED":
            lines.append(f"  {key:<12} SKIPPED (measuredCount=0) -- {s['reason']}")
        elif s["status"] == "NOT_IN_RUN":
            lines.append(f"  {key:<12} not in this run (field absent)")
        else:
            r = s["recallAtK"]
            p = s["precisionAtK"]
            r5 = r.get("5", r.get(5))
            r10 = r.get("10", r.get(10))
            lines.append(
                f"  {key:<12} measuredCount={s['measuredCount']:<3} "
                f"MRR={s['mrr']:.4f}  R@5={r5:.4f}  R@10={r10:.4f}  "
                f"P@5={p.get('5', p.get(5)):.4f}  P@10={p.get('10', p.get(10)):.4f}"
            )
    for key in ("contextGraph", "codeGraph", "ripgrep"):
        cov = row["goldFileCoverage"][key]
        if cov is None:
            continue
        present = cov["presentFileCount"]
        cited = cov["citedFileCount"]
        if present is None:
            lines.append(f"  {key:<12} gold-file coverage: NOT_DETERMINABLE ({cov['basis']})")
        else:
            lines.append(f"  {key:<12} gold-file coverage: {present}/{cited} ({cov['basis']})")
    ci = row["ingest"]["contextGraph"]
    if ci is not None:
        secs = ci["durationMillis"] / 1000.0
        lines.append(f"  ingest(ContextGraph): {secs:.1f}s, {ci['indexSizeBytes']:,} bytes")
    else:
        lines.append("  ingest(ContextGraph): not measured")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("result_json", type=Path)
    ap.add_argument("--label", default=None)
    ap.add_argument("--ingest-json", default=None, help="JSON object string, e.g. cold-index.sh's stdout")
    ap.add_argument("--repo", default="excalidraw")
    ap.add_argument("--rows-log", default=None, type=Path)
    ap.add_argument("--snapshot-dir", default=None, type=Path)
    args = ap.parse_args()

    run = json.loads(args.result_json.read_text())
    ingest = json.loads(args.ingest_json) if args.ingest_json else None

    provenance, label = load_provenance(args.snapshot_dir, args.label)
    label = label or run.get("runId", args.result_json.stem)

    row = build_row(run, args.repo, label, ingest, provenance)

    rows_log = args.rows_log or (args.result_json.parent / "rows.jsonl")
    with rows_log.open("a") as f:
        f.write(json.dumps(row, sort_keys=True) + "\n")

    print(format_human(row))
    print(f"[row appended to {rows_log}]", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
