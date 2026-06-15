package io.contextgraph.extractors

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.NodeType
import io.contextgraph.core.Artifact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.io.path.writeText

class MarkdownExtractorTest : FunSpec({

    val extractor = MarkdownExtractor()
    val config = ContextGraphConfig()

    fun tempMarkdown(content: String): Artifact {
        val file = Files.createTempFile("test-", ".md")
        file.writeText(content)
        val now = Clock.System.now()
        return Artifact(
            id = ArtifactId(file.toAbsolutePath().toString()),
            type = NodeType.MarkdownFile,
            path = file.toAbsolutePath().toString(),
            checksum = "test",
            size = content.length.toLong(),
            lastModified = now,
            indexedAt = now
        )
    }

    fun context(artifact: Artifact) =
        ExtractionContext(java.nio.file.Path.of(artifact.path).parent, config)

    test("supportedTypes contains MarkdownFile and Document") {
        extractor.supportedTypes shouldBe setOf(NodeType.MarkdownFile, NodeType.Document)
    }

    test("always produces a file node") {
        val artifact = tempMarkdown("# Hello")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.type == NodeType.MarkdownFile } shouldBe true
    }

    test("headings become Concept nodes") {
        val artifact = tempMarkdown("# Authentication\n\n## Overview\n\nSome text.")
        val result = extractor.extract(artifact, context(artifact))
        val concepts = result.nodes.filter { it.type == NodeType.Concept }
        concepts.any { it.label == "Authentication" } shouldBe true
        concepts.any { it.label == "Overview" } shouldBe true
    }

    test("headings are connected to file with Contains edges") {
        val artifact = tempMarkdown("# Auth\n\nContent.")
        val result = extractor.extract(artifact, context(artifact))
        result.edges.any { it.type == EdgeType.Contains }.shouldBe(true)
    }

    test("bold text with multiple words becomes a Concept node") {
        val artifact = tempMarkdown("This is **Knowledge Graph** technology.")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.label == "Knowledge Graph" } shouldBe true
    }

    test("single-word bold text is not extracted as concept") {
        val artifact = tempMarkdown("This is **important**.")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.none { it.label == "important" } shouldBe true
    }

    test("external links become Document nodes with References edges") {
        val artifact = tempMarkdown("[See docs](https://example.com/docs)")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.label == "https://example.com/docs" } shouldBe true
        result.edges.any { it.type == EdgeType.References } shouldBe true
    }

    test("anchor-only links are ignored") {
        val artifact = tempMarkdown("[Jump](#section)")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.none { it.label == "#section" } shouldBe true
    }

    test("decision patterns produce Decision nodes") {
        val artifact = tempMarkdown("## Decision: Use SQLite for storage\n\nRationale here.")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.type == NodeType.Decision && it.label == "Use SQLite for storage" } shouldBe true
    }

    test("checklist items become Requirement nodes") {
        val artifact = tempMarkdown("- [ ] Implement auth\n- [x] Add tests")
        val result = extractor.extract(artifact, context(artifact))
        val requirements = result.nodes.filter { it.type == NodeType.Requirement }
        requirements.shouldNotBeEmpty()
    }

    test("RFC keywords produce Requirement nodes") {
        val artifact = tempMarkdown("The system MUST validate all input before processing.")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.type == NodeType.Requirement } shouldBe true
    }

    test("empty document produces only the file node") {
        val artifact = tempMarkdown("")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes shouldBe listOf(result.nodes.first()) // only file node
        result.edges.shouldBeEmpty()
    }
})
