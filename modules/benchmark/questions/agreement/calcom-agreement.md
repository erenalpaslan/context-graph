# cal.com gold set — producer/verifier agreement record

Repo: `calcom` · Pinned SHA: `1c193cca8682b33b9866c792186033f7ef886682` · Checkout read: `.benchmark-corpus/calcom/without/`

## How this file came to exist: duplicate dispatch, reconciled by one final writer

Task 10 was dispatched multiple times by the Lead — the team was restarted
three times (two runs died to transient 529s) and already-in-progress tasks,
including 10, 08, and 11, were redispatched into work that was still
ongoing. Independent developer-agent sessions therefore wrote to
`modules/benchmark/questions/calcom.yaml` concurrently, unaware of each
other, more than once over the life of this task. This was caught the first
time (a session hit the file changing under it mid-edit, stopped per its own
conflict-handling instructions rather than resolving it unilaterally, and
reported it) and resolved by the Lead: **the version on disk at each
checkpoint is the base**, one session is the standing authoritative writer,
and every fact — regardless of who wrote it — gets independently
re-verified rather than trusted on authorship. **It recurred a second time**
between the Lead's first and second independent-verifier passes: `q5` was
replaced entirely (a `CALCOM_TELEMETRY_DISABLED` question became a
`HashedLink` usage-based-expiration question) and `q6` was rewritten with a
broader scope, both by a process other than the standing writer. This is
disclosed here rather than smoothed over, and is why some of the Lead's
second-pass findings below reference content (the old `q5`, an earlier `q6`)
that no longer exists verbatim — each is addressed against what actually
governs the file now, not silently dropped because the target moved.

## Round 2 — fixes applied when this file was reconciled after the first duplicate write

Summary (full narrative in git history of this file / prior task reports):
added `calcom-q3-f6` for the "falls back to no-op" clause `q3`'s text asks
about but no fact had covered (the keycloak-`order()`-style gap); re-cited
`calcom-q4-f5` after finding its original citation split across two lines;
replaced the original `q5` (structurally identical to `q1` — schema +
migration + guard + throw, twice) with a different-shaped question;
reworded `q3` and `q6`'s framing for honesty; fixed a negative-control
contamination where `q8-f2` duplicated another question's evidence.

**Carried forward from round 2, not silently dropped (AC-6):**
1. Replacing the original `q5` removed the only gold fact that tested
   `disableCancelling`'s guard-throws-"This event type does not allow
   cancellations" connection (`handleCancelBooking.ts:211`). No question in
   the current set covers that specific path. Still true after round 4's
   `q5` replacement below (a *different* new `q5` now exists, but nothing
   revived that specific coverage).
2. `q8`'s question text does not promise HTTP status codes (dropped after
   narrowing `q8-f1/f3/f4` to fix adjacent-line brittleness left no
   single-line citation able to support that claim for three of the four
   facts). `q8-f2`'s single-line throw is the exception and still safely
   carries a statusCode claim.

## Round 3 — Lead's first independent mechanical backtick-anchor check

