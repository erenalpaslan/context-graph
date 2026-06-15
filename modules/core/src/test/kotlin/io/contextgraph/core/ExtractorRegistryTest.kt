package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ExtractorRegistryTest : FunSpec({

    fun fakeExtractor(vararg types: NodeType): ResourceExtractor = object : ResourceExtractor {
        override val id = types.first().let { NodeType.stringify(it) }.lowercase()
        override val supportedTypes = types.toSet()
        override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult =
            ExtractionResult(artifact, emptyList(), emptyList())
    }

    test("findExtractors returns matching extractors") {
        val codeExt = fakeExtractor(NodeType.CodeFile)
        val mdExt   = fakeExtractor(NodeType.MarkdownFile)
        val registry = ExtractorRegistry(listOf(codeExt, mdExt))

        registry.findExtractors(NodeType.CodeFile) shouldBe listOf(codeExt)
        registry.findExtractors(NodeType.MarkdownFile) shouldBe listOf(mdExt)
    }

    test("findExtractors returns empty list when no match") {
        val registry = ExtractorRegistry(listOf(fakeExtractor(NodeType.CodeFile)))
        registry.findExtractors(NodeType.PDF).shouldBeEmpty()
    }

    test("findExtractors returns multiple extractors for the same type") {
        val ext1 = fakeExtractor(NodeType.CodeFile)
        val ext2 = fakeExtractor(NodeType.CodeFile)
        val registry = ExtractorRegistry(listOf(ext1, ext2))
        registry.findExtractors(NodeType.CodeFile) shouldHaveSize 2
    }

    test("all returns every registered extractor") {
        val extractors = listOf(fakeExtractor(NodeType.CodeFile), fakeExtractor(NodeType.PDF))
        ExtractorRegistry(extractors).all() shouldBe extractors
    }
})
