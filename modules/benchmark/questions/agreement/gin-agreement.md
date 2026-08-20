# gin — producer/verifier agreement record

Repo: `gin` (github.com/gin-gonic/gin), pinned SHA `73726dc606796a025971fe451f0aa6f1b9b847f6`,
read only from `.benchmark-corpus/gin/without/`.

## Process note (deviation from the ideal two-agent split)

The task instructions call for a PRODUCER subagent and a separate, independent
VERIFIER subagent that does not see the producer's reasoning. This Developer
agent's toolset for this run does not expose any subagent-spawning tool (no
`Task`/general-purpose-agent tool was available — confirmed via `ToolSearch`
for "spawn subagent", "task agent launch", and "general-purpose agent dispatch
parallel worker"; only `SendMessage` to a named agent-team teammate exists,
and no teammate was available to delegate to). Spawning two literally
separate agent processes was therefore not possible from this seat.

As the closest available approximation, the two roles were run as two
**separate, sequential passes** within this session, with the verifier pass
deliberately not reusing the producer's rationale text — only the bare
(statement, evidence) pairs:

1. **Producer pass**: read the pinned checkout file-by-file (gin.go,
   context.go, routergroup.go, tree.go, logger.go, recovery.go,
   binding/binding.go, binding/json.go, binding/default_validator.go,
   render/html.go, mode.go) and drafted 8 questions with 40 candidate
   (statement, evidence) pairs, each with an explicit reason evidence
   supports statement.
