package io.contextgraph.benchmark.corpus

/**
 * Resolves "the latest release tag" for a repo URL (spec Q4: deferred to corpus-prep time,
 * "take each repo's latest release tag at the moment you do the work"). This is the tool
 * used once, by hand, to produce the literal `pinnedTag`/`pinnedSha` values baked into
 * [CorpusCatalog.DEFAULT] — corpus preparation itself never calls this at run time, so a
 * "pinned" SHA can't silently drift to a different commit because a new release shipped
 * between two benchmark runs (see [CorpusCatalog]'s doc comment).
 *
 * Only requires network access to list remote refs (`git ls-remote`), not a clone.
 */
object LatestTagResolver {

    /** Matches release-style version tags (`v1.2.3`, `26.7.1`) and excludes non-version refs like `nightly`. */
    private val VERSION_TAG = Regex("^v?\\d+(\\.\\d+)*$")

    /**
     * The highest version tag for [url] and the commit it points at, or `null` if the repo
     * has no tags matching [VERSION_TAG]. Relies on `git ls-remote --sort=-v:refname` for the
     * version ordering rather than re-implementing semver comparison.
     */
    fun resolveLatest(url: String): ResolvedTag? {
        val tags = GitOps.listTagsBySha(url)
        val (name, sha) = tags.firstOrNull { (name, _) -> VERSION_TAG.matches(name) } ?: return null
        return ResolvedTag(tag = name, sha = sha)
    }
}

data class ResolvedTag(val tag: String, val sha: String)
