**Exploration: how does the retrieval benchmark score ripgrep against ContextGraph**

Found 6 symbols across 4 files.

**Blast radius — what depends on these (update/verify before editing)**

- `score` (src/main/kotlin/io/contextgraph/benchmark/judge/JudgeScorer.kt:22) — 3 callers; tests: `src/main/kotlin/io/contextgraph/benchmark/judge/KappaValidator.kt`, `src/main/kotlin/io/contextgraph/benchmark/orchestrator/BenchmarkOrchestrator.kt`, `src/test/kotlin/io/contextgraph/benchmark/judge/JudgeScorerTest.kt`
- `score` (src/main/kotlin/io/contextgraph/benchmark/judge/SetScorer.kt:28) — 1 caller; tests: `src/main/kotlin/io/contextgraph/benchmark/orchestrator/BenchmarkOrchestrator.kt`
- `RetrievalRun` (src/main/kotlin/io/contextgraph/benchmark/retrieval/RetrievalRun.kt:20) — 3 callers; tests: `src/main/kotlin/io/contextgraph/benchmark/retrieval/RetrievalBenchmarkRunner.kt`, `src/test/kotlin/io/contextgraph/benchmark/retrieval/RetrievalReportGeneratorTest.kt`, `src/test/kotlin/io/contextgraph/benchmark/retrieval/RetrievalSchemaV2Test.kt`
- `RipgrepQueryOutcome` (src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepBaselineRunner.kt:6) — 1 caller; tests: `src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepBaselineRunner.kt`

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`src/main/kotlin/io/contextgraph/benchmark/retrieval/RetrievalBenchmarkRunner.kt`** — openContextGraphSide(method)

