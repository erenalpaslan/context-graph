package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * End-to-end tests of slice 09's two-pass resolution, driven through the real
 * [IngestPipeline] against a real [SqliteStorageAdapter] -- exactly the path `index`
 * production code takes, just with [FakeSymbolExtractor] standing in for a real language
 * extractor (see that class's doc comment for why: no grammar emits call-site references
 * yet, which is outside this slice's file ownership).
 */
class IngestPipelineResolutionTest : FunSpec({

    fun newPipeline(root: Path, storage: SqliteStorageAdapter): IngestPipeline {
        val config = ContextGraphConfig()
        return IngestPipeline(
            discovery = FileDiscovery(config),
            registry = ExtractorRegistry(listOf(FakeSymbolExtractor())),
            checksumTracker = ChecksumTracker(),
            storage = storage,
            context = ExtractionContext(root, config)
        )
    }

    test("AC-10: a call site whose target is declared in another file produces a Calls edge") {
        val root = Files.createTempDirectory("ingest-resolution-ac10")
        root.resolve("A.kt").writeText("DECLARE callerFn\nREFERENCES calleeFn\n")
        root.resolve("B.kt").writeText("DECLARE calleeFn\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            runBlocking { newPipeline(root, storage).index(root) }

            val callerNode = storage.getAllNodes().single { it.label == "callerFn" }
            val calleeNode = storage.getAllNodes().single { it.label == "calleeFn" }
            val edges = storage.getEdgesFrom(callerNode.id)

            edges shouldHaveSize 1
            edges.single().type shouldBe EdgeType.Calls
            edges.single().target shouldBe calleeNode.id
        } finally {
            storage.close()
        }
    }

    test("AC-11: declarations and unresolved references persist after pass 1, and a second run over unchanged source skips pass 1") {
        val root = Files.createTempDirectory("ingest-resolution-ac11")
        root.resolve("A.kt").writeText("DECLARE callerFn\nREFERENCES calleeFn\n")
        root.resolve("B.kt").writeText("DECLARE calleeFn\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            val pipeline = newPipeline(root, storage)
            val first = runBlocking { pipeline.index(root) }
            first.artifactCount shouldBe 2
            first.skipped shouldBe 0

            storage.getAllUnresolvedReferences() shouldHaveSize 1
            storage.getAllNodes().map { it.label } shouldContainExactlyInAnyOrder
                listOf("A.kt", "B.kt", "callerFn", "calleeFn")

            val second = runBlocking { pipeline.index(root) }
            second.artifactCount shouldBe 0
            second.skipped shouldBe 2

            // The symbol table pass 1 already built survives a run that skips every file.
            storage.getAllUnresolvedReferences() shouldHaveSize 1
            storage.getAllNodes().map { it.label } shouldContainExactlyInAnyOrder
                listOf("A.kt", "B.kt", "callerFn", "calleeFn")
        } finally {
            storage.close()
        }
    }

    test("AC-12: editing file B to add a symbol file A references resolves A's edge even though A was skipped") {
        val root = Files.createTempDirectory("ingest-resolution-ac12")
        root.resolve("A.kt").writeText("DECLARE callerFn\nREFERENCES calleeFn\n")
        root.resolve("B.kt").writeText("DECLARE somethingUnrelated\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            val pipeline = newPipeline(root, storage)
            runBlocking { pipeline.index(root) }

            val callerNode = storage.getAllNodes().single { it.label == "callerFn" }
            storage.getEdgesFrom(callerNode.id).shouldBeEmpty()

            // Only B changes. A is untouched on disk, so pass 1 must skip it this run.
            root.resolve("B.kt").writeText("DECLARE somethingUnrelated\nDECLARE calleeFn\n")
            val second = runBlocking { pipeline.index(root) }
            second.skipped shouldBe 1 // A.kt

            val calleeNode = storage.getAllNodes().single { it.label == "calleeFn" }
            val edges = storage.getEdgesFrom(callerNode.id)
            edges shouldHaveSize 1
            edges.single().target shouldBe calleeNode.id
        } finally {
            storage.close()
        }
    }

    test("AC-16: no edge from another artifact is left pointing at a node deleted on reindex") {
        val root = Files.createTempDirectory("ingest-resolution-ac16")
        root.resolve("A.kt").writeText("DECLARE callerFn\nREFERENCES calleeFn\n")
        root.resolve("B.kt").writeText("DECLARE calleeFn\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            val pipeline = newPipeline(root, storage)
            runBlocking { pipeline.index(root) }
            storage.getAllEdges().filter { it.type == EdgeType.Calls } shouldHaveSize 1

            // B is edited to rename its only declaration away -- "calleeFn" no longer exists.
            root.resolve("B.kt").writeText("DECLARE renamedFn\n")
            runBlocking { pipeline.index(root) }

            val liveNodeIds = storage.getAllNodes().map { it.id }.toSet()
            val allEdges = storage.getAllEdges()
            allEdges.forEach { edge ->
                (edge.source in liveNodeIds) shouldBe true
                (edge.target in liveNodeIds) shouldBe true
            }
            // The stale reference to "calleeFn" no longer resolves to anything.
            allEdges.filter { it.type == EdgeType.Calls }.shouldBeEmpty()
        } finally {
            storage.close()
        }
    }

    test("re-running a full index over unchanged source produces an identical graph") {
        val root = Files.createTempDirectory("ingest-resolution-idempotent")
        root.resolve("A.kt").writeText("DECLARE callerFn\nREFERENCES calleeFn\n")
        root.resolve("B.kt").writeText("DECLARE calleeFn\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            val pipeline = newPipeline(root, storage)
            runBlocking { pipeline.index(root) }
            val nodesAfterFirst = storage.getAllNodes().toSet()
            val edgesAfterFirst = storage.getAllEdges().toSet()

            runBlocking { pipeline.index(root) }
            val nodesAfterSecond = storage.getAllNodes().toSet()
            val edgesAfterSecond = storage.getAllEdges().toSet()

            nodesAfterSecond shouldBe nodesAfterFirst
            edgesAfterSecond shouldBe edgesAfterFirst
        } finally {
            storage.close()
        }
    }

    test("artifact identity is repo-relative, not an absolute path baked into this machine") {
        val root = Files.createTempDirectory("ingest-resolution-portability")
        root.resolve("sub").let { Files.createDirectories(it) }
        root.resolve("sub/A.kt").writeText("DECLARE fn\n")

        val storage = SqliteStorageAdapter(root.resolve("graph.db"))
        try {
            val pipeline = newPipeline(root, storage)
            runBlocking { pipeline.index(root) }

            val artifacts = storage.getAllArtifacts()
            artifacts.shouldNotBeEmpty()
            artifacts.forEach { artifact ->
                (artifact.id.value.startsWith("/")) shouldBe false
                (artifact.path.startsWith("/")) shouldBe false
            }
            artifacts.single().path shouldBe "sub/A.kt"
            artifacts.single().id.value shouldBe "sub/A.kt"
        } finally {
            storage.close()
        }
    }
})