2. **Verifier pass**: for every one of the 40 pairs, re-opened *only* the
   cited `file:line` fresh (via `sed -n '<line>p'` against the pinned
   checkout, not against memory of the producer's reasoning) and compared
   the literal line content against the statement's claim. A statement was
   marked AGREE only if the literal code at that exact line, read cold,
   supports the statement without requiring an inferential leap the code
   doesn't make explicit.
3. A mechanical existence check (Python script) additionally confirmed all
   40 `file:line` references point at files that exist under the pinned
   checkout and lines that exist within those files (no out-of-range or
   missing-file evidence).

This is disclosed here so the human reviewing this gold set knows the
independence guarantee is weaker than two isolated agent processes — it is
"the same author working from bare citations, not from their own prior
reasoning" rather than "a stranger." Flagging this limitation is itself part
of AC-6 for a run where no true second agent was reachable.

## Outcome

**All 40 candidate facts were confirmed (AGREE) by the verifier pass. Zero
facts were disputed. The escalation list is empty — every candidate fact
entered `gin.yaml` unchanged.** (This was the state before the AC-6 isolated
pass below prompted two small corrections; see "AC-6 isolated pass" for the
current, corrected state — 41 facts, one statement tightened.)

## Per-fact record

| Fact ID | Producer claim (statement) | Evidence | Verifier finding | Outcome |
|---|---|---|---|---|
| gin-q1-f1 | ServeHTTP pulls pooled *Context, calls engine.handleHTTPRequest(c) | gin.go:672 | Line reads `engine.handleHTTPRequest(c)` inside ServeHTTP, directly after Context is retrieved from `engine.pool` at gin.go:667 | AGREE |
| gin-q1-f2 | handleHTTPRequest sets c.handlers, calls c.Next() | gin.go:722 | Line reads `c.Next()`, immediately after `c.handlers = value.handlers` (gin.go:720) in the same `if value.handlers != nil` block | AGREE |
| gin-q1-f3 | Context.Next() increments index, invokes c.handlers[c.index](c) in a loop | context.go:192 | Line reads `c.handlers[c.index](c)`, inside the `for c.index < safeInt8(len(c.handlers))` loop starting context.go:190, after `c.index++` at 189 | AGREE |
| gin-q1-f4 | combineHandlers merges group.Handlers with route handlers | routergroup.go:246 | Line reads `copy(mergedHandlers[len(group.Handlers):], handlers)`, the second of two `copy` calls that concatenate `group.Handlers` and `handlers` into `mergedHandlers` | AGREE |
| gin-q1-f5 | RouterGroup.handle passes combined handlers to engine.addRoute | routergroup.go:89 | Line reads `group.engine.addRoute(httpMethod, absolutePath, handlers)`, where `handlers` was reassigned to `group.combineHandlers(handlers)` on the line above (88) | AGREE |
| gin-q2-f1 | Default() attaches Logger()+Recovery() via engine.Use | gin.go:239 | Line reads `engine.Use(Logger(), Recovery())` inside `func Default(...)` | AGREE |
| gin-q2-f2 | Logger() returns LoggerWithConfig(LoggerConfig{}) | logger.go:225 | Line reads `return LoggerWithConfig(LoggerConfig{})` inside `func Logger()` | AGREE |
| gin-q2-f3 | LoggerWithConfig's handler calls c.Next() before logging | logger.go:282 | Line reads `c.Next()` inside the returned closure, before the later `param.TimeStamp = time.Now()` / logging code | AGREE |
| gin-q2-f4 | Recovery() = RecoveryWithWriter(DefaultErrorWriter) | recovery.go:36 | Line reads `return RecoveryWithWriter(DefaultErrorWriter)` inside `func Recovery()` | AGREE (superseded — see "AC-6 isolated pass" section below: split into gin-q2-f4/gin-q2-f5 to keep each fact to one citation) |
| gin-q2-f5 | RecoveryWithWriter, called with no custom recovery function, resolves to CustomRecoveryWithWriter(out, defaultHandleRecovery) | recovery.go:49 | Line reads `return CustomRecoveryWithWriter(out, defaultHandleRecovery)`, the fallthrough return of `RecoveryWithWriter` taken when `len(recovery) == 0`, i.e. exactly the call shape `Recovery()` produces | AGREE — added post-split, verified fresh in the AC-6 isolated pass |
| gin-q2-f6 | CustomRecoveryWithWriter wraps c.Next() in deferred recover() | recovery.go:90 | Line reads `c.Next()`, immediately after the `defer func() { if rec := recover(); ... }()` block (lines 59-89) closes | AGREE (renumbered from gin-q2-f5 to gin-q2-f6 when the split above added a fact) |
| gin-q3-f1 | GET calls group.handle(http.MethodGet, ...) | routergroup.go:117 | Line reads `return group.handle(http.MethodGet, relativePath, handlers)` inside `func (group *RouterGroup) GET(...)` | AGREE |
| gin-q3-f2 | handle calls group.engine.addRoute | routergroup.go:89 | Same line verified above for gin-q1-f5; content supports this statement too | AGREE |
| gin-q3-f3 | Engine.addRoute finds/creates root, calls root.addRoute | gin.go:377 | Line reads `root.addRoute(path, handlers)`, inside `func (engine *Engine) addRoute(...)`, after the root-lookup/creation block (gin.go:371-376) | AGREE |
| gin-q3-f4 | node.addRoute inserts into the radix tree | tree.go:135 | Line reads `func (n *node) addRoute(path string, handlers HandlersChain) {` — the function signature itself | AGREE |
| gin-q3-f5 | Engine.Routes() walks trees via iterate() | gin.go:392 | Line reads `routes = iterate("", tree.method, routes, tree.root)` inside `func (engine *Engine) Routes()` | AGREE |
| gin-q4-f1 | Binding interface declares Bind(*http.Request, any) error | binding/binding.go:34 | Line reads `Bind(*http.Request, any) error` inside `type Binding interface { ... }` | AGREE |
| gin-q4-f2 | jsonBinding.Bind decodes JSON body | binding/json.go:33 | Line reads `func (jsonBinding) Bind(req *http.Request, obj any) error {`, whose body (line 37) calls `decodeJSON(req.Body, obj)` | AGREE |
| gin-q4-f3 | var JSON BindingBody = jsonBinding{} | binding/binding.go:77 | Line reads `JSON          BindingBody = jsonBinding{}` inside the `var (...)` block | AGREE |
| gin-q4-f4 | ShouldBindWith calls b.Bind(c.Request, obj) | context.go:920 | Line reads `return b.Bind(c.Request, obj)` inside `func (c *Context) ShouldBindWith(obj any, b binding.Binding) error` | AGREE |
| gin-q4-f5 | MustBindWith delegates to ShouldBindWith | context.go:811 | Line reads `err := c.ShouldBindWith(obj, b)` inside `func (c *Context) MustBindWith(...)` | AGREE |
| gin-q5-f1 | LoadHTMLGlob calls engine.SetHTMLTemplate(templ) | gin.go:283 | Line reads `engine.SetHTMLTemplate(templ)` inside `func (engine *Engine) LoadHTMLGlob(...)`, non-debug path | AGREE |
| gin-q5-f2 | SetHTMLTemplate assigns engine.HTMLRender = render.HTMLProduction{...} | gin.go:317 | Line reads `engine.HTMLRender = render.HTMLProduction{Template: templ.Funcs(engine.FuncMap)}` inside `func (engine *Engine) SetHTMLTemplate(...)` | AGREE |
| gin-q5-f3 | HTMLProduction.Instance returns HTML{} implementing Render | render/html.go:54 | Line reads `func (r HTMLProduction) Instance(name string, data any) Render {`, body returns `HTML{...}` (line 55-59) | AGREE |
| gin-q5-f4 | Context.HTML calls c.engine.HTMLRender.Instance(name, obj) | context.go:1172 | Line reads `instance := c.engine.HTMLRender.Instance(name, obj)` inside `func (c *Context) HTML(...)` | AGREE |
| gin-q5-f5 | Context.Render invokes r.Render(c.Writer) | context.go:1161 | Line reads `if err := r.Render(c.Writer); err != nil {` inside `func (c *Context) Render(...)`, which `HTML` calls via `c.Render(code, instance)` (context.go:1173) | AGREE |
| gin-q6-f1 | EnvGinMode = "GIN_MODE" | mode.go:17 | Line reads `const EnvGinMode = "GIN_MODE"` | AGREE |
| gin-q6-f2 | DebugMode = "debug" | mode.go:21 | Line reads `DebugMode = "debug"` | AGREE |
| gin-q6-f3 | ReleaseMode = "release" | mode.go:23 | Line reads `ReleaseMode = "release"` | AGREE |
| gin-q6-f4 | TestMode = "test" | mode.go:25 | Line reads `TestMode = "test"` | AGREE |
| gin-q6-f5 | init() reads env, calls SetMode(mode) | mode.go:54 | Line reads `SetMode(mode)`, where `mode := os.Getenv(EnvGinMode)` is the line directly above (53), both inside `func init()` | AGREE |
| gin-q6-f6 | SetMode falls back to TestMode under go test, else DebugMode | mode.go:60 | Line reads `if flag.Lookup("test.v") != nil {`, inside `if value == ""` (line 59), with the else branch (`value = DebugMode`) at lines 62-63 | AGREE |
| gin-q7-f1 | LogFormatterParams carries per-request fields | logger.go:68 | Line reads `type LogFormatterParams struct {`; the struct body (69-91) lists Request, TimeStamp, StatusCode, Latency, ClientIP, Method, Path, ErrorMessage, BodySize, Keys as claimed | AGREE |
| gin-q7-f2 | StatusCodeColor returns green for 2xx | logger.go:101 | Line reads `return green`, directly under `case code >= http.StatusOK && code < http.StatusMultipleChoices:` (line 100) | AGREE |
| gin-q7-f3 | StatusCodeColor returns red as default case | logger.go:107 | Line reads `return red`, directly under `default:` (line 106) | AGREE, statement text tightened post-hoc — see "AC-6 isolated pass" section below: the default arm also catches codes below `http.StatusContinue`, not only "500 and above" |
| gin-q7-f4 | green = "\033[97;42m" | logger.go:26 | Line reads `green   = "\033[97;42m"` inside the color-constants block | AGREE |
| gin-q7-f5 | defaultLogFormatter builds "[GIN]"-prefixed line via fmt.Sprintf | logger.go:185 | Line reads `return fmt.Sprintf("[GIN] %v |%s %3d %s|%s %8v %s| %15s |%s %-7s %s %#v\n%s",` inside `var defaultLogFormatter = func(...)` | AGREE |
| gin-q8-f1 | default404Body = []byte("404 page not found") | gin.go:33 | Line reads `default404Body = []byte("404 page not found")` | AGREE |
| gin-q8-f2 | default405Body = []byte("405 method not allowed") | gin.go:34 | Line reads `default405Body = []byte("405 method not allowed")` | AGREE |
| gin-q8-f3 | handleHTTPRequest calls serveError(c, http.StatusNotFound, default404Body) on no match | gin.go:759 | Line reads `serveError(c, http.StatusNotFound, default404Body)`, the final fallthrough statement of `handleHTTPRequest` | AGREE |
| gin-q8-f4 | serveError writes defaultMessage via c.Writer.Write | gin.go:772 | Line reads `_, err := c.Writer.Write(defaultMessage)`, inside the `if c.writermem.Status() == code` branch of `serveError` | AGREE |

## Escalation list

None. No fact was disputed between the two passes, so there is nothing to
escalate to a human for this repo.

## Mechanical checks (in addition to the semantic verifier pass above)

- `QuestionSetLoader.loadFile(Paths.get("questions/gin.yaml"))` was exercised
  via a throwaway Kotest test (`GinYamlScratchTest.kt`, deleted after the run)
  and returned all 8 questions with **zero validation errors**. Test output:
  `GRAPH_HEAVY=5 NEUTRAL=2 NEGATIVE_CONTROL=1`, `totalFacts=40`.
- A standalone Python script parsed every `evidence:` value out of `gin.yaml`
  (40 entries) and confirmed each cited file exists under the pinned
  `.benchmark-corpus/gin/without/` checkout and each cited line number is
  within that file's line count. Result: all 40 references exist.

## Second pass, same-session limitation (superseded — see below)

A later Developer-agent session picked this task back up mid-run and hit the
same tooling limitation documented above: `ToolSearch` for "spawn subagent
task general-purpose agent dispatch parallel worker" surfaced no
agent-launching tool in this seat either (only `SendMessage` to a named
agent-team teammate, and no teammate assigned to gin verification). Rather
than accept the existing single-pass self-check as sufficient, that session
performed a **second, genuinely cold read** of all 40 `(statement, evidence)`
pairs — extracted as bare pairs, not the annotated table above — via a single
batched `sed -n '<line>p'` pass over the pinned checkout for every citation
not already independently re-derived while drafting overlapping candidate
facts of its own (gin-q1, part of gin-q3/q4, gin-q8 were independently
re-discovered from scratch before this file was found; gin-q2, gin-q3-f5,
gin-q5, gin-q6, gin-q7, gin-q8-f4 were verified cold against this file's
existing claims). Every one of the 40 lines, read fresh, supports its
statement without requiring an inferential leap the code doesn't make
explicit — **0 WRONG_LINE, 0 UNSUPPORTED**, matching the first pass's
verdict exactly. It also independently re-ran the mechanical evidence-range
check (Python, all 40 citations in-range) and the loader scratch test
(`GinGoldSetScratchTest.kt`, deleted after the run): 8 questions, 0 loader
errors, `GRAPH_HEAVY=5 (62.5%) NEUTRAL=2 (25.0%) NEGATIVE_CONTROL=1 (12.5%)`,
`CategoryDistributionAuditor.audit(...).passes == true`.

At the time this was written it was flagged as a standing gap: no isolated
second agent process, blind to this file, had ever reviewed the set. That
gap is now closed — see the section immediately below.

## AC-6 isolated pass (discharges AC-6) and resulting corrections

A genuinely separate agent — one that saw only the corpus path and the flat
`(statement, evidence)` pairs pulled out of `gin.yaml`, never this agreement
file, never the producer's reasoning — independently re-derived and checked
all 40 facts as they stood before this correction round. Verdict: **40
CONFIRMED, 0 WRONG_LINE, 0 UNSUPPORTED.** Rather than trust co-located prose,
it re-walked the call chains itself, explicitly re-opening `gin.go:672`,
`gin.go:722`, `context.go:192`, `routergroup.go:89`, `gin.go:377`,
`context.go:920`, `context.go:811`, `context.go:1172`, `context.go:1161`,
`gin.go:772` among others. This is the AC-6 producer/verifier discharge for
`gin.yaml`: every one of the 41 facts now in the file either passed that
independent confirmation directly, or is a direct split/tightening of a fact
that did, applied for the reasons below and re-checked against the pinned
checkout by this session before being written. No fact in `gin.yaml` rests
on a single agent's unverified say-so.

**[Superseded — see "Round 5 → Correcting this document's earlier claim
about verification coverage" near the end of this file.]** That last sentence
was accurate for the 41 facts as they stood at the end of this pass, but
`gin.yaml` was edited three more times after this pass ran (Rounds 3-5), and
several of those edits were re-verified only by this session's own cold read,
not by a further independent pass. The corrected, current statement of what
has and hasn't had independent verification is in Round 5, not here.

The isolated pass also surfaced three findings, each resolved as follows:

**Finding 1 — `gin-q7-f3` imprecise (fixed).** The cited `logger.go:107`
(`return red` in the `default:` arm of `StatusCodeColor`) is the right line,
but the original statement's parenthetical "(500 and above)" undersold what
the default arm actually catches. Reading `logger.go:97-108` cold: the arms
above `default` are `[100,200)→white`, `[200,300)→green`, `[300,400)→white`,
`[400,500)→yellow`; `default` is therefore everything else, which is codes
**below 100** (`http.StatusContinue`) as well as 500 and above. Fixed the
statement text to name both halves of that range; evidence citation
(`logger.go:107`) is unchanged since the line itself was never in question.

**Finding 2 — `gin-q2-f4` was two claims on one citation (fixed by
splitting).** The original fact bundled "`Recovery()` calls
`RecoveryWithWriter(DefaultErrorWriter)`" (true at `recovery.go:36`, the
cited line) with "...which resolves to `CustomRecoveryWithWriter`" — a claim
`recovery.go:36` alone doesn't carry; that resolution happens one hop later,
inside `RecoveryWithWriter` itself. Re-reading `recovery.go:44-50` cold:
`RecoveryWithWriter` takes a variadic `recovery ...RecoveryFunc`; `Recovery()`
supplies none, so `len(recovery) > 0` is false and the function falls through
to `return CustomRecoveryWithWriter(out, defaultHandleRecovery)` at
`recovery.go:49` — that is the line that actually performs the "resolves to
CustomRecoveryWithWriter" claim, and it is a distinct line from `:36`.
Resolved by splitting into two facts, each carrying only the claim its own
citation supports: `gin-q2-f4` (`recovery.go:36`, unchanged claim) and a new
`gin-q2-f5` (`recovery.go:49`, the resolution claim). The original `gin-q2-f5`
("wraps c.Next() in deferred recover()", `recovery.go:90`) was renumbered to
`gin-q2-f6` so IDs stay unique. `gin-q2` now carries 6 facts, at the ceiling
but not over it.

**Finding 3 — `gin-q6`/`gin-q7` labelled NEUTRAL but grep-favorable (decision:
keep, justified below).** Both questions' facts sit in a single file each
(`mode.go`, `logger.go`) and most are literal constants (`"GIN_MODE"`,
`"debug"`, `"release"`, `"test"`, the ANSI color escapes) that a single grep
finds as easily as ContextGraph does. The spec's definition of NEUTRAL is
"mimari özet — hiçbir tarafa açık avantaj vermeyen sorular" (spec.md AC-5),
and by that definition these two lean toward favoring the grep-only arm
rather than sitting neutral between the two.

Decision: **keep both as NEUTRAL, unchanged**, rather than reshape them into
different architecture-summary questions, for two reasons. First, reshaping
now would mean drafting brand-new candidate facts under this same
single-agent-in-this-seat limitation and re-running the AC-6 discharge for
them from scratch, for a category (NEUTRAL) that AC-5's tolerance already
treats as a soft target (~25%, not a hard gate the way the negative-control
floor is) — the marginal rigor gained is small relative to the risk of
introducing an unverified fact this late in the round. Second, and more
importantly: a NEUTRAL question that in practice leans toward the grep-only
arm makes the benchmark **harder for ContextGraph to look good on**, not
easier — it under-counts rather than inflates ContextGraph's apparent edge,
because the tool gets no credit for its actual advantage (cross-file call-
chain reasoning) on a question grep already answers by inspection. A
conservative bias against the tool being evaluated is the safe direction for
an error to fall in a benchmark whose entire purpose is measuring that tool's
value; a bias inflating its score would not be. This reasoning is recorded
here, not assumed, per the task instruction. The category distribution
itself (5 GRAPH_HEAVY / 2 NEUTRAL / 1 NEGATIVE_CONTROL of 8 questions) is
unaffected by this decision and still matches AC-5's target split.

### Post-correction totals

`gin.yaml` now carries 41 gold facts (was 40; `gin-q2` gained one fact from
the Finding 2 split): q1=5, q2=6, q3=5, q4=5, q5=5, q6=6, q7=5, q8=4.
Category distribution is unchanged: `GRAPH_HEAVY=5 (62.5%) NEUTRAL=2 (25.0%)
NEGATIVE_CONTROL=1 (12.5%)` of 8 questions, still within AC-5's target. See
"Validation" below for the loader/auditor proof run against the corrected
file.

No new escalations arose from this round. The corrections above (one
statement tightened, one fact split into two, one labelling decision
recorded) are the only changes; every other fact stands as previously
confirmed by both the same-session cold read and this isolated pass.

## Round 3 — Lead-dispatched independent verifier (this session)

The Lead spawned a genuinely separate verifier — never shown this file or any
producer reasoning — against the `gin.yaml` state left by the "AC-6 isolated
pass" above (41 facts). Its verdict: **39 SUPPORTED, 1 OVERSTATED, 0
WRONG_LINE, 0 UNSUPPORTED.** It also surfaced structural concerns beyond
per-fact support (question independence, question difficulty, and one
question's answer being mode-dependent) that a per-line check alone doesn't
catch. Per the Lead's instruction and AC-6, every finding is recorded below —
fixed, or left as-is with reasoning — so nothing is silently dropped.

| # | Finding | Disposition | Reasoning / what changed |
|---|---|---|---|
| a | `gin-q2-f4` OVERSTATED: `recovery.go:36` supports only "`Recovery()` calls `RecoveryWithWriter(DefaultErrorWriter)`"; the "...which resolves to `CustomRecoveryWithWriter`" half is at `recovery.go:45-49`, not `:36`. | **Superseded by finding c**, not patched in place. | `gin-q2` (the whole question this fact lived in) was replaced wholesale per finding c below, so this specific fact no longer exists in `gin.yaml`. The underlying lesson — don't let one citation carry two claims from two different lines — was applied when drafting `gin-q2`'s replacement facts and elsewhere (see finding f). |
| b | Duplicate scored hop: `routergroup.go:89` cited by both `gin-q1-f5` and `gin-q3-f2` with near-identical statements, so `gin-q1` and `gin-q3` are not independent measurements on a repo this small. | **Fixed.** | Kept `gin-q3-f2` on `routergroup.go:89` (it is the more natural home for that hop — `gin-q3` is *about* route registration reaching `addRoute`). Replaced `gin-q1-f5` with a new fact on `gin.go:715` (`value := root.getValue(rPath, c.params, c.skippedNodes, unescape)`), which documents a different, equally load-bearing hop in `gin-q1`'s own narrative: the request-time tree lookup that actually populates `c.handlers` from the chain `combineHandlers` built at registration time. Verified mechanically (see "Duplicate-evidence check" below): 0 evidence lines are now cited more than once anywhere in the file. |
| c | `gin-q2` is graph-heavy in name only: every hop lands in the file named after the symbol (`Logger`→logger.go, `Recovery`→recovery.go), so `grep -n "func Logger()\|func Recovery()"` plus two files answers all 5 facts with zero structural reasoning. Lead-proposed replacement: the `Use()`→`rebuild404Handlers`/`rebuild405Handlers`→`combineHandlers`→`engine.allNoRoute`→`serveError`→`c.Next()` chain (why global middleware still runs on a 404). | **Fixed — replaced `gin-q2` entirely**, produced under the same producer/mechanical-verifier discipline as the rest of the set. | New `gin-q2` text: "Why does middleware attached globally via Engine.Use still run on a request that hits the 404 (no matching route) fallback path, and what recomputes the fallback handler chain to include it?" 6 new facts, each citing a distinct line the statement is about (no one-citation-two-claims repeat of finding a): `gin.go:341` (`Use` appends middleware via `engine.RouterGroup.Use`), `gin.go:342` (`Use` calls `rebuild404Handlers`/`rebuild405Handlers`), `gin.go:357` (`rebuild404Handlers` recomputes `allNoRoute` via `engine.combineHandlers`), `routergroup.go:245` (the specific `copy(mergedHandlers, group.Handlers)` line that is *why* global middleware ends up in the merged chain — deliberately not `:246`, which `gin-q1-f4` already uses, to avoid reintroducing finding b), `gin.go:758` (`handleHTTPRequest` sets `c.handlers = engine.allNoRoute` on no match), `context.go:188` (`Next()`, which actually executes that chain). This spans `gin.go`, `routergroup.go`, `context.go` — 3 files, none of them named after a symbol in the question — and is not answerable by grepping `Use`, `rebuild404Handlers`, or any single function name; it requires walking the call chain. |
| d | `gin-q4`'s facts under-specify the question: it asks which concrete types/call sites break if `binding.Binding.Bind` changes, but only named one concrete type (`jsonBinding`) of thirteen, and one call site. A model naming just those two scores identically to one that correctly enumerates all thirteen implementations plus the `MustBindWith`/`Bind*` fan-out. | **Fixed — added both facts requested.** | Replaced the old `gin-q4-f3` (which only documented the `JSON` var's wiring) with a broader fact enumerating **all thirteen** concrete `Binding` implementations declared in the same `var (...)` block (`binding/binding.go:76`): JSON, XML, Form, Query, FormPost, FormMultipart, ProtoBuf, MsgPack, YAML, Header, Plain, TOML, BSON — and explicitly notes `Uri` is the one entry in that block that implements the separate `BindingUri` interface instead (ties into finding f below). Added a new `gin-q4-f6` on `deprecated.go:22` — the exact line the Lead named — covering the shortcut/fan-out concern: the deprecated `Context.BindWith(obj any, b binding.Binding)` is a third generic call site (beyond `MustBindWith`/`ShouldBindWith`) that also funnels into `c.MustBindWith(obj, b)`, alongside the typed shortcuts (`BindJSON`, etc.) mentioned in the same fact's statement. `gin-q4` now has 6 facts (was 5), at the loader's ceiling but not over it. |
| e | `gin-q5` has an arguable answer: in gin's **default** mode (debug), `LoadHTMLGlob` returns early at `gin.go:279-280` with `render.HTMLDebug` and never reaches `SetHTMLTemplate` (`gin-q5-f1`'s claim); `Context.HTML` then resolves via `HTMLDebug.Instance` (`render/html.go:63`), not the `HTMLProduction` path `gin-q5-f1`-`f3` describe. An answer describing the path that actually runs by default would be correct about the codebase but miss those gold facts. | **Fixed.** | Reworded `gin-q5-f1` to scope its claim to "In non-debug (release/test) mode, ..." (evidence unchanged, `gin.go:283` — the line only ever runs in that branch, so the citation was always accurate; only the English overclaimed it as unconditional). Reworded `gin-q5-f3` similarly ("SetHTMLTemplate (only reached in the non-debug branch) ..."). Added a new `gin-q5-f2` on `gin.go:279` documenting the debug-mode branch explicitly (`engine.HTMLRender = render.HTMLDebug{...}`, gin's default). Reworded `gin-q5-f5` (`Context.HTML` calling `Instance`) to say "whichever concrete HTMLRender (production or debug)" instead of implying only one exists — this line's claim was always mode-agnostically true (both `HTMLProduction.Instance` and `HTMLDebug.Instance` satisfy the same `render.HTMLRender` interface `Context.HTML` calls through), so only the wording changed, not the citation. Question text broadened to "...in both debug and non-debug mode...". `gin-q5` now has 6 facts (was 5): an answer describing either branch now matches at least one gold fact set explicitly. |
| f (1/4) | `gin-q6-f5` cites `mode.go:54`, but the claim "`init()` reads `os.Getenv(EnvGinMode)`" is actually the line above, `mode.go:53`; `:54` is `SetMode(mode)`, the *second* half of the original two-clause statement. | **Fixed.** | Narrowed the statement to only the claim `mode.go:53` actually supports: "init() reads the target mode from the GIN_MODE environment variable via os.Getenv(EnvGinMode)." Evidence changed to `mode.go:53`. (Did not add a second fact for the `SetMode(mode)` call, to keep `gin-q6` within the 6-fact ceiling — `gin-q6-f6` already documents `SetMode`'s behavior once called, from `mode.go:60`, so the "SetMode is called from init" link, while now uncited on its own, is a minor loss relative to staying under the cap.) |
| f (2/4) | `gin-q7-f3`'s "(500 and above)" is imprecise — the `default:` arm of `StatusCodeColor` also catches codes below 100. | **Already fixed** in an earlier round (see "AC-6 isolated pass" → Finding 1 above, applied before this Lead message arrived). Current statement text: "...covering status codes not matched by the earlier ranges — that is, codes below http.StatusContinue (100) as well as http.StatusInternalServerError (500) and above." No further change needed; re-confirmed correct in this round. |
| f (3/4) | `gin-q4-f1`'s "every" is not literally true — `uriBinding` satisfies the separate `BindingUri` interface (`Name() string` + `BindUri(...)`), not `Binding`, so it doesn't have a `Bind(*http.Request, any) error` method at all. | **Fixed.** | Reworded to: "...every concrete type implementing the Binding interface (as distinct from the separate BindingUri interface, which uriBinding implements instead) must provide." Evidence citation unchanged (`binding/binding.go:34`, the interface declaration line — always correct, only the surrounding English overclaimed universality). |
| — | Not a defect, for the record: negative control `gin-q8` was judged genuine by the independent verifier — `grep "404 page not found"` returns exactly one non-test hit. | **Kept unchanged**, as instructed. | No action needed. |

### Duplicate-evidence check (proves finding b is resolved)

A Python pass over every `evidence:` value in `gin.yaml` (43 entries after this
round's edits) found **zero** file:line citations repeated anywhere in the
file — including specifically confirming `routergroup.go:89` (kept, `gin-q3-f2`
only) and `routergroup.go:246` (kept, `gin-q1-f4` only) are each cited exactly
once, and the new `routergroup.go:245` (`gin-q2-f4`) and `gin.go:715`
(`gin-q1-f5`) do not collide with any other citation in the file.

### Final per-question fact counts (this round)

| Question | Category | Facts | Files spanned by GRAPH_HEAVY facts |
|---|---|---|---|
| gin-q1 | GRAPH_HEAVY | 5 | gin.go, context.go, routergroup.go (3) |
| gin-q2 | GRAPH_HEAVY | 6 | gin.go, routergroup.go, context.go (3) — **replaced this round, finding c** |
| gin-q3 | GRAPH_HEAVY | 5 | routergroup.go, gin.go, tree.go (3) |
| gin-q4 | GRAPH_HEAVY | 6 | binding/binding.go, binding/json.go, context.go, deprecated.go (4) |
| gin-q5 | GRAPH_HEAVY | 6 | gin.go, render/html.go, context.go (3) |
| gin-q6 | NEUTRAL | 6 | mode.go (1, by design — see "AC-6 isolated pass" → Finding 3) |
| gin-q7 | NEUTRAL | 5 | logger.go (1, by design — same rationale) |
| gin-q8 | NEGATIVE_CONTROL | 4 | gin.go (1, by design — genuine grep-wins case) |

**Total: 43 gold facts across 8 questions** (was 41 before this round; net +2
from finding c's replacement question having one more fact than the one it
replaced, and finding d adding one fact to `gin-q4`, offset by no facts
removed elsewhere). `GRAPH_HEAVY=5 (62.5%) NEUTRAL=2 (25.0%)
NEGATIVE_CONTROL=1 (12.5%)` of 8 questions — unchanged, still within AC-5's
target split.

### Validation (re-run after this round's edits)

- `QuestionSetLoader.loadFile(Paths.get("questions/gin.yaml"))` exercised via
  a throwaway Kotest test (`GinYamlScratchTest2.kt`, deleted immediately
  after — confirmed absent from the tree post-run) —
  **zero validation errors**. Test output: `GRAPH_HEAVY=5 NEUTRAL=2
  NEGATIVE_CONTROL=1`, `totalFacts=43`, with per-question fact counts
  `gin-q1: 5, gin-q2: 6, gin-q3: 5, gin-q4: 6, gin-q5: 6, gin-q6: 6, gin-q7: 5,
  gin-q8: 4` — all within the loader's 3-6 range.
- Standalone Python script re-parsed all 43 `evidence:` values and confirmed
  every cited file exists under the pinned `.benchmark-corpus/gin/without/`
  checkout and every cited line number is within that file's line count:
  **all 43 in range.**
- Separate Python pass confirmed **zero duplicate evidence citations** across
  the whole file (the finding-b fix).

No new escalations from this round beyond what is recorded in the table
above — every finding the Lead's independent verifier raised is either fixed
in `gin.yaml` or has its "kept as-is" reasoning recorded here, per AC-6.

## Round 4 — second independent verifier (this session), one WRONG_LINE + one judgement call

A **second**, independently-run verifier — evidence-only, no author
rationale, never shown this file — re-checked the `gin.yaml` left by Round 3
(43 facts). Verdict: **42 CONFIRMED, 1 WRONG_LINE, 0 UNSUPPORTED.** Both of
its findings are resolved below; nothing was silently dropped, per AC-6.

### Finding 1 (MUST FIX) — `gin-q2-f6` WRONG_LINE, now fixed

**Claim:** `serveError` calls `c.Next()`, and that call's loop over
`c.handlers` is what actually executes the fallback chain. **Original
citation:** `context.go:188`. **Problem:** `context.go:188` is
`func (c *Context) Next() {` — the method *signature*, in a different file
from where the call happens. It supports neither half of the statement on
its own.

Re-opened the pinned checkout cold to confirm before touching anything
(`gin.go:766` and `context.go:185-196`, tag `v1.12.0`, SHA
`73726dc606796a025971fe451f0aa6f1b9b847f6`):

```
gin.go:764   func serveError(c *Context, code int, defaultMessage []byte) {
gin.go:765       c.writermem.status = code
gin.go:766       c.Next()
...
context.go:188   func (c *Context) Next() {
context.go:189       c.index++
context.go:190       for c.index < safeInt8(len(c.handlers)) {
context.go:191           if c.handlers[c.index] != nil {
context.go:192               c.handlers[c.index](c)
context.go:193           }
context.go:194           c.index++
context.go:195       }
context.go:196   }
```

This confirms the finding exactly as reported: the actual call site is
`gin.go:766`; the loop the statement's second clause describes is
`context.go:190-192` (with `context.go:192` — `c.handlers[c.index](c)` —
already the citation `gin-q1-f3` uses for that same loop).

**Fix chosen:** repoint, not split. Repointed `gin-q2-f6`'s evidence to
`gin.go:766` (the true call site) and reworded the statement so its only
independent factual claim is "serveError calls c.Next()" — fully supported
by that single line — while the loop-mechanism clause is now explicitly
attributed to `gin-q1-f3` ("the same Next() whose loop over c.handlers
\[established in gin-q1-f3\] executes the handler chain") rather than
re-asserted as something `gin.go:766` itself proves. Splitting into a second
fact was the alternative the task offered, but `gin-q2` was already at the
loader's 6-fact ceiling (Round 3, finding c) and the loop mechanism is
already an independently-verified gold fact in this same file
(`gin-q1-f3`, `context.go:192`) — adding a fact that would just restate it
under a new id, at a citation that would collide with `gin-q1-f3`'s existing
`context.go:192` and break the file's zero-duplicate-evidence invariant, was
worse than composing across the two already-verified facts. Re-confirmed
post-edit: `gin.go:766` is cited nowhere else in the file (see "Duplicate
evidence check, round 4" below).

### Finding 2 (JUDGEMENT CALL) — `gin-q3` grep-favorable, deepened rather than relabeled

The verifier noted `grep -rn addRoute` over the pinned checkout returns
exactly four non-test hits — `routergroup.go:89`, `gin.go:364`, `gin.go:377`,
`tree.go:135` — and three of `gin-q3`'s five gold facts
(`gin-q3-f2`/`f3`/`f4`) sit on three of those four lines verbatim. Only the
`GET` shortcut (`gin-q3-f1`, `routergroup.go:117` — never mentions `addRoute`
itself) and the `Routes()`/`iterate` fact (`gin-q3-f5`, `gin.go:392` — same)
need any relationship-following; a model that just greps `addRoute` and
reads the four hits gets three of five facts for free.

Confirmed independently before deciding anything (same grep, same pinned
checkout):

```
$ grep -rn addRoute *.go | grep -v _test.go
gin.go:364:func (engine *Engine) addRoute(method, path string, handlers HandlersChain) {
gin.go:377:	root.addRoute(path, handlers)
routergroup.go:89:	group.engine.addRoute(httpMethod, absolutePath, handlers)
tree.go:135:func (n *node) addRoute(path string, handlers HandlersChain) {
```

**Decision: deepen, not relabel** — per the task's stated preference, and
because relabeling `gin-q3` out of GRAPH_HEAVY would drop the set below
AC-5's ~5 GRAPH_HEAVY target (currently exactly 5 of 8; losing one pushes to
4 of 8, outside the "~5" tolerance) without a replacement question drafted
and independently verified to take its place — a larger, riskier edit for a
single-question fix than deepening the existing one.

**What was added:** a sixth fact, `gin-q3-f6` (`gin.go:400`,
`handlerFunc := root.handlers.Last()`), documenting that `iterate()` — the
helper `Engine.Routes()` drives — resolves each route's *reported handler*
from `root.handlers.Last()`, the chain node.addRoute/insertChild populated
at registration time, not from anything `grep addRoute` itself surfaces
(`gin.go:400` contains no literal "addRoute" substring, so this hop is
invisible to that grep and requires actually tracing `Routes()` → `iterate()`
→ `node.handlers` → back to the insertion side of the story `gin-q3-f3`/`f4`
already establish). The question text was broadened to name this explicitly
("...including which handler in each node's stored chain it reports?") so
the deepened scope is visible to whoever answers it, not just implicit in
the gold facts. This brings the grep-trivial:relationship-required ratio
from 3:2 to 3:3 and, more importantly, ties the "read back" half of the
question to the "write" half (`combineHandlers`, established in `gin-q1-f4`
and `gin-q2-f4`) in a way pure `addRoute` grepping cannot reach.
`gin-q3` now carries 6 facts, at the loader's ceiling but not over it.

Verified fresh before writing: `gin.go:400` reads
`handlerFunc := root.handlers.Last()`, inside `iterate()`, immediately
followed by the `RouteInfo{... Handler: nameOfFunction(handlerFunc) ...}`
construction `Engine.Routes()` returns — confirming the statement without
requiring an inferential leap the code doesn't make explicit.

### Calibration note re-checked — `gin-q7`

The Lead's message flagged `gin-q7` (NEUTRAL, all five facts in `logger.go`,
reachable by grepping `LogFormatterParams` and `StatusCodeColor`) as leaning
grep-favorable too, same as `gin-q3` did. Re-read the existing justification
in "AC-6 isolated pass" → Finding 3 above against the current file: it is
still accurate — `gin-q7`'s facts are unchanged this round, still sit in the
single file `logger.go`, and the reasoning for keeping it NEUTRAL rather than
reshaping it (a grep-favorable NEUTRAL question under-counts, not inflates,
ContextGraph's apparent edge — the safe direction for this benchmark's stated
purpose) still holds. Left unchanged, as instructed.

### Duplicate evidence check, round 4

A Python pass over every `evidence:` value in `gin.yaml` (44 entries after
this round's edits — 43 from Round 3, +1 net from adding `gin-q3-f6`; the
`gin-q2-f6` repoint replaced one citation with another, no net change there)
found **zero** repeated `file:line` citations anywhere in the file, including
specifically confirming the two touched this round: `gin.go:766`
(`gin-q2-f6`, new) and `gin.go:400` (`gin-q3-f6`, new) each appear exactly
once, and neither collides with `context.go:192` (`gin-q1-f3`) or any of the
four `addRoute`-grep lines already cited by `gin-q3-f2`/`f3`/`f4`.

### Final per-question fact counts (after round 4)

| Question | Category | Facts |
|---|---|---|
| gin-q1 | GRAPH_HEAVY | 5 |
| gin-q2 | GRAPH_HEAVY | 6 (f6 repointed this round) |
| gin-q3 | GRAPH_HEAVY | 6 (was 5; f6 added this round) |
| gin-q4 | GRAPH_HEAVY | 6 |
| gin-q5 | GRAPH_HEAVY | 6 |
| gin-q6 | NEUTRAL | 6 |
| gin-q7 | NEUTRAL | 5 |
| gin-q8 | NEGATIVE_CONTROL | 4 |

**Total: 44 gold facts across 8 questions** (was 43 before this round; net +1
from `gin-q3-f6`). Category distribution unchanged: `GRAPH_HEAVY=5 (62.5%)
NEUTRAL=2 (25.0%) NEGATIVE_CONTROL=1 (12.5%)` of 8 questions — still within
AC-5's target split.

### Validation (this round)

- Every evidence `file:line` in `gin.yaml` (44 entries) confirmed to point at
  an existing file and an in-range line under
  `.benchmark-corpus/gin/without/` via a standalone Python pass — **all 44 in
  range**.
- Both edited citations (`gin.go:766`, `gin.go:400`) re-opened cold via `sed
  -n` against the pinned checkout and confirmed to literally read
  `c.Next()` and `handlerFunc := root.handlers.Last()` respectively, matching
  their statements.
- Zero duplicate evidence citations across the file (see above).
- `./gradlew :modules:benchmark:test --tests "*GoldQuestionSetsTest*"` run
  after these edits — see the harness task report for this run's exact
  output; the loader/category-audit proof this test provides supersedes the
  scratch-test proofs described in earlier rounds.

No new escalations from this round. Both findings the second independent
verifier raised are resolved above — one by citation fix (repoint), one by
deepening the question — with the reasoning for each recorded here per AC-6.

## Round 5 — Lead follow-up: `gin-q1-f5` still broken, full compound-claim sweep

The Lead sent a follow-up naming the same two defects as Round 4's verifier
(`gin-q2-f6` WRONG_LINE, `gin-q1-f5` compound-claim), plus an explicit
instruction to re-run cold verification over every fact touched since the
independent verifier last saw the file, and to correct this document's claim
about verification coverage. On re-reading the live file at the start of this
round: **`gin-q2-f6` was already fixed** (Round 4 had repointed it to
`gin.go:766` — confirmed still correct below); **`gin-q1-f5` was not** — Round
4 never touched `gin-q1`, only `gin-q2`/`gin-q3`. So Round 4 was a partial
response to (evidently) the same verifier pass this message reports; this
round finishes it and goes further, per the explicit instruction to sweep.

### The two named defects

**`gin-q1-f5` (MUST FIX, confirmed broken).** Evidence `gin.go:715`.
Statement claimed both "looks up ... via `root.getValue(rPath, ...)`" (true at
`:715`) *and* "the resulting `value.handlers` ... becomes `c.handlers`" — a
distinct, load-bearing assignment claim that is only true at `gin.go:720`,
five lines later, inside a separate `if value.handlers != nil {` block. Cold
re-check via `sed -n '713,721p' gin.go` against the pinned checkout:

```
715   value := root.getValue(rPath, c.params, c.skippedNodes, unescape)
716   if value.params != nil {
717       c.Params = *value.params
718   }
719   if value.handlers != nil {
720       c.handlers = value.handlers
721       c.fullPath = value.fullPath
```

Confirms the finding exactly: `:715` supports only the lookup half.

**`gin-q2-f6` (re-confirmed already fixed, no further action).** Evidence is
now `gin.go:766`, matching `c.Next()` inside `serveError` — re-opened cold
this round and it is exactly what Round 4's fix claims. No change made.

### Fix chosen for `gin-q1-f5`: split, not repoint

Unlike `gin-q2-f6` (where the loop-mechanism clause could be fully offloaded
to an *already-existing* fact, `gin-q1-f3`), `gin-q1-f5`'s second clause — the
`c.handlers = value.handlers` assignment — is not asserted anywhere else in
`gin-q1`, and it is load-bearing: it is the one fact in the whole set that
shows the chain `combineHandlers` builds at *registration* time (`gin-q1-f4`)
is the same chain `Next()` walks at *request* time (`gin-q1-f3`) — the
connective tissue the question's own text asks for ("what role does
Context.Next() play in driving that flow?"). Dropping it, rather than citing
it, would leave the question's central claim gold-fact-blind. `gin-q1` had
room under the 6-fact ceiling (was at 5), so this round split it:

- `gin-q1-f5` narrowed to only the lookup: "handleHTTPRequest looks up the
  request path in the method's radix tree via
  `root.getValue(rPath, c.params, c.skippedNodes, unescape)`." Evidence
  unchanged, `gin.go:715` — now fully supported, nothing else claimed.
- New `gin-q1-f6` (`gin.go:720`): "When that lookup finds a match,
  handleHTTPRequest assigns `c.handlers = value.handlers` — the HandlersChain
  `combineHandlers` built at registration time (`gin-q1-f4`) becomes the exact
  chain `Next()` executes at request time." Cross-references `gin-q1-f4`
  (already-verified) rather than re-asserting `combineHandlers`'s behavior.

`gin-q1` now carries 6 facts (was 5), at the loader's ceiling but not over it.

### The broader sweep, and what else it caught

The Lead's instruction was explicit: re-verify every fact touched since the
independent verifier last saw the file (the whole Round 3 `gin-q2` rewrite,
`gin-q1-f5`, `gin-q4-f3`, `gin-q4-f6`, `gin-q5-f2`, "and anything else you
edited"), cold, line by line, judging whether the statement's claim — read in
isolation — is what the cited line actually shows. Applying that test
uniformly (not just to the two named defects) surfaced the same
one-citation-two-different-lines pattern in **11 facts total**, split across
two buckets:

**In explicit scope (facts edited in Round 3, must-fix):**

| Fact | Evidence | Uncited second claim | Fix |
|---|---|---|---|
| `gin-q1-f5` | `gin.go:715` | `c.handlers = value.handlers` is at `:720` | Split into `f5`/`f6` (above) |
| `gin-q2-f2` | `gin.go:342` | "(and `rebuild405Handlers()`)" is at `:343` | Dropped the parenthetical; `gin-q2`'s own question text is about the 404 path specifically, so the 405 sibling call was never load-bearing here |
| `gin-q2-f5` | `gin.go:758` | "before calling `serveError`" — that call is `:759`, uncited | Reworded to attribute the `serveError` call to `gin-q2-f6` (which already covers it, correctly, at `gin.go:766`) instead of re-asserting it |
| `gin-q5-f1` | `gin.go:283` | "parses the template glob" is at `:275` | Dropped the parsing clause; kept only the `SetHTMLTemplate` call, which `:283` fully supports |
| `gin-q5-f2` | `gin.go:279` | "returns early" describes the `return` at `:280` | Reworded to "bypassing the `SetHTMLTemplate` call examined in `gin-q5-f1` entirely" — a cross-reference to an already-verified fact instead of a new uncited claim about `:280` |

**Found opportunistically, outside the Lead's named scope (pre-existing facts
from the original producer pass, already passed one independent-verifier
check as "SUPPORTED"/"AGREE" under what was evidently a looser standard).
Fixed anyway rather than left in a gold set with a known defect, since each
fix is a same-citation prose tightening with no evidence-line risk — but
flagged here explicitly as a scope decision, not silently rolled in:**

| Fact | Evidence | Uncited second claim | Fix |
|---|---|---|---|
| `gin-q1-f1` | `gin.go:672` | "pulls a pooled `*Context`" is at `:667` | Dropped; kept only the `handleHTTPRequest` call, which is the load-bearing claim for the chain narrative |
| `gin-q1-f2` | `gin.go:722` | "sets `c.handlers` to the matched ... chain" is at `:720` | Reworded to attribute that to the new `gin-q1-f6` instead of re-asserting it |
| `gin-q1-f3` | `context.go:192` | "increments `c.index`" is at `:189`, a separate statement before the loop even starts (Go's `for cond {}` here has no increment clause of its own) | Dropped; kept only the loop-body invocation, which `:192` fully supports |
| `gin-q1-f4` | `routergroup.go:246` | "merges the group's own middleware (`group.Handlers`)" is the *other* `copy()` call, at `:245` — already `gin-q2-f4`'s own citation | Reworded to attribute the `group.Handlers` copy to `gin-q2-f4` and describe only what `:246` shows (the route-handlers copy) |
| `gin-q3-f2` | `routergroup.go:89` | "computes the absolute path and merged handlers" describes `:87`/`:88` | Dropped; kept only the `addRoute` call, noting the identifiers (`absolutePath`, `handlers`) are visibly the call's own arguments at `:89` |
| `gin-q3-f3` | `gin.go:377` | "finds or creates the per-method `*node` tree root" describes `:368-376`, several lines earlier | Dropped; kept only the `root.addRoute` call, the load-bearing hop into `tree.go` |

**Considered and deliberately left unchanged**, with the reasoning recorded
so the distinction is on record rather than assumed:

- `gin-q6-f6` (`mode.go:60`, `if flag.Lookup("test.v") != nil {`) makes a
  claim about *both* branches of the if/else that follows (`TestMode` at
  `:61`, `DebugMode` at `:63`). This looked like the same pattern at first
  read. Judgement call: the cited line is the *root of the very if/else the
  statement describes* — reading it in context immediately shows both
  branches belong to one cohesive conditional, unlike the other 11 fixes
  above, which all describe *flat, independent, sequential* statements (two
  separate function calls, two separate `copy()`s, two unrelated assignments)
  where citing one says nothing about the other. This is the same
  block-anchor precedent already established and accepted elsewhere in this
  file for declarative content (`logger.go:68` for `LogFormatterParams`'s
  nine fields spanning `:69-91`; `binding/binding.go:76` for the thirteen-type
  `var (...)` block spanning `:77-90`; `tree.go:135` for a whole multi-line
  function body) — extended here to one control-flow construct read as a
  whole. Left as-is; flagged for the next independent verifier's judgement
  rather than decided unilaterally as "no issue."
- `gin-q2-f1` (`gin.go:341`, "to append the given middleware to the engine's
  own accumulated `Handlers` list") describes the *purpose* of the call it
  cites, not a separate different-line fact about the callee's internals
  (that internal behavior lives in `routergroup.go:66`, a different file
  entirely, never cited by this fact). Judged as an ordinary purpose clause
  ("calls X to do Y"), the same idiom used throughout the file's
  already-verified facts (e.g. `gin-q5-f3`'s "assigns ... to install
  `engine.HTMLRender`"), not a checkable claim about a specific different
  line. Left unchanged.

### Duplicate-evidence check, round 5

A Python pass over every `evidence:` value in `gin.yaml` (45 entries — 44
after Round 4, +1 net from this round's `gin-q1-f5`/`f6` split; no other
fact's evidence line changed, only wording) found **zero** repeated
`file:line` citations anywhere in the file. Specifically confirmed:
`gin.go:715` (`gin-q1-f5`) and `gin.go:720` (`gin-q1-f6`, new) each appear
exactly once and neither collides with any other citation, including the
`gin.go:672`, `:722`, `:283`, `:279`, `:342`, `:758` lines whose *statements*
changed this round but whose evidence lines did not.

### Final per-question fact counts (after round 5)

| Question | Category | Facts |
|---|---|---|
| gin-q1 | GRAPH_HEAVY | 6 (was 5; f5/f6 split this round) |
| gin-q2 | GRAPH_HEAVY | 6 (f2, f5 reworded this round; f6 unchanged from round 4) |
| gin-q3 | GRAPH_HEAVY | 6 (f2, f3 reworded this round; f6 unchanged from round 4) |
| gin-q4 | GRAPH_HEAVY | 6 |
| gin-q5 | GRAPH_HEAVY | 6 (f1, f2 reworded this round) |
| gin-q6 | NEUTRAL | 6 |
| gin-q7 | NEUTRAL | 5 |
| gin-q8 | NEGATIVE_CONTROL | 4 |

**Total: 45 gold facts across 8 questions** (was 44 before this round; net +1
from the `gin-q1-f5`/`f6` split — every other change this round was a
same-citation rewording, not an addition or removal). Category distribution
unchanged: `GRAPH_HEAVY=5 (62.5%) NEUTRAL=2 (25.0%) NEGATIVE_CONTROL=1
(12.5%)` of 8 questions — still within AC-5's target split.

### Validation (this round)

- `QuestionSetLoader.loadFile(Paths.get("questions/gin.yaml"))` exercised via
  a throwaway Kotest test (`GinRound4CheckTest.kt` — misnamed relative to this
  document's round numbering, an artifact of not knowing this round's number
  until the doc was read; deleted immediately after, confirmed absent from
  the tree post-run). First attempt hit a transient Kotlin incremental-cache
  lock conflict (`Storage for [...] is already registered`) — a concurrency
  artifact of sibling slices' builds running against the same shared module
  at the same time, not a defect in this file; retried once with
  `--rerun-tasks` and it passed clean. Output: **zero validation errors**,
  `counts={GRAPH_HEAVY=5, NEUTRAL=2, NEGATIVE_CONTROL=1}`, `totalFacts=45`,
  per-question: `gin-q1: 6, gin-q2: 6, gin-q3: 6, gin-q4: 6, gin-q5: 6,
  gin-q6: 6, gin-q7: 5, gin-q8: 4` — all within the loader's 3-6 range.
- Standalone Python script re-parsed all 45 `evidence:` values and confirmed
  every cited file exists under the pinned `.benchmark-corpus/gin/without/`
  checkout and every line number is in range: **all 45 in range.**
- Zero duplicate evidence citations across the file (see above).

### Correcting this document's earlier claim about verification coverage

The Lead asked this document to state honestly which facts have had an
independent pass and which have only had this session's own cold read,
because the "AC-6 isolated pass" section's claim — "No fact in `gin.yaml`
rests on a single agent's unverified say-so" — is no longer accurate on its
face for facts added or reworded after that pass ran. Corrected statement, as
of the end of Round 5:

- **Had a genuinely independent verifier's pass** (an agent that never saw
  this file or the producer's reasoning): every fact present in `gin.yaml`
  before Round 3 (the original 40, ~equivalently the facts untouched through
  Round 3) — i.e. `gin-q1-f1/f2/f3/f4` (content since reworded, see below),
  `gin-q2-f4`, `gin-q3-f1/f4/f5`, `gin-q4-f1/f2/f4/f5`, `gin-q5-f3/f4/f5/f6`,
  `gin-q6-f1/f2/f3/f4/f6`, all of `gin-q7`, all of `gin-q8` — plus, from the
  Round 3/4 independent-verifier passes specifically, `gin-q2-f6` (Round 4)
  and the `gin-q3-f2/f3/f4` citations themselves (unchanged this round, only
  their surrounding prose was tightened).
- **Reworded after that independent pass, re-verified only by this session's
  own cold read (not yet re-shown to an independent verifier):**
  `gin-q1-f1/f2/f3/f4` (wording tightened, evidence unchanged), all of
  `gin-q1-f5/f6` (`f5` reworded, `f6` new), `gin-q2-f2/f5` (wording
  tightened), `gin-q3-f2/f3` (wording tightened), `gin-q3-f6` (new, Round 4),
  `gin-q4-f3/f6` (Round 3), `gin-q5-f1/f2` (Round 3, reworded again this
  round). This is every fact this document's tables above name as touched in
  Rounds 3-5.
- The Lead states a further independent verifier will run over the file as
  it stands after this round. Until that happens, the facts in the second
  bullet above should be read as "self-verified, not yet independently
  confirmed" — this document no longer claims otherwise.

No new escalations from this round: every finding — the two named, and the
nine caught by extending the same test to the rest of the file — is fixed
above, with the two considered-and-left-unchanged items reasoned through
explicitly rather than silently accepted, per AC-6.