```kotlin
1	package io.contextgraph.benchmark.retrieval
2	
3	import io.contextgraph.benchmark.corpus.IndexIntegrityGate
4	import io.contextgraph.benchmark.model.CorpusRepo
5	import io.contextgraph.benchmark.model.Question
6	import io.contextgraph.core.GraphDb
7	import io.contextgraph.query.QueryEngine
8	import io.contextgraph.storage.SqliteStorageAdapter
9	import kotlinx.datetime.Clock
10	import java.nio.file.Files
11	import java.nio.file.Path
12	
13	/**
14	 * Runs the whole retrieval axis (AC-23..AC-26) over a set of already-prepared corpus repos:
15	 * for every question, scores both sides against the [ExpectedFileSet] its own gold facts derive,
16	 * and returns a [RetrievalRun] with [RetrievalStats.summarize] already folded in.
17	 *
18	 * Deliberately does not prepare, clone, or (re-)index anything -- it only *reads*
19	 * [corpusRoot]`/<repoId>/with` and `/without`, the layout
20	 * [io.contextgraph.benchmark.corpus.CorpusPreparer] already writes. This matters for a corpus
21	 * repo whose WITH copy is still being indexed by a concurrent process: this runner never opens
22	 * that path for writing, so it cannot corrupt or race an in-progress index. It can only ever
23	 * observe one of two honest outcomes -- [IndexIntegrityGate] passes (the index is complete
24	 * enough to answer every gold-fact-cited file) or it throws (still incomplete, or not indexed at
25	 * all yet) -- and the latter is recorded as a per-repo skip of the ContextGraph side only (see
26	 * [RetrievalRunResult.contextGraph]), never as a crash of the whole run and never by silently
27	 * retrying or waiting.
28	 */
29	class RetrievalBenchmarkRunner(
30	    private val corpusRoot: Path,
31	    private val questions: List<Question>,
32	    private val catalog: List<CorpusRepo>,
33	    private val kValues: List<Int> = DEFAULT_K_VALUES,
34	    private val rgPath: String = "rg",
35	    private val progress: (String) -> Unit = {}
36	) {
37	
38	    fun run(): RetrievalRun {
39	        val results = mutableListOf<RetrievalRunResult>()
40	        val skipped = mutableListOf<SkippedRepo>()
41	        val baseline = RipgrepBaselineRunner(rgPath)
42	
43	        for (repo in catalog) {
44	            val repoQuestions = questions.filter { it.repoId == repo.id }
45	            if (repoQuestions.isEmpty()) continue
46	
47	            val withoutDir = corpusRoot.resolve(repo.id).resolve("without")
48	            if (!Files.isDirectory(withoutDir)) {
49	                skipped += SkippedRepo(
50	                    repo.id,
51	                    "WITHOUT working copy not found at $withoutDir -- corpus not prepared for this repo; skipping both sides"
52	                )
53	                continue
54	            }
55	
56	            val withDir = corpusRoot.resolve(repo.id).resolve("with")
57	            val queryEngine = openContextGraphSide(repo.id, withDir, repoQuestions, skipped)
58	
59	            try {
60	                for (question in repoQuestions) {
61	                    progress("${question.id}: scoring ContextGraph and ripgrep")
62	                    results += scoreQuestion(question, withoutDir, queryEngine, baseline)
63	                }
64	            } finally {
65	                queryEngine?.close()
66	            }
67	        }
68	
69	        val summary = RetrievalStats.summarize(results, kValues)
70	        return RetrievalRun(
71	            runId = "retrieval-${Clock.System.now().toEpochMilliseconds()}",
72	            generatedAt = Clock.System.now(),
73	            kValues = kValues,
74	            results = results,
75	            skippedRepos = skipped,
76	            summary = summary
77	        )
78	    }
79	
80	    private fun openContextGraphSide(
81	        repoId: String,
82	        withDir: Path,
83	        repoQuestions: List<Question>,
84	        skipped: MutableList<SkippedRepo>
85	    ): ClosableQueryEngine? {
86	        return try {
87	            IndexIntegrityGate.verify(repoId, withDir, repoQuestions)
88	            val storage = SqliteStorageAdapter(GraphDb.forRead(withDir))
89	            ClosableQueryEngine(storage)
90	        } catch (e: Exception) {
91	            skipped += SkippedRepo(
92	                repoId,
93	                "ContextGraph side skipped (ripgrep side still measured): ${e.message}"
94	            )
95	            null
96	        }
97	    }
98	
99	    private fun scoreQuestion(
100	        question: Question,
101	        withoutDir: Path,
102	        queryEngine: ClosableQueryEngine?,
103	        baseline: RipgrepBaselineRunner
104	    ): RetrievalRunResult {
105	        val expected = ExpectedFileSet.of(question)
106	
107	        val ripgrepOutcome = baseline.rankedFiles(question.text, withoutDir)
108	        val ripgrepSide = scoreSide(ripgrepOutcome.rankedFiles, expected)
109	
110	        val contextGraphSide = queryEngine?.let {
111	            val ranked = ContextGraphRetrievalRunner(it.queryEngine).rankedFiles(question.text)
112	            scoreSide(ranked, expected)
113	        }
114	
115	        return RetrievalRunResult(
116	            questionId = question.id,
117	            repoId = question.repoId,
118	            category = question.category,
119	            expectedFiles = expected.sorted(),
120	            ripgrepQueryTokens = ripgrepOutcome.tokens,
121	            contextGraph = contextGraphSide,
122	            ripgrep = ripgrepSide
123	        )
124	    }
125	
126	    private fun scoreSide(rankedFiles: List<String>, expected: Set<String>): SideResult = SideResult(
127	        rankedFiles = rankedFiles,
128	        precisionAtK = kValues.associateWith { k -> RetrievalMetrics.precisionAtK(rankedFiles, expected, k) },
129	        recallAtK = kValues.associateWith { k -> RetrievalMetrics.recallAtK(rankedFiles, expected, k) },
130	        reciprocalRank = RetrievalMetrics.reciprocalRank(rankedFiles, expected)
131	    )
132	
133	    /** Bundles [QueryEngine] with the [SqliteStorageAdapter] underneath it so both close together. */
134	    private class ClosableQueryEngine(private val storage: SqliteStorageAdapter) {
135	        val queryEngine = QueryEngine(storage)
136	        fun close() = storage.close()
137	    }
138	
139	    companion object {
140	        /**
141	         * k=5 and k=10 (documented in `BENCHMARKS.md`'s retrieval section, not just here): the
142	         * real gold-set data has a median of 3 and a maximum of 5 distinct cited files per
143	         * question across all 33 questions (computed once, by hand, from the real question
144	         * files -- not a guess), so k=5 is the smallest k at which *every* question's recall@k
145	         * can theoretically reach 1.0, and k=10 is a softer, twice-as-generous ceiling that
146	         * tests whether the right files are still findable within roughly "the first page" of
147	         * either side's output once some noise is allowed in.
148	         */
149	        val DEFAULT_K_VALUES = listOf(5, 10)
150	    }
151	}
```

