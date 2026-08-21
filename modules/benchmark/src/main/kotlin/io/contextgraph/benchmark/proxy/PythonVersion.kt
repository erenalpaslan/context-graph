package io.contextgraph.benchmark.proxy

/**
 * A parsed `python3 --version` result (e.g. `Python 3.9.6` -> `PythonVersion(3, 9, 6)`), used to
 * check compatibility with the pinned litellm release before any install is attempted (see
 * [LiteLlmPin]).
 */
data class PythonVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PythonVersion> {
    override fun compareTo(other: PythonVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION_PATTERN = Regex("""Python\s+(\d+)\.(\d+)\.(\d+)""")

        /**
         * Parses the real output of `python3 --version` (Python prints this to stdout on modern
         * versions, stderr on some old ones -- callers should check both and hand whichever is
         * non-blank to this function). Throws [IllegalArgumentException] naming the unparseable
         * text rather than returning a default: a python3 that can't even report its own version
         * is exactly the kind of environment this must refuse loudly, not guess about.
         */
        fun parse(output: String): PythonVersion {
            val match = VERSION_PATTERN.find(output)
                ?: throw IllegalArgumentException("Could not parse a Python version out of: '${output.trim()}'")
            val (major, minor, patch) = match.destructured
            return PythonVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
