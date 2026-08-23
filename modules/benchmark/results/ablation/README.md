# Ranking ablation — raw results

One directory per configuration measured while making `ContextBundler`'s final sort a function of
the query. Each holds the run's result JSON and the `BENCHMARKS.md` the runner generated from it.

**The table, what shipped, what did not and why: [`docs/retrieval-ranking-ablation.md`](../../../../docs/retrieval-ranking-ablation.md).**

| directory | configuration | commit |
|---|---|---|
| `priv-0-baseline` | `main`, unchanged | `c4c7436` |
| `priv-1-search-relevance` | + the search layer's relevance in the sort | `d12c3b9` |
| `priv-2-deprioritise` | + documentation and tests de-prioritised | `586d5d5` |
| `priv-3-name-match` | + name ladder, CodeGraph's five tiers | `a62dd6c` |
| `priv-3b-name-match-reduced` | + name ladder, harmful tier removed | `1e9bf24` |
| `priv-3c-name-removed` | − name ladder removed | `34803f1` |
| `priv-4-kind` | + kind ladder | `d673a58` |
| `priv-5-path` | + path relevance | `b730824` |
| `priv-6-exact-name` | + exact-name supplement, entering first | `b10da99` |
| `priv-6b-exact-name-last` | + exact-name supplement, entering last | `a8dce53` |
| `priv-7-item6-removed` | − supplement removed | `3a15af8` |
| `priv-8-shipped` | − kind ladder removed — **the shipped configuration** | `91a29a5` |
| `priv-9-interaction-check-item6` | the supplement re-applied to the shipped base | not committed |

Every row was measured against the same frozen copy of the excalidraw corpus, so the rows are
comparable to each other and to nothing else. The ripgrep and CodeGraph sides are in every JSON and
are identical across all of them, which is the control that says the instrument did not drift.

## The two scripts

`ablation_row.py <result.json>...` prints the four columns for all three sides. It lives outside the
benchmark sources deliberately: the run was forbidden to modify the harness, and the docs-share
column is the one metric the harness does not compute. Its definition is pooled — documentation
files in the top tens over all files in the top tens — and it reproduces the 35.62% published for
the baseline before it is trusted on anything else.

`check_prohibited.sh [base-revision]` diffs every file the run was forbidden to touch against a base
revision, so "we did not touch them" is a check a reader can re-run rather than a claim.