**`src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepBaselineRunner.kt`** — RipgrepQueryOutcome(class), RipgrepBaselineRunner(class), rankedFiles(method)

```kotlin
1	package io.contextgraph.benchmark.retrieval
2	
3	import java.nio.file.Path
4	
5	/** [tokens] is what [RipgrepQueryDeriver] derived from the question text; [rankedFiles] is what `rg` found searching for them, ranked by matching-line count descending (ties broken alphabetically, for a fully deterministic order). */
6	data class RipgrepQueryOutcome(val tokens: List<String>, val rankedFiles: List<String>)
7	
8	/**
9	 * The ripgrep side of the retrieval measurement (AC-25): runs [RipgrepQueryDeriver]'s tokens
10	 * against a repo's **WITHOUT** working copy -- the clean, never-indexed checkout -- exactly as a
11	 * human with a terminal and no ContextGraph would.
12	 *
13	 * A single `rg` invocation per question, not one per token: every token is passed as its own
14	 * `-e` pattern, which `rg` ORs together internally, so one pass over the tree yields one match
15	 * count per file for "matched at least one of these patterns" -- the natural ranking signal a
16	 * human skimming `rg`'s output would use ("this file has more hits, it's probably the one").
17	 * `-F` (fixed-strings, no regex metacharacter surprises from a dot or slash inside a token) and
18	 * `-w` (whole-word match, so a search for `Next` doesn't also match `NextFunc`) keep the search
19	 * literal and precise, matching what a token that came out of [RipgrepQueryDeriver] is meant to
20	 * represent -- an exact symbol/file/constant, not a fuzzy substring. No other flags are passed:
21	 * `rg` respects the checkout's own `.gitignore` and skips hidden/binary files by default, which
22	 * is exactly the behavior a human typing plain `rg` at the repo root would get -- overriding it
23	 * would be baseline-strengthening or -weakening by hand, the thing AC-25 forbids.
24	 */
25	class RipgrepBaselineRunner(private val rgPath: String = "rg") {
26	
27	    fun rankedFiles(questionText: String, repoRoot: Path): RipgrepQueryOutcome {
28	        val tokens = RipgrepQueryDeriver.deriveTokens(questionText)
29	        if (tokens.isEmpty()) return RipgrepQueryOutcome(tokens, emptyList())
30	
31	        val args = listOf("-F", "-w", "--no-messages", "--count") +
32	            tokens.flatMap { listOf("-e", it) } +
33	            listOf(".")
34	
35	        val lines = RipgrepProcess.run(args, cwd = repoRoot, rgPath = rgPath)
36	
37	        val ranked = lines
38	            .mapNotNull(::parseCountLine)
39	            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
40	            .map { it.first }
41	
42	        return RipgrepQueryOutcome(tokens, ranked)
43	    }
44	
45	    /** Parses an `rg --count` output line (`<path>:<count>`) into (path with `./` stripped, count). Paths never contain `:`, so splitting on the last `:` is safe. */
46	    private fun parseCountLine(line: String): Pair<String, Int>? {
47	        val separator = line.lastIndexOf(':')
48	        if (separator < 0) return null
49	        val path = line.substring(0, separator).removePrefix("./")
50	        val count = line.substring(separator + 1).toIntOrNull() ?: return null
51	        return path to count
52	    }
53	}
```

