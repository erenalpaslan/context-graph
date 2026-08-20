package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo

/**
 * The four corpus repos and their pinned tag/SHA (spec's corpus table; task 02's notes,
 * answering Q4). These literals are the result of running [LatestTagResolver] by hand
 * against each repo's real remote on 2026-08-17 (`git ls-remote --tags --sort=-v:refname`)
 * and are deliberately hard-coded rather than re-resolved on every corpus-prep run:
 * "pinned" only means something if two runs a month apart clone the *same* commit. A repo
 * that ships a new release between two benchmark runs must not silently change what
 * "the corpus" is — bumping these constants (and re-verifying downstream gold facts still
 * cite valid `file:line` locations, per AC-4) is a deliberate, reviewed action, not an
 * automatic side effect of running the benchmark again.
 *
 * | Repo | Role (spec) |
 * |---|---|
 * | excalidraw | pure TS code — comparable to CodeGraph's published numbers |
 * | gin | pure Go code, small |
 * | cal.com | TS + Prisma/SQL + docs — cross-artifact |
 * | keycloak | Java + SQL + docs — cross-artifact |
 */
object CorpusCatalog {

    val DEFAULT: List<CorpusRepo> = listOf(
        CorpusRepo(
            id = "excalidraw",
            name = "Excalidraw",
            url = "https://github.com/excalidraw/excalidraw.git",
            pinnedTag = "v0.18.1",
            pinnedSha = "a2ec2889babf7d2295469c6d90ebe77fae57df84"
        ),
        CorpusRepo(
            id = "gin",
            name = "gin",
            url = "https://github.com/gin-gonic/gin.git",
            pinnedTag = "v1.12.0",
            pinnedSha = "73726dc606796a025971fe451f0aa6f1b9b847f6"
        ),
        CorpusRepo(
            id = "calcom",
            name = "cal.com",
            url = "https://github.com/calcom/cal.com.git",
            pinnedTag = "v6.2.0",
            pinnedSha = "1c193cca8682b33b9866c792186033f7ef886682"
        ),
        CorpusRepo(
            id = "keycloak",
            name = "Keycloak",
            url = "https://github.com/keycloak/keycloak.git",
            // "nightly" also exists as a tag on this repo but isn't a version release;
            // LatestTagResolver's VERSION_TAG pattern excludes it.
            pinnedTag = "26.7.1",
            pinnedSha = "73f08b397f193712b26d317210dce99898129709"
        )
    )
}
