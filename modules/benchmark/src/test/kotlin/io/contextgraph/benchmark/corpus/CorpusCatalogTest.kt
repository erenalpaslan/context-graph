package io.contextgraph.benchmark.corpus

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotBeBlank

/** Sanity checks on the four hard-coded, pinned corpus repos (spec's corpus table). */
class CorpusCatalogTest : FunSpec({

    test("has exactly the four repos the spec names, each with a non-blank pinned tag and 40-char hex SHA") {
        CorpusCatalog.DEFAULT shouldHaveSize 4
        CorpusCatalog.DEFAULT.map { it.id } shouldContainExactlyInAnyOrder
            listOf("excalidraw", "gin", "calcom", "keycloak")

        CorpusCatalog.DEFAULT.forEach { repo ->
            repo.url.shouldNotBeBlank()
            repo.pinnedTag.shouldNotBeBlank()
            repo.pinnedSha shouldMatch Regex("^[0-9a-f]{40}$")
            repo.workingCopyWithPath.shouldBeNull()
            repo.workingCopyWithoutPath.shouldBeNull()
        }
    }
})
