package io.contextgraph.benchmark.retrieval

/**
 * The **single place** (AC-25) that turns a benchmark question's English sentence into the
 * search terms a `ripgrep` baseline is run with. Every caller in this module that needs a
 * ripgrep query goes through [deriveTokens] -- there is no second tokenizer anywhere else in
 * `io.contextgraph.benchmark.retrieval`.
 *
 * ## Why this exists, and what it must not do
 *
 * The spec calls this slice's most important requirement out by name: handing a question's raw
 * sentence to `ripgrep` verbatim sabotages the baseline (a multi-word English sentence matches
 * nothing literally, or -- worse -- an implementation that ORs every English word together
 * matches almost everything, making ripgrep look artificially weak either way) and a version of
 * the opposite mistake already happened once in this suite: an early gin question was written in
 * a way that was directly grep-able by the exact symbol name the answer needed, despite being
 * labeled graph-heavy, and had to be reworded entirely (see the gold-set slices' agreement docs).
 * A baseline that is deliberately starved *or* deliberately handed the answer is not a
 * measurement -- "baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış sayıdır."
 *
 * The fairness invariant this function exists to hold: **both sides see exactly the same input**
 * ([io.contextgraph.query.QueryEngine.buildContext] is called with the question's raw
 * [io.contextgraph.benchmark.model.Question.text]; this function is *also* called with that same
 * raw text, nothing more, nothing pre-filtered out on either side, and nothing pulled from the
 * gold facts that the question text itself does not already say). Neither side is handed
 * anything the other is denied -- not a symbol name lifted from the answer, not a hint about
 * which file the answer lives in, nothing beyond what a reader of the question sentence itself
 * would notice.
 *
 * ## The heuristic
 *
 * A human presented with this question and a terminal would not type the whole sentence into
 * `rg`. They would notice the tokens in it that *look like code* -- symbol names, file names,
 * constants -- and search for those. This function approximates that noticing mechanically:
 *
 * 1. **Quoted spans** (`"..."`, `` `...` ``) are extracted verbatim -- if the question quotes a
 *    literal string, a human would grep for exactly that string. English apostrophes (`'do
 *    not'`, `gin's`) are deliberately *not* treated as quote delimiters: an earlier version of
 *    this function paired the first apostrophe in a sentence with the next one anywhere later in
 *    the text and captured entire clauses as "quoted" garbage -- caught by inspecting this
 *    function's actual output against all 33 real gold questions, not by review alone.
 * 2. **Identifier-shaped words** are extracted: a run of letters/digits/`_`/`.`/`/`/`-` that
 *    contains at least one of an underscore, a dot, a slash, or an internal capital letter (i.e.
 *    anything after the first character is upper-case) -- what distinguishes `ServeHTTP`,
 *    `handleHTTPRequest`, `Context.Next`, `gin.go`,
 *    `packages/features/bookings/lib/handleCancelBooking.ts` and `GIN_MODE` from plain English
 *    words like `How`, `travel`, `Engine` alone, or `route`, none of which survive this filter.
 *    Each qualifying token is also split on `.` and `/` (but not `_` -- `GIN_MODE` is one
 *    meaningful identifier, not two) into sub-tokens that are re-checked against the same
 *    filter, so `Engine.ServeHTTP` yields both the dotted form (in case that exact prose
 *    notation appears verbatim somewhere) *and* the standalone `ServeHTTP` a human would
 *    actually search for; `packages/.../handleCancelBooking.ts` yields the full path, the
 *    filename, and the bare symbol name `handleCancelBooking`. Segments that don't themselves
 *    qualify (`Engine`, `packages`, `com` from `cal.com`) are dropped at every level.
 * 3. **Bare numeric literals** of two or more digits (`404`, `405`) -- a status code or magic
 *    number named directly in a question is exactly the kind of thing a human would grep for,
 *    and dropping pure digit runs (identifiers must start with a letter or `_`) would silently
 *    weaken the baseline on precisely the negative-control questions where a literal, searchable
 *    constant is the whole point (AC-26).
 *
 * A short list of Latin abbreviations that are dotted but not code (`e.g`, `i.e`, `etc`) is
 * excluded explicitly -- found the same way as the apostrophe bug, by running this function
 * against the real question set and reading its output.
 *
 * Tokens shorter than [MIN_TOKEN_LENGTH] are dropped as noise (too short to be a meaningful
 * search term on their own). Order is first-appearance order; duplicates are removed.
 *
 * A question with **no** derivable tokens (a purely conceptual question with no code-shaped
 * words in it at all -- three of Excalidraw's five graph-heavy questions are exactly this, e.g.
 * "Trace the call chain from a Ctrl+Z keydown in the editor through to History actually popping
 * an entry off the undo stack" names no symbol, file, or constant at all) yields an empty list.
 * That is not a bug to work around -- it is itself a true, honest measurement: a question a
 * human could not even form a `grep` query for is a question `grep` structurally cannot help
 * with, and [RipgrepBaselineRunner] reports it as such (zero ranked files, scoring zero on every
 * metric) rather than substituting something the question didn't actually say.
 */
object RipgrepQueryDeriver {

    private const val MIN_TOKEN_LENGTH = 3

    private val ABBREVIATIONS = setOf("e.g", "i.e", "etc")

    private val QUOTED = Regex("[\"`]([^\"`]{2,})[\"`]")
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_./-]*")
    private val NUMBER = Regex("(?<![\\w.])\\d{2,}(?![\\w.])")
    private val SPLIT_ON = Regex("[./]")

    fun deriveTokens(questionText: String): List<String> {
        val tokens = LinkedHashSet<String>()

        for (match in QUOTED.findAll(questionText)) {
            val candidate = match.groupValues[1].trim()
            if (candidate.length >= MIN_TOKEN_LENGTH) tokens += candidate
        }

        for (match in WORD.findAll(questionText)) {
            val raw = match.value.trim('.', '/', '-')
            addIfCodeShaped(raw, tokens)
            if (raw.contains('/')) {
                // Path decomposition is two-level, not a single split on every '.'/'/' at once:
                // first by '/' into path segments (so a segment like "handleCancelBooking.ts"
                // survives whole, one extra candidate beyond its own further-split parts), then
                // each segment by '.'. Splitting everything in one pass would skip straight from
                // the full path to "handleCancelBooking"/"ts", never trying the filename with
                // its extension on its own.
                for (segment in raw.split('/')) {
                    val trimmedSegment = segment.trim('-')
                    addIfCodeShaped(trimmedSegment, tokens)
                    if (trimmedSegment.contains('.')) {
                        for (part in trimmedSegment.split('.')) {
                            addIfCodeShaped(part.trim('-'), tokens)
                        }
                    }
                }
            } else if (raw.contains('.')) {
                for (part in raw.split(SPLIT_ON)) {
                    addIfCodeShaped(part.trim('-'), tokens)
                }
            }
        }

        for (match in NUMBER.findAll(questionText)) {
            tokens += match.value
        }

        return tokens.toList()
    }

    private fun addIfCodeShaped(raw: String, into: MutableSet<String>) {
        if (raw.length < MIN_TOKEN_LENGTH) return
        if (raw.lowercase() in ABBREVIATIONS) return
        val looksCodeShaped = raw.contains('_') ||
            raw.contains('.') ||
            raw.contains('/') ||
            raw.drop(1).any { it.isUpperCase() }
        if (looksCodeShaped) into += raw
    }
}
