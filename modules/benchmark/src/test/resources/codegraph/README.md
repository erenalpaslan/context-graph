# `codegraph explore` output fixtures

Captured from **`@colbymchenry/codegraph` v1.5.0**, `darwin-arm64`, on 2026-08-22.

These exist because `CodeGraphExploreParser` reads markdown that no schema
governs, and a silently mis-parsing parser reads as *"CodeGraph retrieves
nothing"* — which would hand the three-way comparison a fabricated win for
ContextGraph and look entirely plausible doing it. Real output, committed, with
a test behind it, is the only thing that catches that.

`explore` has **no `--json` flag** (verified: its only options are `-p/--path`
and `--max-files`), so the markdown is the interface.

## How each fixture was produced

Three of the four are verbatim captures. Every one was taken from a project
indexed with `codegraph init`, because **`codegraph index` refuses to run
against a directory that was never `init`-ed** ("✗ CodeGraph not initialized …
ℹ Run \"codegraph init\" first"). `init` builds the initial index itself.

| Fixture | Command | Notes |
|---|---|---|
| `explore-small.md` | `codegraph explore -p <proj> authenticate user` | `<proj>` = a copy of `test-fixtures/kotlin-project` (4 indexed files). Two file sections. |
| `explore-large.md` | `codegraph explore -p <proj> "how does the retrieval benchmark score ripgrep against ContextGraph"` | `<proj>` = a copy of `modules/benchmark/src` (154 indexed files, 1,925 nodes). Four file sections, a populated blast-radius section naming *other* files, and the trailing budget-truncation note. |
| `explore-no-results.md` | `codegraph explore -p <proj> "how does the system handle the data"` | The empty case. A single line, no markdown structure at all. |
| `explore-low-confidence.md` | **Synthesized — see below.** | |

## `explore-low-confidence.md` is synthesized, and here is why

The brief for this work asked for a fixture covering the
`### ⚠️ Low-confidence match` sentinel that `dist/context/markers.d.ts`
documents. **`codegraph explore` cannot produce that sentinel in v1.5.0.**
Verified by reading the installed binary's own code:

- `codegraph explore` runs `handler.execute('codegraph_explore', …)`
  (`dist/bin/codegraph.js:1107`), which formats its output in
  `dist/mcp/tools.js`.
- `dist/mcp/tools.js` never references `LOW_CONFIDENCE_MARKER` and does not
  import `context/markers`. The only emitter is
  `ContextBuilder.buildLowConfidenceNote` in `dist/context/index.js:290`, on the
  separate `ContextBuilder` path, which the `explore` command does not reach.

So this fixture is `explore-small.md` with the low-confidence block appended
**exactly as `dist/context/index.js:290-299` concatenates it** — same marker,
same wording, same `codegraph_files` directory-hint line. It is labelled here
rather than passed off as a capture.

It still earns its place, and it proves two things worth proving:

1. A parser must not stop at or trip over the marker: every file section
   *above* it is still part of the answer.
2. **The low-confidence section contains no file paths at all** — only advisory
   prose and *directory* hints in backticks. So the question the brief asked
   ("do files under it count as ranked?") has no files to decide about, and the
   directory hints must not be mistaken for results.

## Regenerating

Any of the three real fixtures can be recaptured by re-running its command
against a freshly `codegraph init`-ed copy of the named project. Expect
incidental drift (symbol counts, budget truncation) between CodeGraph versions;
the parser test asserts on the *file list*, which is the contract that matters.
