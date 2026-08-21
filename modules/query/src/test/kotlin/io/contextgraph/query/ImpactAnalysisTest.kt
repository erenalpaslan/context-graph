package io.contextgraph.query

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files

/**
 * `impactAnalysis` answers "what calls this", and a benchmark on Keycloak showed it answering
 * something else. Asked for the callers of one overloaded builder method it returned a two-hop
 * blast radius ranked by PageRank and cut to 50 -- so it both padded the answer with nodes that
 * call nothing of the sort and dropped real callers, with nothing in the result marking either.
 *
 * The two properties below are what makes the tool usable for that question: every direct caller
 * is present, and a cap is never applied silently.
 *
 * Node ids follow the real declaration-site format (`repoRelativePath#scope.chain`), as in
 * [BlastRadiusTest], so a failure reads the way a real graph's would.
 */
class ImpactAnalysisTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter
    lateinit var engine: QueryEngine

    beforeEach {
        val tmpDir = Files.createTempDirectory("impact-analysis-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
        engine = QueryEngine(storage)
    }

    afterEach {
        storage.close()
    }

    fun node(id: String) = GraphNode(NodeId(id), NodeType.Method, id.substringAfterLast('.'))

    fun callsEdge(from: String, to: String) =
        GraphEdge(EdgeId("$from->$to"), NodeId(from), NodeId(to), EdgeType.Calls, confidence = 0.97)

    test("returns every direct caller, and nothing that only reaches the target through one") {
        val target = "spi/Builder.java#Builder.name(String)"
        storage.upsertNode(node(target))
        // Two direct callers...
        listOf("a/A.java#A.configure()", "b/B.java#B.configure()").forEach {
            storage.upsertNode(node(it))
            storage.upsertEdge(callsEdge(it, target))
        }
        // ...and one node that calls a caller. It depends on the target transitively, but it is
        // not a call site of it, and the old two-hop expansion could not tell the two apart.
        storage.upsertNode(node("c/C.java#C.top()"))
        storage.upsertEdge(callsEdge("c/C.java#C.top()", "a/A.java#A.configure()"))

        val bundle = engine.impactAnalysis(target)

        bundle.nodes.map { it.id.value } shouldContainExactlyInAnyOrder
            listOf("a/A.java#A.configure()", "b/B.java#B.configure()")
        bundle.totalNodeCount shouldBe 2
    }

    test("a capped answer reports the full count, so a fragment cannot read as complete") {
        val target = "spi/Builder.java#Builder.name(String)"
        storage.upsertNode(node(target))
        repeat(70) { i ->
            val caller = "pkg$i/Caller$i.java#Caller$i.configure()"
            storage.upsertNode(node(caller))
            storage.upsertEdge(callsEdge(caller, target))
        }

        val bundle = engine.impactAnalysis(target, limit = 50)

        // The list is cut, but the count that a renderer reports is not -- 50 of 70, never "50".
        bundle.nodes shouldHaveSize 50
        bundle.totalNodeCount shouldBe 70
    }

    test("every returned caller carries provenance, so the answer is followable to a file and line") {
        val target = "spi/Builder.java#Builder.name(String)"
        storage.upsertNode(node(target))
        val caller = "a/A.java#A.configure()"
        storage.upsertNode(node(caller))
        storage.upsertEdge(callsEdge(caller, target))
        storage.upsertProvenance(
            entityId = caller,
            entityKind = "node",
            provenance = Provenance(
                artifactId = ArtifactId("a/A.java"),
                path = "a/A.java",
                lineStart = 12,
                lineEnd = 30,
                extractor = "tree-sitter",
                extractedAt = Clock.System.now()
            )
        )

        val bundle = engine.impactAnalysis(target)

        // Without this the caller is a name with no location, which is what sent the benchmarked
        // agent back to grep even though the graph knew exactly where the call was.
        bundle.evidence.map { it.path } shouldContainExactlyInAnyOrder listOf("a/A.java")
        bundle.evidence.single().lineStart shouldBe 12
    }
})