**`src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepProcess.kt`** — RipgrepProcess(class)

```kotlin
1	package io.contextgraph.benchmark.retrieval
2	
3	import java.nio.file.Path
4	import java.util.concurrent.TimeUnit
5	
6	/** A `rg` invocation failed to even start, or exited with a real error (not "no matches"). */
7	class RipgrepExecutionException(message: String) : RuntimeException(message)
8	
9	/**
10	 * Thin wrapper around shelling out to `ripgrep`, the same shape as
11	 * [io.contextgraph.benchmark.corpus.GitOps] for `git`: one place that owns the
12	 * `ProcessBuilder`, working directory, timeout, and exit-code interpretation, rather than every
13	 * caller building its own.
14	 *
15	 * `rg` exits `0` when it finds at least one match and `1` when it runs cleanly but finds none --
16	 * both are success from this wrapper's point of view (an empty result is a real, meaningful
17	 * answer: this question's derived tokens don't appear anywhere in the checkout). Any other exit
18	 * code is a real failure (bad flags, unreadable path, `rg` missing entirely) and throws
19	 * [RipgrepExecutionException] rather than being silently swallowed as "no results" -- collapsing
20	 * those two cases would make a broken `rg` invocation indistinguishable from a true zero-recall
21	 * measurement, which is exactly the kind of quiet corruption AC-24's determinism requirement
22	 * exists to rule out.
23	 */
24	object RipgrepProcess {
25	
26	    private const val TIMEOUT_SECONDS = 60L
27	
28	    /** True if [rgPath] resolves to a runnable `rg` binary at all. Used to gate tests/CLI startup with a clear message rather than a raw process-launch exception. */
29	    fun isAvailable(rgPath: String): Boolean = try {
30	        val process = ProcessBuilder(rgPath, "--version").redirectErrorStream(true).start()
31	        val finished = process.waitFor(5, TimeUnit.SECONDS)
32	        finished && process.exitValue() == 0
33	    } catch (e: Exception) {
34	        false
35	    }
36	
37	    /**
38	     * Runs `rg <args>` in [cwd], returning stdout split into lines (trailing blank lines
39	     * dropped). Throws [RipgrepExecutionException] naming [rgPath], [args], the exit code and
40	     * stderr if the process can't be started, times out, or exits with anything other than `0`
41	     * or `1`.
42	     */
43	    fun run(args: List<String>, cwd: Path, rgPath: String = "rg"): List<String> {
44	        val command = listOf(rgPath) + args
45	        val process = try {
46	            ProcessBuilder(command).directory(cwd.toFile()).start()
47	        } catch (e: Exception) {
48	            throw RipgrepExecutionException(
49	                "Could not start '$rgPath' (looked for it on PATH) -- is ripgrep installed? " +
50	                    "Original error: ${e.message}"
51	            )
52	        }
53	
54	        val stdout = process.inputStream.bufferedReader().readText()
55	        val stderr = process.errorStream.bufferedReader().readText()
56	        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
57	        if (!finished) {
58	            process.destroyForcibly()
59	            throw RipgrepExecutionException("'$rgPath ${args.joinToString(" ")}' timed out after ${TIMEOUT_SECONDS}s")
60	        }
61	
62	        val exitCode = process.exitValue()
63	        if (exitCode != 0 && exitCode != 1) {
64	            throw RipgrepExecutionException(
65	                "'$rgPath ${args.joinToString(" ")}' failed (exit $exitCode): " +
66	                    stderr.trim().ifBlank { stdout.trim() }
67	            )
68	        }
69	
70	        return stdout.lines().filter { it.isNotBlank() }
71	    }
72	}
```

**`src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepQueryDeriver.kt`** — RipgrepQueryDeriver(class)