`calcom.yaml` is the only one of the four repos' gold sets that
backtick-quotes literal code fragments inside fact statements (`gin.yaml`,
`excalidraw.yaml`, `keycloak.yaml` don't) — which is what made a mechanical
"does this quoted code actually appear on the cited line" check possible at
all. **This format is being kept**, per the Lead's explicit instruction.

Two findings: `calcom-q2-f3` was a false alarm (its `...` elision is the
established "more here, abbreviated" convention, not a claim the source
contains literal dots — confirmed correct, left unchanged). `calcom-q4-f5`
was real: the round-2 citation fix moved to line 2796 but left a
backtick-quoted clause that only lived on line 2794. Fixed by narrowing the
statement to a single backtick span (`` `deps.webhookProducer.queueBookingRequestedWebhook` ``)
fully present on the cited line, with the PENDING-guard context moved to
plain, non-backtick prose.

## Round 4 — Lead's second independent verifier (blind, full-file) + this session's fixes

The Lead ran a second, genuinely independent verifier over the file, blind
to this agreement doc and told to assume nothing had been checked —
closing the AC-6 independence gap for the whole file, including facts added
or changed after the first verifier saw it. Result against the version it
checked: **36 of 38 SUPPORTED, 2 OVERSTATED, 0 WRONG_LINE, 0 UNSUPPORTED**,
and confirmed the round-3 `q4-f5` fix was genuinely fixed, not moved (every
`throw new HttpError({...})` citation quotes only what's physically on its
line). No doc-vs-code contradiction relied on by any fact.

By the time this session acted on the findings, `q5` and `q6` had already
changed again (see the disclosure at the top of this file) — so each
finding below is addressed against **current** content, not the snapshot
the verifier saw.

### a) `calcom-q3-f2` overstated the uniqueness of its doc citation — FIXED

The claim "is the one that explicitly names" `CALCOM_LICENSE_KEY` was false
repo-wide: also named at `README.md:642`,
`packages/features/ee/organizations/README.md:13`, `apps/api/v1/README.md:215`,
and `apps/api/v2/README.md:28` — independently confirmed by grepping the
pinned checkout, all four hits are real. It genuinely is unique *within*
`docs/` (`grep -rl CALCOM_LICENSE_KEY docs/` returns exactly one file).

**Fix**: scoped both `q3`'s question text and `q3-f2`'s statement explicitly
to `docs/`, and had `q3-f2` name the repo-wide READMEs itself rather than
implying uniqueness it doesn't have — an agent that finds `README.md:642`
should not be graded wrong for finding real, correct information the
question no longer claims exclusivity over.

### b) `calcom-q3` vs. old `calcom-q5` inconsistency — MOOT (target changed), underlying q3 problem still fixed

The specific inconsistency the Lead flagged (`q5-f1` treating `README.md:665`
as documentation while `q3` implicitly excludes README.md) no longer exists
because `q5` was independently replaced with the `HashedLink`
usage-based-expiration question (round-4 disclosure above) — it makes no
README claim at all. Recorded rather than silently dropped because the
underlying problem finding (a) fixed — `q3` overclaiming doc-naming
exclusivity — was real and independent of what `q5` says; fixing `q3` on
its own merits was still done regardless of `q5`'s content.

### c) `calcom-q4-f5`'s parenthetical was untrue — FIXED

"The pending-status guard sits on the line immediately above this call" is
false: the guard (`if (booking && booking.status === BookingStatus.PENDING
&& !isDryRun) {`) is on line 2794, the call is on line 2796, and line 2795
(`try {`) sits immediately above — independently re-confirmed by reading
lines 2793-2796 fresh.

**Fix**: replaced the false adjacency claim with "a few lines apart, inside
the same try block" — true without asserting a specific line-count that
isn't.

### d) old `calcom-q6-f3` ("seat-display-related" framing) — the question's scope had already changed by the time this session acted

The Lead's finding was against the question text "seat-display-related
boolean fields," which legitimately excludes `onlyShowFirstAvailableSlot`
(it lives in the Limits tab, trims slot lists via
`packages/trpc/server/routers/viewer/slots/util.ts:1470`, no seat
involvement) — the exact defect described. By the time this session read
the file, `q6` had already been rewritten with a different, broader scope:
"every Boolean field in the `seatsPerTimeSlot`→`schedulingType` schema
block whose name doesn't start with `disable`" — which is exactly the
"rewrite the question so the field legitimately belongs" fix the Lead
offered as the alternative to dropping the fact.

**Independently re-verified this round** (not assumed correct because it
was already there): read `schema.prisma:220-233` fresh. The block has
exactly four Boolean fields not prefixed `disable`:
`onlyShowFirstAvailableSlot` (223), `showOptimizedSlots` (224),
`seatsShowAttendees` (229), `seatsShowAvailabilityCount` (230) — all four
are present as `q6-f1..f4`, nothing missing, nothing wrongly included.
`disableCancelling`/`disableRescheduling` (225-226, correctly excluded by
the `disable`-prefix rule) and `minimumRescheduleNotice` (228, `Int?`, not
`Boolean`, correctly excluded) round out the block. This framing holds up
under independent re-derivation, not just because it was already on disk.

### e) Cross-artifact spread — recorded honestly, target moved

The Lead's assessment ("q5 marginal, README↔TS only, effectively 2 artifact
types") was of the now-superseded telemetry `q5`. The **current** `q5`
(`HashedLink` usage-based expiration) is a different question, independently
assessed fresh this round: `schema.prisma` (code) + `migration.sql`
(SQL-migration) + `hashedLinksUtils.ts` (code, two facts) + `errorCodes.ts`
(code) + `docs/api-reference/v2/openapi.json` (doc — the v2 API's OpenAPI
spec, confirmed to sit inside the `CreatePrivateLinkInput` schema block).
That's **3 artifact types** (code + SQL-migration + doc) — not marginal, and
not the question the Lead evaluated. See the updated cross-artifact table
below for the full, current per-question breakdown.

### This session's own additional catch (found during independent re-verification, not in the Lead's list)

`calcom-q5-f3` (the `HashedLink` question, unfamiliar content this session
had never verified before this round) had the same class of defect as
finding (c): its statement claimed the usage-based check was "two lines
below" the cited time-based check on `hashedLinksUtils.ts:104` — the actual
usage-based check is on line 108, four lines below, not two (independently
counted: 104→105→106→107→108). **Fixed** the same way as (c): replaced the
specific, wrong line-count with "later in the same function," which is true
without asserting a number that isn't.

An adjacency-claim sweep was run across the whole file after these fixes
(`grep -n "line above\|line below\|lines above\|lines below\|immediately
above\|immediately below"`) — after fixing (c) and this catch, zero
remaining numeric-adjacency claims exist anywhere in `calcom.yaml`.

## Category distribution and loader proof (current file, post-round-4 fixes)

Mechanical re-check:

```
Total facts: 41
Bad line/file citations: 0
Backtick spans checked: 37, mismatches: 0
Duplicate evidence citations: {}
```

Per-question fact counts, all within the 3-6 range: q1=6, q2=5, q3=6, q4=6,
q5=6, q6=4, q7=4, q8=4.

`QuestionSetLoader.loadFile` + `CategoryDistributionAuditor`, via a
throwaway Kotest test (deleted immediately after running):

```
Category distribution for repo 'calcom' (8 questions):
  GRAPH_HEAVY: 62.5% (target 60.0%, count=5)
  NEUTRAL: 25.0% (target 25.0%, count=2)
  NEGATIVE_CONTROL: 12.5% (target 15.0%, count=1)

passes=true counts={GRAPH_HEAVY=5, NEUTRAL=2, NEGATIVE_CONTROL=1}
total facts=41, unique fact ids=41
unique evidence citations=41 of 41
```

62.5% / 25.0% / 12.5% against the 60/25/15 target, `passes=true`.

## Question-text-to-gold-fact coverage audit (the keycloak-`order()` trap, checked for every question, current content)

| Question | Text clauses | Covered by |
|---|---|---|
| q1 | rejection condition / HTTP status / error code / schema+migration origin | f3 / f4 / f5+f6 / f1+f2 |
| q2 | db column / signing code / documented verification steps | f1 / f3+f4 / f5 |
| q3 | doc (in docs/) that doesn't name the var / doc (in docs/) that does / function reading it / factory calling that function / fallback condition | f1 / f2 / f3 / f4 / f5+f6 |
| q4 | schema origin / first-read function / isConfirmedByDefault check / alternate webhook / doc corroboration | f1(+f2) / f3 / f4 / f5 / f6 |
| q5 | migration-added column + default / boundary condition for usage exhaustion / time-vs-usage check order / error code / v2 API doc corroboration | f1+f2 / f4 / f3 / f5 / f6 |
| q6 | every non-`disable`-prefixed Boolean in the named schema block, with type+default | f1, f2, f3, f4 |
| q7 | cause of P3005 / naming / resolve-applied remedy / full-reset remedy | f1 / f2 / f3 / f4 |
| q8 | four literal messages (cancelled / no user / missing reason / ended) | f1 / f2 / f3 / f4 |

No clause found without a supporting fact.

## Cross-artifact audit (GRAPH_HEAVY questions only, current content)

| Question | Types crossed | Doc/config-pulling? |
|---|---|---|
| calcom-q1 (booking seats capacity) | code + SQL-migration | No — code+SQL-migration only |
| calcom-q2 (webhook HMAC signing) | code + SQL-migration + doc | Yes (doc) |
| calcom-q3 (CALCOM_LICENSE_KEY resolution chain, scoped to docs/) | doc + code | Yes (doc) |
| calcom-q4 (requiresConfirmation → BOOKING_REQUESTED) | code + SQL-migration + doc | Yes (doc) |
| calcom-q5 (HashedLink usage-based expiration) | code + SQL-migration + doc | Yes (doc) |

5/5 GRAPH_HEAVY questions cross ≥2 artifact types. `schema.prisma` facts are
counted as `code`; `migration.sql` facts as `SQL-migration` (raw SQL DDL,
unambiguous regardless of how `schema.prisma` is classified);
`openapi.json`/`.mdx` facts as `doc`. 4/5 (q2/q3/q4/q5) pull in docs
specifically; q1 is the one question that stays in the code+SQL-migration
pair — a genuinely simpler, single-hop shape, kept deliberately alongside
four richer ones rather than an oversight. This is a *stronger* spread than
the version the Lead's second pass evaluated (where the old `q5` was
2-artifact and marginal); the current `q5` is a 3-type question like `q2`
and `q4`.

