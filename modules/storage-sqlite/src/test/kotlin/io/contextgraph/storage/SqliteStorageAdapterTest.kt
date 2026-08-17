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

        test("does not delete a Calls edge another artifact holds into this artifact's node") {
            // Regression test for the orphaning bug slice 09 fixes: reindexing artifact B
            // (whose node "callee" is the *target* of a Calls edge artifact A's node holds)
            // must not silently drop that edge just because B's own nodes are being
            // replaced. Calls edges are pass 2's (io.contextgraph.ingest.ReferenceResolver)
            // to own -- it wipes and rebuilds the complete set every full index run, which
            // is what actually keeps them consistent; deleteNodesForArtifact must stay out
            // of the way in the meantime rather than destroy a cross-artifact edge that
            // nothing (since A isn't being reprocessed) would ever restore.
            val artifactA = ArtifactId("/src/A.kt")
            val artifactB = ArtifactId("/src/B.kt")
            val caller = GraphNode(NodeId("A#caller"), NodeType.Method, "caller")
            val callee = GraphNode(NodeId("B#callee"), NodeType.Method, "callee")
            storage.upsertNode(caller)
            storage.upsertNode(callee)
            storage.upsertProvenance(caller.id.value, "node", Provenance(artifactA, "/src/A.kt", extractor = "code", extractedAt = now()))
            storage.upsertProvenance(callee.id.value, "node", Provenance(artifactB, "/src/B.kt", extractor = "code", extractedAt = now()))
            storage.upsertEdge(makeEdge(caller.id.value, callee.id.value, EdgeType.Calls))

            // B is reindexed (e.g. an unrelated edit bumped its checksum); A is untouched.
            storage.deleteNodesForArtifact(artifactB)

            storage.getEdgesTo(callee.id).map { it.type } shouldBe listOf(EdgeType.Calls)
        }

        test("still deletes non-Calls edges touching this artifact's nodes") {
            val artifactA = ArtifactId("/src/A.kt")
            val fileNode = GraphNode(NodeId("A#file"), NodeType.CodeFile, "A.kt")
            val member = GraphNode(NodeId("A#member"), NodeType.Method, "member")
            storage.upsertNode(fileNode)
            storage.upsertNode(member)
            storage.upsertProvenance(fileNode.id.value, "node", Provenance(artifactA, "/src/A.kt", extractor = "code", extractedAt = now()))
            storage.upsertProvenance(member.id.value, "node", Provenance(artifactA, "/src/A.kt", extractor = "code", extractedAt = now()))
            storage.upsertEdge(makeEdge(fileNode.id.value, member.id.value, EdgeType.Contains))

            storage.deleteNodesForArtifact(artifactA)

            storage.getEdgesFrom(fileNode.id).shouldBeEmpty()
        }
    }

    context("unresolvedReferences") {
        fun makeReference(name: String, referringSymbolId: String, artifactId: String, path: String) =
            io.contextgraph.core.UnresolvedReference(
                referenceName = name,
                referringSymbolId = NodeId(referringSymbolId),
                repoRelativePath = path,
                artifactId = ArtifactId(artifactId),
                line = 1
            )

        test("insertUnresolvedReference and getAllUnresolvedReferences roundtrip") {
            storage.insertUnresolvedReference(makeReference("calleeFn", "A#caller", "A.kt", "A.kt"))
            val all = storage.getAllUnresolvedReferences()
            all shouldHaveSize 1
            all.single().referenceName shouldBe "calleeFn"
        }

        test("deleteUnresolvedReferencesForArtifact removes only that artifact's rows") {
            storage.insertUnresolvedReference(makeReference("x", "A#caller", "A.kt", "A.kt"))
            storage.insertUnresolvedReference(makeReference("y", "B#caller", "B.kt", "B.kt"))
            storage.deleteUnresolvedReferencesForArtifact(ArtifactId("A.kt"))
            val remaining = storage.getAllUnresolvedReferences()
            remaining shouldHaveSize 1
            remaining.single().referenceName shouldBe "y"
        }
    }

    context("deleteEdgesOfType") {
        test("removes only edges of the given type, leaving others intact") {
            storage.upsertNode(makeNode("N1", "One"))
            storage.upsertNode(makeNode("N2", "Two"))
            storage.upsertEdge(makeEdge("N1", "N2", EdgeType.Calls))
            storage.upsertEdge(makeEdge("N1", "N2", EdgeType.Contains))
            storage.deleteEdgesOfType(EdgeType.Calls)
            val remaining = storage.getAllEdges()
            remaining shouldHaveSize 1
            remaining.single().type shouldBe EdgeType.Contains
        }
    }

    context("findNodesByLabel") {
        test("returns every node with an exact label match, across types") {
            storage.upsertNode(makeNode("N1", "shared", type = NodeType.Method))
            storage.upsertNode(makeNode("N2", "shared", type = NodeType.Class))
            storage.upsertNode(makeNode("N3", "different", type = NodeType.Method))
            storage.findNodesByLabel("shared") shouldHaveSize 2
            storage.findNodesByLabel("nonexistent").shouldBeEmpty()
        }

        test("nodes(label) is backed by an index, since ReferenceResolver calls this once per unresolved reference") {
            val tmpDir = Files.createTempDirectory("contextgraph-test-migration")
            val dbFile = tmpDir.resolve("graph.db")
            // Opening the adapter runs Flyway migrations, including V3 which adds idx_nodes_label.
            SqliteStorageAdapter(dbFile).close()

            java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'nodes' AND sql LIKE '%(label)%'"
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString("name") shouldBe "idx_nodes_label"
                    }
                }
            }
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
