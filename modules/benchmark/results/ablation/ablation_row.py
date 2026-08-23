#!/usr/bin/env python3
"""One ablation row, computed from a retrieval run's own result JSON.

Deliberately outside the benchmark sources: the run's prohibitions forbid touching the
harness, and a metric the harness does not compute (docs share of top-10) is the one column
the ablation table needs beyond what RetrievalStats already prints.

docs share is POOLED, not the mean of per-question shares: (docs files in the top-10 of every
measured question) / (all files in those top-10s). Verified to reproduce the 0.3562 published
for the baseline three-way run, which is the number the whole brief argues from.

A docs file is one whose path ends .md or .mdx, or which has a path segment naming a docs
directory (docs, doc, documentation, or any *-docs / *_docs compound). No path, name or
extension from the gold question set appears here.
"""
import json
import sys

DOC_EXTS = (".md", ".mdx")
DOC_DIR_NAMES = ("docs", "doc", "documentation")


def is_doc(path):
    p = path.lower()
    if p.endswith(DOC_EXTS):
        return True
    for seg in p.split("/")[:-1]:
        if seg in DOC_DIR_NAMES or seg.endswith("-docs") or seg.endswith("_docs"):
            return True
    return False


def row(path, side="contextGraph", k=10, repo=None):
    run = json.load(open(path))
    sides = [
        (r["questionId"], r[side])
        for r in run["results"]
        if r.get(side) and (repo is None or r["repoId"] == repo)
    ]
    if not sides:
        return None
    mrr = sum(s["reciprocalRank"] for _, s in sides) / len(sides)
    r5 = sum(s["recallAtK"]["5"] for _, s in sides) / len(sides)
    r10 = sum(s["recallAtK"]["10"] for _, s in sides) / len(sides)
    files = [f for _, s in sides for f in s["rankedFiles"][:k]]
    docs = sum(1 for f in files if is_doc(f))
    return {
        "n": len(sides),
        "MRR": mrr,
        "R@5": r5,
        "R@10": r10,
        "docsShare": (docs / len(files)) if files else 0.0,
        "filesInTop10": len(files),
        "runId": run["runId"],
    }


if __name__ == "__main__":
    args = sys.argv[1:]
    repo = None
    if args and args[0].startswith("--repo="):
        repo = args.pop(0).split("=", 1)[1]
    for p in args:
        for side in ("contextGraph", "ripgrep", "codeGraph"):
            r = row(p, side, repo=repo)
            if r:
                print(
                    "%-40s %-13s n=%2d  MRR=%.4f  R@5=%.4f  R@10=%.4f  docs=%.4f (%d files)"
                    % (p.split("/")[-2], side, r["n"], r["MRR"], r["R@5"], r["R@10"], r["docsShare"], r["filesInTop10"])
                )
