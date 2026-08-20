package io.contextgraph.benchmark.proxy

/**
 * The litellm release this module installs, and the Python range it actually supports --
 * verified empirically (task 17, 2026-08-17), not assumed:
 *
 * - PyPI's release metadata for `litellm` shows `1.83.9`'s `requires_python` as `>=3.9,<3.14`,
 *   while the very next release, `1.84.0`, raised the floor to `>=3.10`. `1.83.9` is therefore
 *   the newest release that still covers this project's measured system Python (3.9.6).
 * - `1.83.9` was then actually installed into a real venv on that Python 3.9.6 and run as a live
 *   proxy (`litellm --config ... --port ...`), which answered `/health/liveliness` within a
 *   second and forwarded a real `/v1/chat/completions` request through to Anthropic (returning
 *   Anthropic's own 401 for the deliberately-fake test key used for that check) -- proving this
 *   isn't just a metadata-compatible version, but one that actually starts and serves on 3.9.
 *
 * A version bump here must repeat both checks (PyPI's `requires_python`, and a real install+run)
 * before this constant changes -- see [requireSupported] for what happens when it drifts ahead of
 * a still-3.9 system Python.
 */
object LiteLlmPin {
    const val VERSION = "1.83.9"

    /** Inclusive lower bound: the oldest Python [VERSION] declares support for. */
    val MIN_PYTHON = PythonVersion(3, 9, 0)

    /** Exclusive upper bound: [VERSION] does not support this version or newer. */
    val MAX_PYTHON_EXCLUSIVE = PythonVersion(3, 14, 0)

    fun isSupported(version: PythonVersion): Boolean = version >= MIN_PYTHON && version < MAX_PYTHON_EXCLUSIVE

    /**
     * Throws [UnsupportedPythonVersionException] -- naming the interpreter actually found, and
     * the range [VERSION] requires -- rather than letting the caller silently fall back to an
     * older, unpinned litellm release that happens to install. A wrong-but-quiet judge proxy is
     * worse than a run that refuses to start (task 17's explicit instruction).
     */
    fun requireSupported(version: PythonVersion) {
        if (!isSupported(version)) {
            throw UnsupportedPythonVersionException(
                "Python $version cannot run the pinned litellm==$VERSION (requires " +
                    ">=$MIN_PYTHON,<$MAX_PYTHON_EXCLUSIVE). Install a Python in that range and " +
                    "point the proxy at it explicitly, or verify a newer litellm release still " +
                    "supports Python $version before bumping LiteLlmPin.VERSION -- do not " +
                    "silently fall back to an older, unpinned release."
            )
        }
    }
}