## Negative control genuineness

`calcom-q8` asks for four literal, fixed-string HTTP error messages thrown
by early, sequential, structurally-unrelated validation guards in one
function (`handleCancelBooking.ts`, lines 200/205/226/233). Each is
answerable by a single `grep -n "throw new HttpError"
packages/features/bookings/lib/handleCancelBooking.ts` followed by reading
the four hits — no relational/graph traversal helps here. Re-confirmed this
round: none of its four evidence lines overlaps with any other question's
evidence (zero duplicate citations across all 41 facts).

## Docs-vs-code staleness check

Checked again this round for `q5`'s new doc citation specifically:
`openapi.json:26492`'s `maxUsageCount` description was confirmed to sit
inside the `CreatePrivateLinkInput` schema block (`grep -n
"CreatePrivateLinkInput"` → definition at line 26481, the field at
26490-26495 is inside it, not a different, unrelated schema's field that
happens to share a name). No contradiction found anywhere in the current
cited set.

## Appendix: full re-verification table (round 4, current file)

Fact id / evidence / raw cited-line content, independently re-confirmed by
reading the pinned checkout fresh this round:

```
calcom-q1-f1  packages/prisma/schema.prisma:222                                             seatsPerTimeSlot Int?
calcom-q1-f2  packages/prisma/migrations/20220413173832_add_seats_to_event_type_model/migration.sql:2   ALTER TABLE "EventType" ADD COLUMN "seatsPerTimeSlot" INTEGER;
calcom-q1-f3  packages/features/bookings/lib/handleSeats/create/createNewSeat.ts:77          if (input.seatsPerTimeSlot > 0 && input.seatsPerTimeSlot <= currentSeatCount) {
calcom-q1-f4  packages/features/bookings/lib/handleSeats/create/createNewSeat.ts:79          statusCode: 409,
calcom-q1-f5  packages/features/bookings/lib/handleSeats/create/createNewSeat.ts:80          message: ErrorCode.BookingSeatsFull,
calcom-q1-f6  packages/lib/errorCodes.ts:22                                                  BookingSeatsFull = "booking_seats_full_error",
calcom-q2-f1  packages/prisma/schema.prisma:1189                                             secret String?
calcom-q2-f2  packages/prisma/migrations/20220614090326_add_webhook_secret/migration.sql:2   ALTER TABLE "Webhook" ADD COLUMN "secret" TEXT;
calcom-q2-f3  packages/features/webhooks/lib/sendPayload.ts:322                              ? createHmac("sha256", params.secret).update(`${params.body}`).digest("hex")
calcom-q2-f4  packages/features/webhooks/lib/sendPayload.ts:340                              "X-Cal-Signature-256": createWebhookSignature({ secret: secretKey, body }),
calcom-q2-f5  docs/developing/guides/automation/webhooks.mdx:1675                            Compare the hash received... x-cal-signature-256...
calcom-q3-f1  docs/self-hosting/license-key.mdx:6                                            ...self-host Cal.com with our Commercial License, you need to purchase a License Key...
calcom-q3-f2  docs/developing/guides/api/how-to-setup-api-in-a-local-instance.mdx:12          Add a staging license key... `CALCOM_LICENSE_KEY`...
calcom-q3-f3  packages/features/ee/deployment/lib/getDeploymentKey.ts:8                       if (process.env.CALCOM_LICENSE_KEY) {
calcom-q3-f4  packages/features/ee/common/server/LicenseKeyService.ts:38                      const licenseKey = await getDeploymentKey(deploymentRepo);
calcom-q3-f5  packages/features/ee/common/server/LicenseKeyService.ts:40                      const useNoop = !licenseKey || process.env.NEXT_PUBLIC_IS_E2E === "1";
calcom-q3-f6  packages/features/ee/common/server/LicenseKeyService.ts:41                      return !useNoop ? new LicenseKeyService(...) : new NoopLicenseKeyService();
calcom-q4-f1  packages/prisma/schema.prisma:205                                              requiresConfirmation Boolean @default(false)
calcom-q4-f2  packages/prisma/migrations/20210717120159_booking_confirmation/migration.sql:6 ALTER TABLE "EventType" ADD COLUMN "requiresConfirmation" BOOLEAN NOT NULL DEFAULT false;
calcom-q4-f3  packages/features/bookings/lib/handleNewBooking/getRequiresConfirmationFlags.ts:54  let requiresConfirmation = eventType?.requiresConfirmation;
calcom-q4-f4  packages/features/bookings/lib/handleNewBooking/getRequiresConfirmationFlags.ts:92  return (!requiresConfirmation && price === 0) || userReschedulingIsOwner;
calcom-q4-f5  packages/features/bookings/lib/service/RegularBookingService.ts:2796            await deps.webhookProducer.queueBookingRequestedWebhook({  [only backtick span: `deps.webhookProducer.queueBookingRequestedWebhook`, verbatim on this line]
calcom-q4-f6  docs/developing/guides/automation/webhooks.mdx:585                             "requiresConfirmation": true,
calcom-q5-f1  packages/prisma/schema.prisma:1252                                             maxUsageCount Int @default(1)
calcom-q5-f2  packages/prisma/migrations/20250707145503_add_private_links_expiration_capability/migration.sql:3  ADD COLUMN "maxUsageCount" INTEGER NOT NULL DEFAULT 1,
calcom-q5-f3  packages/lib/hashedLinksUtils.ts:104                                           if (isTimeBasedExpired(linkData.expiresAt, linkData.eventType)) {
calcom-q5-f4  packages/lib/hashedLinksUtils.ts:95                                            return usageCount >= maxUsageCount;
calcom-q5-f5  packages/lib/errorCodes.ts:39                                                  PrivateLinkExpired = "private_link_expired",
calcom-q5-f6  docs/api-reference/v2/openapi.json:26492                                       "description": "Maximum number of times the link can be used. If omitted and expiresAt is not provided, defaults to 1 (one time use)."
calcom-q6-f1  packages/prisma/schema.prisma:223                                              onlyShowFirstAvailableSlot Boolean @default(false)
calcom-q6-f2  packages/prisma/schema.prisma:224                                              showOptimizedSlots Boolean? @default(false)
calcom-q6-f3  packages/prisma/schema.prisma:229                                              seatsShowAttendees Boolean? @default(false)
calcom-q6-f4  packages/prisma/schema.prisma:230                                              seatsShowAvailabilityCount Boolean? @default(true)
calcom-q7-f1  docs/self-hosting/database-migrations.mdx:36                                   Prisma uses a database called `_prisma_migrations`... mismatch... throw the following error:
calcom-q7-f2  docs/self-hosting/database-migrations.mdx:39                                   Error: P3005
calcom-q7-f3  docs/self-hosting/database-migrations.mdx:49                                   yarn prisma migrate resolve --applied migration_name
calcom-q7-f4  docs/self-hosting/database-migrations.mdx:61                                   DELETE FROM "_prisma_migrations";
calcom-q8-f1  packages/features/bookings/lib/handleCancelBooking.ts:200                      message: "This booking has already been cancelled.",
calcom-q8-f2  packages/features/bookings/lib/handleCancelBooking.ts:205                      throw new HttpError({ statusCode: 400, message: "User not found" });
calcom-q8-f3  packages/features/bookings/lib/handleCancelBooking.ts:226                      message: "Cancellation reason is required",
calcom-q8-f4  packages/features/bookings/lib/handleCancelBooking.ts:233                      message: "Cannot cancel a booking that has already ended",
```

