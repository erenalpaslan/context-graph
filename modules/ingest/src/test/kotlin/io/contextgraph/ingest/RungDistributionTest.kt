package io.contextgraph.ingest

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.UnresolvedReference
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * Slice 10's other acceptance surface: "record the rung distribution per language ... make
 * it queryable/reportable, not just logged." [RungDistribution] is a pure read over what
 * [ReferenceResolver] already persisted -- the `rung` property on `Calls` edges, and the
 * `language` property [io.contextgraph.extractors.TreeSitterExtractor] stamps on every file
 * node -- so this exercises it against real resolved edges rather than hand-built fixtures.
 */
class RungDistributionTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach {
        val tmpDir = Files.createTempDirectory("rung-distribution-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
    }

    afterEach {
        storage.close()
    }

    fun fileNode(path: String, language: String) =
        GraphNode(NodeId(path), NodeType.CodeFile, path, properties = mapOf("language" to JsonPrimitive(language)))

    fun reference(name: String, referringSymbolId: String, repoRelativePath: String) =
        UnresolvedReference(
            referenceName = name,
            referringSymbolId = NodeId(referringSymbolId),
            repoRelativePath = repoRelativePath,
            artifactId = ArtifactId(repoRelativePath),
            line = 1
        )

    test("groups resolved Calls edges by the source file's language, then by rung") {
        // Kotlin: one local-scope resolution (same type).
        storage.upsertNode(fileNode("src/kt/Widget.kt", "kotlin"))
        storage.upsertNode(GraphNode(NodeId("src/kt/Widget.kt#Widget.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("src/kt/Widget.kt#Widget.helper"), NodeType.Method, "helper"))
        storage.insertUnresolvedReference(reference("helper", "src/kt/Widget.kt#Widget.caller", "src/kt/Widget.kt"))

        // Python: one repo-wide-unique resolution (no local/import/directory signal --
        // recording this honestly, not compensating for it, is the point of this slice).
        storage.upsertNode(fileNode("src/py/caller.py", "python"))
        storage.upsertNode(GraphNode(NodeId("src/py/caller.py#run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("vendor/deep/util.py#doWork"), NodeType.Method, "doWork"))
        storage.insertUnresolvedReference(reference("doWork", "src/py/caller.py#run", "src/py/caller.py"))

        ReferenceResolver(storage).resolveAll()

        val distribution = RungDistribution.compute(storage)

        distribution shouldContainExactly mapOf(
            "kotlin" to mapOf("local_scope" to 1),
            "python" to mapOf("repo_unique_name" to 1)
        )
    }

    test("falls back to unknown when the source file node has no language property") {
        storage.upsertNode(GraphNode(NodeId("src/x/A.kt#A.recurse"), NodeType.Method, "recurse"))
        storage.insertUnresolvedReference(reference("recurse", "src/x/A.kt#A.recurse", "src/x/A.kt"))

        ReferenceResolver(storage).resolveAll()

        RungDistribution.compute(storage) shouldBe mapOf(UNKNOWN_LANGUAGE to mapOf("local_scope" to 1))
    }
})