```kotlin
1	package io.contextgraph.benchmark.retrieval
2	
3	/**
4	 * The **single place** (AC-25) that turns a benchmark question's English sentence into the
5	 * search terms a `ripgrep` baseline is run with. Every caller in this module that needs a
6	 * ripgrep query goes through [deriveTokens] -- there is no second tokenizer anywhere else in
7	 * `io.contextgraph.benchmark.retrieval`.
8	 *
9	 * ## Why this exists, and what it must not do
10	 *
11	 * The spec calls this slice's most important requirement out by name: handing a question's raw
12	 * sentence to `ripgrep` verbatim sabotages the baseline (a multi-word English sentence matches
13	 * nothing literally, or -- worse -- an implementation that ORs every English word together
14	 * matches almost everything, making ripgrep look artificially weak either way) and a version of
15	 * the opposite mistake already happened once in this suite: an early gin question was written in
16	 * a way that was directly grep-able by the exact symbol name the answer needed, despite being
17	 * labeled graph-heavy, and had to be reworded entirely (see the gold-set slices' agreement docs).
18	 * A baseline that is deliberately starved *or* deliberately handed the answer is not a
19	 * measurement -- "baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış sayıdır."
20	 *
21	 * The fairness invariant this function exists to hold: **both sides see exactly the same input**
22	 * ([io.contextgraph.query.QueryEngine.buildContext] is called with the question's raw
23	 * [io.contextgraph.benchmark.model.Question.text]; this function is *also* called with that same
24	 * raw text, nothing more, nothing pre-filtered out on either side, and nothing pulled from the
25	 * gold facts that the question text itself does not already say). Neither side is handed
26	 * anything the other is denied -- not a symbol name lifted from the answer, not a hint about
27	 * which file the answer lives in, nothing beyond what a reader of the question sentence itself
28	 * would notice.
29	 *
30	 * ## The heuristic
31	 *
32	 * A human presented with this question and a terminal would not type the whole sentence into
33	 * `rg`. They would notice the tokens in it that *look like code* -- symbol names, file names,
34	 * constants -- and search for those. This function approximates that noticing mechanically:
35	 *
36	 * 1. **Quoted spans** (`"..."`, `` `...` ``) are extracted verbatim -- if the question quotes a
37	 *    literal string, a human would grep for exactly that string. English apostrophes (`'do
38	 *    not'`, `gin's`) are deliberately *not* treated as quote delimiters: an earlier version of
39	 *    this function paired the first apostrophe in a sentence with the next one anywhere later in
40	 *    the text and captured entire clauses as "quoted" garbage -- caught by inspecting this
41	 *    function's actual output against all 33 real gold questions, not by review alone.
42	 * 2. **Identifier-shaped words** are extracted: a run of letters/digits/`_`/`.`/`/`/`-` that
43	 *    contains at least one of an underscore, a dot, a slash, or an internal capital letter (i.e.
44	 *    anything after the first character is upper-case) -- what distinguishes `ServeHTTP`,
45	 *    `handleHTTPRequest`, `Context.Next`, `gin.go`,
46	 *    `packages/features/bookings/lib/handleCancelBooking.ts` and `GIN_MODE` from plain English
47	 *    words like `How`, `travel`, `Engine` alone, or `route`, none of which survive this filter.
48	 *    Each qualifying token is also split on `.` and `/` (but not `_` -- `GIN_MODE` is one
49	 *    meaningful identifier, not two) into sub-tokens that are re-checked against the same
50	 *    filter, so `Engine.ServeHTTP` yields both the dotted form (in case that exact prose
51	 *    notation appears verbatim somewhere) *and* the standalone `ServeHTTP` a human would
52	 *    actually search for; `packages/.../handleCancelBooking.ts` yields the full path, the
53	 *    filename, and the bare symbol name `handleCancelBooking`. Segments that don't themselves
54	 *    qualify (`Engine`, `packages`, `com` from `cal.com`) are dropped at every level.
55	 * 3. **Bare numeric literals** of two or more digits (`404`, `405`) -- a status code or magic
56	 *    number named directly in a question is exactly the kind of thing a human would grep for,
57	 *    and dropping pure digit runs (identifiers must start with a letter or `_`) would silently
58	 *    weaken the baseline on precisely the negative-control questions where a literal, searchable
59	 *    constant is the whole point (AC-26).
60	 *
61	 * A short list of Latin abbreviations that are dotted but not code (`e.g`, `i.e`, `etc`) is
62	 * excluded explicitly -- found the same way as the apostrophe bug, by running this function
63	 * against the real question set and reading its output.
64	 *
65	 * Tokens shorter than [MIN_TOKEN_LENGTH] are dropped as noise (too short to be a meaningful
66	 * search term on their own). Order is first-appearance order; duplicates are removed.
67	 *
68	 * A question with **no** derivable tokens (a purely conceptual question with no code-shaped
69	 * words in it at all -- three of Excalidraw's five graph-heavy questions are exactly this, e.g.
70	 * "Trace the call chain from a Ctrl+Z keydown in the editor through to History actually popping
71	 * an entry off the undo stack" names no symbol, file, or constant at all) yields an empty list.
72	 * That is not a bug to work around -- it is itself a true, honest measurement: a question a
73	 * human could not even form a `grep` query for is a question `grep` structurally cannot help
74	 * with, and [RipgrepBaselineRunner] reports it as such (zero ranked files, scoring zero on every
75	 * metric) rather than substituting something the question didn't actually say.
76	 */
77	object RipgrepQueryDeriver {
78	
79	    private const val MIN_TOKEN_LENGTH = 3
80	
81	    private val ABBREVIATIONS = setOf("e.g", "i.e", "etc")
82	
83	    private val QUOTED = Regex("[\"`]([^\"`]{2,})[\"`]")
84	    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_./-]*")
85	    private val NUMBER = Regex("(?<![\\w.])\\d{2,}(?![\\w.])")
86	    private val SPLIT_ON = Regex("[./]")
87	
88	    fun deriveTokens(questionText: String): List<String> {
89	        val tokens = LinkedHashSet<String>()
90	
91	        for (match in QUOTED.findAll(questionText)) {
92	            val candidate = match.groupValues[1].trim()
93	            if (candidate.length >= MIN_TOKEN_LENGTH) tokens += candidate
94	        }
95	
96	        for (match in WORD.findAll(questionText)) {
97	            val raw = match.value.trim('.', '/', '-')
98	            addIfCodeShaped(raw, tokens)
99	            if (raw.contains('/')) {
100	                // Path decomposition is two-level, not a single split on every '.'/'/' at once:
101	                // first by '/' into path segments (so a segment like "handleCancelBooking.ts"
102	                // survives whole, one extra candidate beyond its own further-split parts), then
103	                // each segment by '.'. Splitting everything in one pass would skip straight from
104	                // the full path to "handleCancelBooking"/"ts", never trying the filename with
105	                // its extension on its own.
106	                for (segment in raw.split('/')) {
107	                    val trimmedSegment = segment.trim('-')
108	                    addIfCodeShaped(trimmedSegment, tokens)
109	                    if (trimmedSegment.contains('.')) {
110	                        for (part in trimmedSegment.split('.')) {
111	                            addIfCodeShaped(part.trim('-'), tokens)
112	                        }
113	                    }
114	                }
115	            } else if (raw.contains('.')) {
116	                for (part in raw.split(SPLIT_ON)) {
117	                    addIfCodeShaped(part.trim('-'), tokens)
118	                }
119	            }
120	        }
121	
122	        for (match in NUMBER.findAll(questionText)) {
123	            tokens += match.value
124	        }
125	
126	        return tokens.toList()
127	    }
128	
129	    private fun addIfCodeShaped(raw: String, into: MutableSet<String>) {
130	        if (raw.length < MIN_TOKEN_LENGTH) return
131	        if (raw.lowercase() in ABBREVIATIONS) return
132	        val looksCodeShaped = raw.contains('_') ||
133	            raw.contains('.') ||
134	            raw.contains('/') ||
135	            raw.drop(1).any { it.isUpperCase() }
136	        if (looksCodeShaped) into += raw
137	    }
138	}
```


... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)
