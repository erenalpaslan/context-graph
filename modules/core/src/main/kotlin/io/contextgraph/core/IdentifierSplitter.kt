package io.contextgraph.core

/**
 * Splits a compound identifier (a symbol name, file name, or similar token) into its
 * component words, so a search index can retrieve the whole identifier from any one of
 * its parts.
 *
 * Handles:
 *  - camelCase / PascalCase: `RungDistribution` -> `Rung`, `Distribution`
 *  - acronym runs: `HTTPServerFactory` -> `HTTP`, `Server`, `Factory` (the boundary falls
 *    before the last letter of the acronym run, since that letter starts the next word)
 *  - snake_case, kebab-case and dotted names: split on any run of non-alphanumeric characters
 *  - digit boundaries: `Base64Encoder` -> `Base`, `64`, `Encoder`
 *
 * A word with no internal boundary is returned unchanged, as the sole element of the
 * result — this function never mangles a simple identifier.
 *
 * This is a pure splitter: it returns components in their original casing and does not
 * decide what a caller does with them (index them, join them, lowercase them, etc).
 */
object IdentifierSplitter {

    // Zero-width boundaries only, so splitting on this regex never drops characters:
    //  1. lower/digit -> upper       (rung|Distribution)
    //  2. acronym run -> next word   (HTTP|Server, i.e. UPPER before UPPER+lower)
    //  3. letter -> digit            (Base|64)
    //  4. digit -> letter            (64|Encoder)
    private val WORD_BOUNDARY = Regex(
        "(?<=[a-z0-9])(?=[A-Z])" +
            "|(?<=[A-Z])(?=[A-Z][a-z])" +
            "|(?<=[A-Za-z])(?=[0-9])" +
            "|(?<=[0-9])(?=[A-Za-z])"
    )

    private val SEPARATORS = Regex("[^A-Za-z0-9]+")

    fun split(identifier: String): List<String> {
        if (identifier.isEmpty()) return emptyList()
        return SEPARATORS.split(identifier)
            .filter { it.isNotEmpty() }
            .flatMap { segment -> WORD_BOUNDARY.split(segment) }
            .filter { it.isNotEmpty() }
    }
}