All 41: AGREE (statement genuinely supported by its cited line, read cold,
independently re-derived this round rather than trusted from any prior
pass's notes).

## Round 5 — a second, separately-dispatched independent verifier (evidence-only, no author rationale) and this session's must-fix items

A fresh verifier agent re-checked the file with no access to any of the
narrative above — evidence only, blind to authorial intent. Its result,
against the 38-fact file as it stood before this round's two fixes below:
**38 facts, 38 CONFIRMED, 0 WRONG_LINE, 0 UNSUPPORTED.** It also checked, and
confirmed, that the two concurrent write rounds disclosed at the top of this
file left no structural wreckage: no orphaned fact ids, no duplicated
questions, every question's gold facts actually match what its `text` field
asks. Independently re-confirmed by this session: 8 unique question ids, and
(at the time, pre-fix) 38 unique fact ids with 38 unique evidence citations
— the factual layer was sound. Two things were not, and needed fixing
rather than recording:

### Must-fix 1 — `calcom-q5` was mislabelled `GRAPH_HEAVY`: a single grep handed over the answer

`grep -rn CALCOM_TELEMETRY_DISABLED` over the pinned checkout returns exactly
three hits — `README.md:665`, `packages/types/environment.d.ts:3`,
`packages/lib/telemetry.ts:59` — three of the old `q5`'s four facts
verbatim, with the fourth (`telemetry.ts:58`) sitting on the very next line.
The question text itself even named `packages/lib/telemetry.ts`, closing off
the one remaining hop. Independently re-ran that exact grep this round;
confirmed the same three-hit result.

**Resolution**: replaced `q5` outright (relabelling was rejected — AC-5
needs the ~5 GRAPH_HEAVY / 2 NEUTRAL / 1 NEGATIVE_CONTROL split, and moving
`q5` to NEUTRAL would have broken it) with a genuinely graph-shaped
question: `HashedLink`'s usage-based private-link expiration. `grep -rln
maxUsageCount` returns 23 files repo-wide — no single grep collapses the
chain — and the six facts that actually answer the question span five
different files with no shared identifier that a single lookup exposes:
`schema.prisma:1252` (column + default), a dedicated migration
(`20250707145503_add_private_links_expiration_capability/migration.sql:3`),
two separate checks inside `hashedLinksUtils.ts` (`:104` time-based first,
`:95` the usage-based boundary `usageCount >= maxUsageCount`),
`errorCodes.ts:39` (the error code identifier), and the v2 API's
`openapi.json:26492` (independently confirmed to sit inside the
`CreatePrivateLinkInput` schema block, corroborating the same default of 1).
This session found, on re-reading the file after making this exact fix, that
a concurrently-dispatched session had independently converged on the same
`HashedLink`/`maxUsageCount` replacement (see the Round 4 disclosure above)
— re-verified fresh rather than assumed correct on that basis; both the
schema/migration/error-code facts and the doc corroboration check out
against the pinned checkout as read directly.

### Must-fix 2 — `calcom-q6` had an undefinable answer boundary

The old `q6` asked for "seat-display-related boolean fields," which
included `onlyShowFirstAvailableSlot` (`schema.prisma:223`, a slot-display
flag with no seat scoping) while excluding the equally-qualifying
`showOptimizedSlots` (`schema.prisma:224`) — a boundary a grader could draw
either way, making the gold answer indeterminate rather than merely
incomplete.

**Resolution**: `q6` now pins an exact, mechanically-checkable criterion —
every Boolean field in the `seatsPerTimeSlot`→`schedulingType` schema block
whose name does not start with `disable` — naming `disableCancelling` /
`disableRescheduling` (action-disabling, out of scope) and
`minimumRescheduleNotice` (`Int?`, excluded by type) explicitly as
boundary markers rather than leaving them to inference. Independently
re-read `schema.prisma:220-233` this round: exactly four Boolean fields
satisfy that rule — `onlyShowFirstAvailableSlot` (223), `showOptimizedSlots`
(224), `seatsShowAttendees` (229), `seatsShowAvailabilityCount` (230) — all
four are `q6-f1..f4`, nothing missing, nothing wrongly included. No taste
required to grade an answer against this version.

### Point 3 — recorded, no action taken (per the coordinator's instruction not to let raised-once findings quietly disappear)

- **`calcom-q7` (NEUTRAL) behaves like a negative control.** `grep -rn P3005
  docs/` lands on exactly one file (`docs/self-hosting/database-migrations.mdx`,
  confirmed this round), which holds all four of `q7`'s facts. No
  relational traversal is needed to answer it, despite its NEUTRAL label —
  recorded as an observation about question *difficulty*, not a labelling
  defect (NEUTRAL doesn't promise graph traversal the way GRAPH_HEAVY does).
- **`calcom-q1` is weak-but-not-wrong GRAPH_HEAVY.** Its answer is
  reconstructable from two greps (the guard condition and
  `ErrorCode.BookingSeatsFull`, both inside
  `createNewSeat.ts`, confirmed on lines 77 and 80 this round) rather than
  one — genuinely weaker traversal than `q2`-`q5`, but not the same
  single-grep hazard that got `q5` replaced. Left as-is: `q1`'s own
  cross-artifact audit entry already discloses it as "the one question that
  stays in the code+SQL-migration pair... a genuinely simpler, single-hop
  shape."
- **`calcom-q4` has a decoy worth knowing about.** A second, differently-named
  function — `doesBookingRequireConfirmation.ts:20` — holds
  `let requiresConfirmation = eventType?.requiresConfirmation;`, byte-identical
  to `q4-f3`'s cited line (confirmed this round). It's reachable only from
  payment-webhook paths, not booking creation, so `q4`'s own text-scoping
  ("into booking-creation code") still pins the intended answer — recorded
  because an agent that finds the decoy first and doesn't check the
  question's scoping could be misled, not because the gold fact is wrong.
- **`calcom-q2-f4` is incomplete, not incorrect.** It cites
  `sendPayload.ts:340` for the `X-Cal-Signature-256` header; a second
  dispatch site, `handleWebhookScheduledTriggers.ts:64`, sets the identical
  header (confirmed this round: `headers["X-Cal-Signature-256"] =
  createWebhookSignature({ secret: webhook.secret, body: job.payload });`).
  `q2-f4`'s statement makes no exclusivity claim ("is sent... when the
  webhook payload is dispatched," not "is the only place"), so this is
  disclosed as a coverage gap rather than a defect requiring a fix.

### Final state, independently re-confirmed this round

```
Total questions: 8 (repoId=calcom, all present)
Total facts: 41, unique fact ids: 41, unique evidence citations: 41
Duplicate evidence citations: {}
Category distribution: GRAPH_HEAVY=5 (62.5%), NEUTRAL=2 (25.0%), NEGATIVE_CONTROL=1 (12.5%)
  against 60/25/15 target — passes=true
```

`./gradlew :modules:benchmark:test --tests "GoldQuestionSetsTest"` (the
`*GoldQuestionSetsTest*` glob form is unreliable in this shared,
concurrently-built worktree — it repeatedly reported "No tests found" even
immediately after a clean `--rerun-tasks` recompile, while the equivalent
unwildcarded `--tests "GoldQuestionSetsTest"` and the unfiltered
`:modules:benchmark:test` both discover and run it without issue; a Gradle
test-filter quirk with this Kotest engine, not a problem with the gold set)
passed clean: 3 tests, 0 failures, 0 errors — `every real gold question set
loads without error`, `question ids are unique across all four repos`, `all
four repos are present and the merged set passes
CategoryDistributionAuditor`, re-run against the file's current, post-fix
state.
