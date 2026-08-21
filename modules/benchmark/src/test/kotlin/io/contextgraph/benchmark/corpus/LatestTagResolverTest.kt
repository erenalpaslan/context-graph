package io.contextgraph.benchmark.corpus

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Exercises [LatestTagResolver] against a local git repo standing in for a GitHub remote
 * (no network). `CorpusCatalog`'s literals were produced by running this same resolver by
 * hand against the four real repo URLs — see that file's doc comment for the actual values
 * and the reasoning for pinning them as constants rather than re-resolving on every run.
 */
class LatestTagResolverTest : FunSpec({

    test("picks the highest version tag and its peeled commit SHA, ignoring non-version tags") {
        val dir = Files.createTempDirectory("corpus-tagresolver-")
        try {
            LocalGitFixture.create(dir, tag = "v1.0.0")
            LocalGitFixture.addTag(dir, "v1.1.0", annotated = true)
            val expectedSha = GitOps.revParseHead(dir) // HEAD after addTag is the v1.1.0 commit
            // A non-version ref (e.g. keycloak's real "nightly" tag) must never win.
            GitOps.run(listOf("tag", "nightly"), cwd = dir)

            val resolved = LatestTagResolver.resolveLatest(dir.toString())

            resolved.shouldNotBeNull()
            resolved.tag shouldBe "v1.1.0"
            // The annotated tag's peeled commit, not the tag object's own SHA.
            resolved.sha shouldBe expectedSha
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("returns null when a repo has no version-shaped tags") {
        val dir = Files.createTempDirectory("corpus-tagresolver-notags-")
        try {
            LocalGitFixture.create(dir, tag = "not-a-version")
            LatestTagResolver.resolveLatest(dir.toString()) shouldBe null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("accepts both v-prefixed and bare numeric version tags") {
        val dir = Files.createTempDirectory("corpus-tagresolver-bare-")
        try {
            LocalGitFixture.create(dir, tag = "26.7.1")
            val resolved = LatestTagResolver.resolveLatest(dir.toString())
            resolved.shouldNotBeNull()
            resolved.tag shouldBe "26.7.1"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
