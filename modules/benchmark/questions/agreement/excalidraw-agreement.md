# Excalidraw gold set — agreement and verification record

Repo: `excalidraw`, pinned SHA `a2ec2889babf7d2295469c6d90ebe77fae57df84`.
Checkout read: `.benchmark-corpus/excalidraw/without/`.

**This document describes the file that is actually on disk right now**
(9 questions, 47 gold facts, after three verification rounds). Earlier
versions of both `excalidraw.yaml` and this file described different
content and are superseded — see "Provenance" and "Round 3" below.

## Provenance

Task 08 was dispatched twice by the Lead (team startup failed twice on
transient 529s before a third, successful start; the retries re-dispatched
tasks that were already in progress). Two independent developer-agent
sessions worked this slice concurrently, on the same worktree, writing the
same two files, unaware of each other, producing substantively different
gold sets that landed on disk interleaved. One session detected the
collision mid-edit and stopped per its own instructions rather than resolve
it unilaterally, and escalated to the Lead. The Lead confirmed the other
session had exited, named this session sole remaining author, and directed
a full end-to-end re-verification of whatever was actually on disk (round
2, below) — not a resumption of either party's original work.

## Round 2 — first full re-verification (single-session)

Every fact on disk at that point (46 facts) was independently re-derived
against the pinned checkout, including facts this session itself had
written earlier — nothing carried forward on trust. Every question's
**text** was checked against its **facts** for coverage gaps (the specific
failure mode the Lead named from a sibling slice: "keycloak's question asked
about `order()` but no fact addressed it, so half the question went
unscored"). Three such gaps were found and closed by adding a fact or
narrowing the question's wording to match what the facts actually support:
q3 (added the shape-generation fact), q6 (narrowed "each package's main
entry" to name one package), q7/q9 (narrowed open-ended "what fields" /
"what MIME types" phrasing that implied exhaustive listing down to what was
actually evidenced).

### Standing process deviation

The task brief calls for a producer/verifier pair as two genuinely separate
agent processes. No tool available to a Developer-tier agent in this harness
can spawn an independent second agent session (`ToolSearch` was queried
repeatedly for spawn/dispatch/Task/Agent-type tools across every round; none
exists here — `SendMessage` only reaches already-existing named teammates).
Round 2's substitute was single-session, line-by-line re-derivation against
the pinned checkout. **Round 3 (below) closes this gap**: the Lead ran a
second developer-agent session as a genuinely independent verifier, blind to
this document, instructed to assume nothing had been checked.

## Round 3 — genuinely independent second verifier + fixes

The Lead spawned a separate agent session that reviewed the round-2 file
cold (no access to this document's reasoning) and judged all 46 facts
against the pinned checkout. Result:

**45 of 46 SUPPORTED, 1 OVERSTATED, 0 WRONG_LINE, 0 UNSUPPORTED.**

No fact was factually false. This closes the AC-6 independence requirement
for the file as it stood after round 2 — including the ~18 facts this
session had added or changed after any prior verifier had looked at them.

**Clarification on question count:** an earlier reviewer read the task
brief's Turkish "7 ya da 9 sorun değil" as objecting to 9 questions. That
phrase means "7 or 9 is not a problem" — i.e. explicitly permits either
count; the ratio matters, not hitting exactly 8. 9 questions is compliant.
**q9 was not cut.**

### Issues raised and how each was resolved

**(a) q3-f6 OVERSTATED — fixed.** `ShapeCache.ts:70` shows only the
`_generateElementShape(...)` call; the statement additionally claimed
"then stores the result in the WeakMap before returning it", which is a
separate action at `:82` (`ShapeCache.cache.set(...)`) with the return at
`:84`, 12-14 lines below the cited anchor. Also the third argument is
`renderConfig || { isExporting: false, canvasBackgroundColor:
COLOR_PALETTE.white, embedsValidationStatus: null }` (`:73-77`), not bare
`renderConfig`. **Fix:** narrowed the statement to describe only what's at
`:70` — the call itself, with the correct (fallback-inclusive) third
argument — and dropped the WeakMap-storage claim rather than add a 7th fact
past the cap. Re-verified against the source in this round.

**(b) q7 and q9 graded on the author's arbitrary pick rather than on
knowledge — fixed by tightening question text, facts unchanged.**
- q9 asked "name THREE of the image MIME types" but only graded svg/png/jpg
  — a correct answer of gif/webp/bmp would score 0/3. **Fix:** question text
  now names the specific entries: "What MIME types do IMAGE_MIME_TYPES's
  svg, png, and jpg entries map to...".
- q7 asked for "TWO EXAMPLE fields" but only graded `boundElements` and
  `version` — a correct answer of `id`/`x`/`isDeleted` would score 0/2.
  **Fix:** question text now names them: "...what do the boundElements and
  version fields (defined on every element's shared base type)
  represent?".
No facts were loosened to match a vague question; the questions were
tightened to match the specific facts, per the Lead's explicit instruction.

**(c) Weaker anchors — fixed.**
- q4-f3 bundled two claims under one citation: "reconcileElements is
  re-exported from index.tsx" (supported at `:229`) **and** "which is how
  excalidraw-app imports it" (only supported in a different file,
  `Collab.tsx:24-25`, where the actual `import { ..., reconcileElements }
  from "@excalidraw/excalidraw"` sits). **Fix:** split into two facts —
  f3 narrowed to just the re-export claim (`index.tsx:229`), new f4 added
  for the Collab.tsx import site (`Collab.tsx:24`). q4 grew from 5 to 6
  facts, still within cap; this also gives q4 a third cross-file/
  cross-package hop, partially addressing point (e) below.
- q6-f1 claimed a five-entry workspaces list from a citation showing only
  the first entry. **Fix:** reworded to state precisely what's visible from
  the cited line and its immediate, verifiable adjacency ("begins with
  \"excalidraw-app\" ... immediately followed on the next four lines by
  ..."), rather than claiming the citation alone proves all five. Evidence
  line unchanged (`:6`); re-confirmed lines 7-10 contain the other four
  entries in that exact order.
- q7-f1: the round-2 pass had moved this from `:203` to `:204` reasoning
  that `:203` was "just the declaration, not a variant member." The round-3
  verifier judged `:203` (the `export type ExcalidrawElement =` line itself)
  the better anchor for a claim about the union as a whole, since `:204`
  only shows the *first* of ten variants, not the union. **Deferred to the
  second verifier's judgment; reverted to `:203`.**
- q3-f2 attributed "a requestAnimationFrame-throttled wrapper" to
  `renderStaticSceneThrottled` at a citation (`:475`) that shows only the
  delegation call, not the `throttleRAF(...)` definition (which is at
  `:460`). **Fix:** narrowed the statement to just the delegation behavior
  actually visible at `:475` ("delegates ... and returns immediately,
  instead of falling through to the direct `_renderStaticScene` call"),
  dropping the RAF-throttled characterization rather than spend another
  fact on a claim outside q3's asked scope (the question doesn't ask about
  throttling at all — f2 is supplementary color, not required).

**(d) Decided and recorded, not silently accepted.**
- q8 asks "and where are they defined?" but no fact's *statement* asserted
  the file, only the evidence citation did (which a grader may or may not
  surface). **Decision: tighten.** All four `q8` statements now explicitly
  say "defined in excalidraw-app/app_constants.ts" so the location claim is
  in the graded statement itself, not only in the evidence metadata.
- q3-f3 hedges to "one of several such call sites" (`renderElement` is
  called from six locations in the static-scene render routine: `:314`,
  `:324`, `:337`, `:371`, `:391`, `:444`). **Decision: accept the hedge,
  do not tighten to one arbitrary "the" call site.** All six sites are
  real and equally valid evidence for "the routine calls renderElement
  for each element it draws" — picking one and calling it *the* site would
  overclaim a specificity the code doesn't have. Grading against this fact
  should accept any of the six as satisfying it. Recorded here explicitly
  per the Lead's instruction rather than left ambiguous.

**(e) q1 and q2 are the weakest GRAPH_HEAVY pair — noted as a known
limitation, not restructured.** Both are essentially two-file chains
(`dragElements.ts`/`binding.ts` for q1; `actionDeleteSelected.tsx`/
`binding.ts` for q2) with most facts landing in `binding.ts`, so an agent
that greps the entry symbol and reads that one file can pick up most of the
credit without doing much cross-file graph work. The Lead characterized
this as "not a blocker." Given the scope of round 3 was fixing named defects
rather than restructuring sound questions, **this was left as-is** and is
recorded here for whoever next revises this repo's question set — adding a
genuine third hop to q1 or q2 (e.g. into the call site that triggers
`fixBindingsAfterDeletion` from a non-`actionDeleteSelected` path, if one
exists) would strengthen it.

## Final shape of the file

9 questions, **47 gold facts** (46 after round 2, +1 from splitting q4-f3 in
round 3), all with `file:line` evidence independently confirmed against the
pinned checkout in both round 2 (this session) and round 3 (the Lead's
independent verifier, for the pre-fix state):

| Question | Category | Facts | Topic |
|---|---|---|---|
| excalidraw-q1 | GRAPH_HEAVY | 6 | Bound-arrow repositioning on drag: compute (`updateBoundPoint`) vs apply (`LinearElementEditor.movePoints`) |
| excalidraw-q2 | GRAPH_HEAVY | 5 | Binding cleanup on element deletion (`fixBindingsAfterDeletion`, `BoundElement`/`BindableElement.unbindAffected`) |
| excalidraw-q3 | GRAPH_HEAVY | 6 | Static-canvas render chain, roughjs shape cache hit vs generate |
| excalidraw-q4 | GRAPH_HEAVY | 6 | Collaboration socket reconciliation (`reconcileElements`, cross-package import, `shouldDiscardRemoteElement`) |
| excalidraw-q5 | GRAPH_HEAVY | 6 | Ctrl+Z keydown call chain to `History` actually popping the undo stack |
| excalidraw-q6 | NEUTRAL | 5 | Monorepo workspace packages, names, main entry point |
| excalidraw-q7 | NEUTRAL | 4 | `ExcalidrawElement` union variants, `ExcalidrawElementType`, `boundElements`/`version` fields |
| excalidraw-q8 | NEGATIVE_CONTROL | 4 | Literal localStorage key strings, with file asserted in-statement |
| excalidraw-q9 | NEUTRAL | 5 | `IMAGE_MIME_TYPES` svg/png/jpg, `MIME_TYPES` vs `IMAGE_MIME_TYPES` |

**Category split: 5 GRAPH_HEAVY / 3 NEUTRAL / 1 NEGATIVE_CONTROL** (55.6% /
33.3% / 11.1%), unchanged by round 3's fixes (no question was added, removed,
or recategorized). Checked against `CategoryDistributionAuditor` (target
60/25/15, ±20pt tolerance, no target category may be zero): `GRAPH_HEAVY`
deviation 4.4pt, `NEUTRAL` deviation 8.3pt, `NEGATIVE_CONTROL` deviation
3.9pt — all within tolerance, no category at zero. **`audit.passes == true`**,
re-verified by running the auditor against the round-3 file.

## Decision on q9 (extra question beyond ~8)

q9 is not a duplicate of q7 (different part of `element/types.ts` and
`constants.ts` entirely) and its facts hold up under two independent
verification passes now. 9 questions at 5/3/1 passes
`CategoryDistributionAuditor`, and per the Turkish-wording clarification
above, 9 is explicitly permitted by the task brief. **q9 is kept.**

## Mechanical checks (round 3, post-fix)

- All 47 `evidence` citations: file exists under
  `.benchmark-corpus/excalidraw/without/` **and** the cited line number is
  within that file's line count. 0 failures (script-checked).
- `QuestionSetLoader.loadFile` against the real
  `modules/benchmark/questions/excalidraw.yaml`: **0 validation errors**,
  9 questions loaded, all `repoId == "excalidraw"`, all fact counts in
  `3..6`, category counts 5/3/1, `CategoryDistributionAuditor.passes ==
  true`. Verified via a throwaway Kotest test (`ExcalidrawGoldSetRound3Check`),
  run with captured stdout, then deleted (not part of the permanent suite;
  the shared worktree's concurrent Gradle daemon caused one flaky
  compilation error and one run whose XML result was overwritten by a
  concurrently-running sibling test filter — both are build-infrastructure
  noise from sharing this worktree with other slices, not a problem with
  this file; the run was retried with `-i --rerun-tasks` and its stdout
  captured directly, showing `BUILD SUCCESSFUL` with the exact expected
  counts).

## Escalations to the human (AC-6 — nothing silently dropped)

1. **Duplicate dispatch of this slice** (see Provenance). No content
   consequence remains — everything has now been independently re-verified
   twice over (round 2 single-session, round 3 genuinely independent
   second agent) — but the Lead should be aware two full developer-agent
   sessions burned time on the same slice due to retry-after-529
   re-dispatching already-in-progress work.
2. **q3-f3's "one of several call sites" hedge is accepted deliberately**
   (point (d) above) — grading must accept any of the six `renderElement`
   call sites in `staticScene.ts` (`:314/324/337/371/391/444`), not just
   `:314`. This is a grading-harness note, not a data defect.
3. **q1 and q2 remain a structurally weak GRAPH_HEAVY pair** (point (e)
   above) — both are effectively 2-file chains concentrated in
   `binding.ts`. Not fixed in this round per the Lead's "not a blocker"
   guidance; flagged for whoever next revises this set to consider adding
   a third hop.
4. **Scope-narrowing decisions on q6/q7/q9's question text were made
   unilaterally** by a single agent session in round 2 (no second party to
   disagree with at the time) when a question's wording claimed broader
   coverage than its facts supported within the 3-6 fact cap. Round 3's
   independent verifier reviewed the resulting file and did not object to
   these narrowings beyond what's listed in (b) above (which was a
   further tightening of the same kind). A human reviewer may still prefer
   expanding facts instead of narrowing question text in a future pass
   (e.g. adding math/utils `main` entries to q6 as their own facts, or
   splitting q6 into two questions) — flagging so that preference can be
   exercised if desired.

No fact currently in the file is flagged as disputed. All 47 have now been
checked against the pinned checkout by at least one full independent pass
(round 2, this session) plus, for the 46 facts that existed at that point,
a second genuinely separate agent (round 3); the one fact that second
verifier found overstated has been corrected and the fix re-verified in
this round, along with the one fact added afterward (q4's new f4).
