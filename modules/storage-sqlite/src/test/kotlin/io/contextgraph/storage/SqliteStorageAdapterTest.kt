package io.contextgraph.storage

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.Artifact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files

class SqliteStorageAdapterTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach {
        val tmpDir = Files.createTempDirectory("contextgraph-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
    }

    afterEach {
        storage.close()
    }

    fun now() = Clock.System.now()

    fun makeNode(id: String, label: String, type: NodeType = NodeType.Class, confidence: Double = 1.0) =
        GraphNode(NodeId(id), type, label, confidence = confidence)

    fun makeEdge(srcId: String, tgtId: String, type: EdgeType = EdgeType.Calls) =
        GraphEdge(EdgeId("e:$srcId:$tgtId"), NodeId(srcId), NodeId(tgtId), type, confidence = 1.0)

    fun makeArtifact(id: String, path: String = "/src/$id.kt", type: NodeType = NodeType.CodeFile) =
        Artifact(
            id = ArtifactId(id),
            type = type,
            path = path,
            checksum = "abc123",
            size = 100L,
            lastModified = now(),
            indexedAt = now()
        )

    context("nodes") {
        test("upsertNode and getNode roundtrip") {
            val node = makeNode("io.example.UserService", "UserService")
            storage.upsertNode(node)
            storage.getNode(node.id).shouldNotBeNull().label shouldBe "UserService"
        }

        test("getNode returns null for unknown ID") {
            storage.getNode(NodeId("nonexistent")).shouldBeNull()
        }

        test("upsertNode updates existing node") {
            storage.upsertNode(makeNode("A", "Alpha", confidence = 0.5))
            storage.upsertNode(makeNode("A", "Alpha Updated", confidence = 0.9))
            storage.getNode(NodeId("A"))!!.label shouldBe "Alpha Updated"
        }

        test("getAllNodes returns all stored nodes") {
            storage.upsertNode(makeNode("A", "Alpha"))
            storage.upsertNode(makeNode("B", "Beta"))
            storage.getAllNodes() shouldHaveSize 2
        }

        test("getAllNodes filters by minConfidence") {
            storage.upsertNode(makeNode("High", "H", confidence = 0.9))
            storage.upsertNode(makeNode("Low",  "L", confidence = 0.3))
            val results = storage.getAllNodes(minConfidence = 0.5)
            results shouldHaveSize 1
            results.single().id shouldBe NodeId("High")
        }

        test("searchNodes performs full-text search") {
            storage.upsertNode(makeNode("auth.Login", "LoginHandler"))
            storage.upsertNode(makeNode("user.Profile", "UserProfile"))
            val results = storage.searchNodes("Login")
            results shouldHaveSize 1
            results.single().id shouldBe NodeId("auth.Login")
        }

        test("searchNodes filters by node type") {
            storage.upsertNode(makeNode("A", "AuthService", NodeType.Class))
            storage.upsertNode(makeNode("B", "authenticate", NodeType.Function))
            val results = storage.searchNodes("auth", types = listOf(NodeType.Function))
            results shouldHaveSize 1
            results.single().id shouldBe NodeId("B")
        }
    }

    context("edges") {
        test("upsertEdge and getEdgesFrom roundtrip") {
            storage.upsertNode(makeNode("A", "Alpha"))
            storage.upsertNode(makeNode("B", "Beta"))
            storage.upsertEdge(makeEdge("A", "B"))
            storage.getEdgesFrom(NodeId("A")) shouldHaveSize 1
        }

        test("getEdgesTo returns incoming edges") {
            storage.upsertNode(makeNode("A", "Alpha"))
            storage.upsertNode(makeNode("B", "Beta"))
            storage.upsertEdge(makeEdge("A", "B"))
            storage.getEdgesTo(NodeId("B")) shouldHaveSize 1
        }

        test("getEdgesFrom returns empty for node with no edges") {
            storage.upsertNode(makeNode("A", "Alpha"))
            storage.getEdgesFrom(NodeId("A")).shouldBeEmpty()
        }

        test("getAllEdges returns all stored edges") {
            storage.upsertNode(makeNode("A", "A"))
            storage.upsertNode(makeNode("B", "B"))
            storage.upsertNode(makeNode("C", "C"))
            storage.upsertEdge(makeEdge("A", "B"))
            storage.upsertEdge(makeEdge("B", "C"))
            storage.getAllEdges() shouldHaveSize 2
        }
    }

    context("artifacts") {
        test("upsertArtifact and getArtifact roundtrip") {
            val artifact = makeArtifact("io.example.Main", "/src/Main.kt")
            storage.upsertArtifact(artifact)
            storage.getArtifact(artifact.id).shouldNotBeNull().path shouldBe "/src/Main.kt"
        }

        test("getArtifact returns null for unknown ID") {
            storage.getArtifact(ArtifactId("missing")).shouldBeNull()
        }

        test("upsertArtifact updates checksum on re-index") {
            val original = makeArtifact("A")
            storage.upsertArtifact(original)
            storage.upsertArtifact(original.copy(checksum = "newchecksum"))
            storage.getArtifact(ArtifactId("A"))!!.checksum shouldBe "newchecksum"
        }

        test("getAllArtifacts returns all stored artifacts") {
            storage.upsertArtifact(makeArtifact("A"))
            storage.upsertArtifact(makeArtifact("B"))
            storage.getAllArtifacts() shouldHaveSize 2
        }
    }

    context("provenance") {
        test("upsertProvenance and getProvenance roundtrip") {
            storage.upsertNode(makeNode("A", "Alpha"))
            val prov = Provenance(
                artifactId = ArtifactId("/src/Alpha.kt"),
                path = "/src/Alpha.kt",
                lineStart = 10,
                extractor = "code",
                extractedAt = now()
            )
            storage.upsertProvenance("A", "node", prov)
            val results = storage.getProvenance("A")
            results shouldHaveSize 1
            results.single().lineStart shouldBe 10
        }
    }

    context("deleteNodesForArtifact") {
        test("removes nodes belonging to a specific artifact") {
            val artifactId = ArtifactId("/src/A.kt")
            val node = GraphNode(NodeId("nodeA"), NodeType.Class, "Alpha")
            val prov = Provenance(artifactId, "/src/A.kt", extractor = "code", extractedAt = now())
            storage.upsertNode(node)
            // IngestPipeline always calls upsertProvenance after upsertNode —
            // that's how nodes become associated with an artifact.
            storage.upsertProvenance(node.id.value, "node", prov)
            storage.deleteNodesForArtifact(artifactId)
            storage.getAllNodes().shouldBeEmpty()
        }
    }

    context("stats") {
        test("getStats reflects inserted data") {
            storage.upsertArtifact(makeArtifact("art1"))
            storage.upsertNode(makeNode("N1", "Node1"))
            storage.upsertNode(makeNode("N2", "Node2"))
            storage.upsertEdge(makeEdge("N1", "N2"))
            val stats = storage.getStats()
            stats.artifactCount shouldBe 1
            stats.nodeCount shouldBe 2
            stats.edgeCount shouldBe 1
        }

        test("empty database has zero counts") {
            val stats = storage.getStats()
            stats.artifactCount shouldBe 0
            stats.nodeCount shouldBe 0
            stats.edgeCount shouldBe 0
        }
    }
})
