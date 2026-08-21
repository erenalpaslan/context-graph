package io.contextgraph.ingest

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.UnresolvedReference
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * Unit-level tests for pass 2 in isolation: given declarations and unresolved references
 * already sitting in storage (as pass 1 would leave them), does resolveAll() produce exactly
 * the Calls edges it should? Exercises SqliteStorageAdapter directly rather than a fake --
 * findNodesByLabel/deleteEdgesOfType/getEdgesFrom are real SQL, and this is the seam
 * ReferenceResolver's correctness actually depends on.
 *
 * Node ids below follow the real declaration-site format
 * (`repoRelativePath#scope.chain`, see `io.contextgraph.treesitter.DeclarationSiteId`) with
 * real directory structure, because slice 10's ladder (see [ResolutionLadder]) reads rungs
 * directly out of that path/scope structure -- a flat "A"/"B" id would collapse every rung
 * into "same directory" (both are directory-less root paths) and hide what's actually being
 * tested.
 */
class ReferenceResolverTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach {
        val tmpDir = Files.createTempDirectory("reference-resolver-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
    }

    afterEach {
        storage.close()
    }

    fun reference(name: String, referringSymbolId: String, repoRelativePath: String, artifactId: String = "art") =
        UnresolvedReference(
            referenceName = name,
            referringSymbolId = NodeId(referringSymbolId),
            repoRelativePath = repoRelativePath,
            artifactId = ArtifactId(artifactId),
            line = 1
        )

    test("a reference with exactly one repo-wide candidate, no local/import/directory signal, resolves at the weakest rung") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("vendor/lib/B.kt#B.callee"), NodeType.Method, "callee"))
        storage.insertUnresolvedReference(reference("callee", "src/caller/A.kt#A.caller", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        stats.unresolved shouldBe 0
        val edges = storage.getEdgesFrom(NodeId("src/caller/A.kt#A.caller"))
        edges shouldHaveSize 1
        val edge = edges.single()
        edge.target shouldBe NodeId("vendor/lib/B.kt#B.callee")
        edge.type shouldBe EdgeType.Calls
        edge.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME
        (edge.properties["rung"] as JsonPrimitive).content shouldBe "repo_unique_name"
    }

    test("a reference with no matching declaration produces no edge") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.caller"), NodeType.Method, "caller"))
        storage.insertUnresolvedReference(reference("neverDeclared", "src/caller/A.kt#A.caller", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 0
        stats.unresolved shouldBe 1
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.caller")).shouldBeEmpty()
    }

    test("a same-type call resolves via local scope even though the same name is repo-wide ambiguous") {
        // This is exactly the case slice 09's naive full-rebuild left unresolved: "helper" is
        // declared twice repo-wide, but only one of them is reachable from Widget's own body.
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.helper"), NodeType.Method, "helper"))
        storage.upsertNode(GraphNode(NodeId("other/Other.kt#Other.helper"), NodeType.Method, "helper"))
        storage.insertUnresolvedReference(reference("helper", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edges = storage.getEdgesFrom(NodeId("src/pkg/Widget.kt#Widget.caller"))
        edges shouldHaveSize 1
        val edge = edges.single()
        edge.target shouldBe NodeId("src/pkg/Widget.kt#Widget.helper")
        edge.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_LOCAL_SCOPE
        (edge.properties["rung"] as JsonPrimitive).content shouldBe "local_scope"
    }

    test("a call resolves via a file import when the candidate's file basename matches an imported token") {
        val fileId = NodeId("src/pkg/A.kt")
        storage.upsertNode(GraphNode(fileId, NodeType.CodeFile, "A.kt"))
        val importId = NodeId("src/pkg/A.kt#import:Logger")
        storage.upsertNode(GraphNode(importId, NodeType.Module, "pkg.util.Logger"))
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("imports:${fileId.value}:${importId.value}"),
                source = fileId,
                target = importId,
                type = EdgeType.Imports
            )
        )
        storage.upsertNode(GraphNode(NodeId("src/pkg/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("src/other/Logger.kt#Logger.log"), NodeType.Method, "log"))
        storage.insertUnresolvedReference(reference("log", "src/pkg/A.kt#A.run", "src/pkg/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edge = storage.getEdgesFrom(NodeId("src/pkg/A.kt#A.run")).single()
        edge.target shouldBe NodeId("src/other/Logger.kt#Logger.log")
        edge.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_FILE_IMPORTS
        (edge.properties["rung"] as JsonPrimitive).content shouldBe "file_imports"
    }

    test("a call resolves via same directory when no local scope or import signal applies") {
        storage.upsertNode(GraphNode(NodeId("src/pkg/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/B.kt#B.helper"), NodeType.Method, "helper"))
        storage.insertUnresolvedReference(reference("helper", "src/pkg/A.kt#A.run", "src/pkg/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edge = storage.getEdgesFrom(NodeId("src/pkg/A.kt#A.run")).single()
        edge.target shouldBe NodeId("src/pkg/B.kt#B.helper")
        edge.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY
        (edge.properties["rung"] as JsonPrimitive).content shouldBe "same_directory"
    }

    test("AC-13: higher rungs carry strictly higher confidence than lower ones") {
        // Four independent references, each engineered to hit exactly one rung.
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.localTarget"), NodeType.Method, "localTarget"))

        val fileId = NodeId("src/pkg/Widget.kt")
        storage.upsertNode(GraphNode(fileId, NodeType.CodeFile, "Widget.kt"))
        val importId = NodeId("src/pkg/Widget.kt#import:Helper")
        storage.upsertNode(GraphNode(importId, NodeType.Module, "Helper"))
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("imports:${fileId.value}:${importId.value}"),
                source = fileId,
                target = importId,
                type = EdgeType.Imports
            )
        )
        storage.upsertNode(GraphNode(NodeId("src/other/Helper.kt#Helper.importTarget"), NodeType.Method, "importTarget"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/Sibling.kt#Sibling.dirTarget"), NodeType.Method, "dirTarget"))
        storage.upsertNode(GraphNode(NodeId("vendor/deep/Util.kt#Util.repoTarget"), NodeType.Method, "repoTarget"))

        storage.insertUnresolvedReference(reference("localTarget", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))
        storage.insertUnresolvedReference(reference("importTarget", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))
        storage.insertUnresolvedReference(reference("dirTarget", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))
        storage.insertUnresolvedReference(reference("repoTarget", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))

        ReferenceResolver(storage).resolveAll()

        val edges = storage.getEdgesFrom(NodeId("src/pkg/Widget.kt#Widget.caller"))
            .associateBy { (it.properties["rung"] as JsonPrimitive).content }
        edges.keys shouldContainExactlyInAnyOrder
            listOf("local_scope", "file_imports", "same_directory", "repo_unique_name")

        val local = edges.getValue("local_scope").confidence
        val imports = edges.getValue("file_imports").confidence
        val directory = edges.getValue("same_directory").confidence
        val repo = edges.getValue("repo_unique_name").confidence

        (local > imports) shouldBe true
        (imports > directory) shouldBe true
        (directory > repo) shouldBe true
    }

    test("AC-14: an ambiguous name with more candidates than the cap is left unresolved") {
        // Four repo-wide candidates for "process", none sharing local scope, imports, or a
        // directory with the caller -- one more than CALL_RESOLUTION_CANDIDATE_CAP.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        listOf("m1", "m2", "m3", "m4").forEach { dir ->
            storage.upsertNode(GraphNode(NodeId("vendor/$dir/Impl.kt#Impl.process"), NodeType.Method, "process"))
        }
        storage.insertUnresolvedReference(reference("process", "src/caller/A.kt#A.run", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 0
        stats.unresolved shouldBe 1
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).shouldBeEmpty()
    }

    test("fields sharing the call's name are not candidates, and do not count toward the cap") {
        // The Keycloak shape: `.name(...)` on a config builder, where three of the four same-named
        // declarations in scope are fields. Counting them pushed a single unambiguous call over
        // CALL_RESOLUTION_CANDIDATE_CAP, so it resolved to nothing -- while `.type(...)` on the
        // next line, one field fewer, resolved fine.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.configure"), NodeType.Method, "configure"))
        storage.upsertNode(GraphNode(NodeId("vendor/m1/Builder.kt#Builder.name(String)"), NodeType.Method, "name"))
        listOf("vendor/m2/Component.kt#Component.name", "vendor/m3/Property.kt#Property.name", "vendor/m4/Builder.kt#Builder.name")
            .forEach { storage.upsertNode(GraphNode(NodeId(it), NodeType.Custom("Field"), "name")) }
        storage.insertUnresolvedReference(reference("name", "src/caller/A.kt#A.configure", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edges = storage.getEdgesFrom(NodeId("src/caller/A.kt#A.configure"))
        edges shouldHaveSize 1
        edges.single().target shouldBe NodeId("vendor/m1/Builder.kt#Builder.name(String)")
        // One candidate, so it is unambiguous -- not merely resolved, but resolved with full
        // confidence, which is what the fields were costing it even when it stayed under the cap.
        edges.single().confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME
    }

    test("a receiver's declared type resolves a name that is otherwise hopelessly ambiguous") {
        // The `getId` shape: far more same-name declarations than the cap, so without the hint
        // this reference resolves to nothing at all rather than to something imprecise.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        listOf("UserSessionModel", "RealmModel", "ClientModel", "GroupModel", "RoleModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverType = "UserSessionModel")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edge = storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).single()
        edge.target shouldBe NodeId("vendor/models/UserSessionModel.kt#UserSessionModel.getId()")
    }

    test("the same reference without the receiver hint stays unresolved, which is what the hint buys") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        listOf("UserSessionModel", "RealmModel", "ClientModel", "GroupModel", "RoleModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 0
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).shouldBeEmpty()
    }

    test("a receiver hint matching no candidate falls back rather than losing the resolution") {
        // The inherited-method case: the receiver is declared `JpaUserSession`, but `getId` is
        // declared on the interface it implements, so nothing sits under the hint's own name.
        // The hint must not turn a resolvable call into an unresolved one.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("vendor/models/UserSessionModel.kt#UserSessionModel.getId()"), NodeType.Method, "getId"))
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverType = "JpaUserSession")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).single().target shouldBe
            NodeId("vendor/models/UserSessionModel.kt#UserSessionModel.getId()")
    }

    test("a chained receiver is typed from the declared return type of the call that produced it") {
        // `authSession.getParentSession().getId()`: nothing in the calling file says what
        // getParentSession returns, but the symbol table does.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(
            GraphNode(
                NodeId("vendor/models/AuthenticationSessionModel.kt#AuthenticationSessionModel.getParentSession()"),
                NodeType.Method, "getParentSession",
                properties = mapOf("returns" to JsonPrimitive("RootAuthenticationSessionModel"))
            )
        )
        listOf("RootAuthenticationSessionModel", "UserSessionModel", "RealmModel", "ClientModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverCall = "getParentSession")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).single().target shouldBe
            NodeId("vendor/models/RootAuthenticationSessionModel.kt#RootAuthenticationSessionModel.getId()")
    }

    test("implementations of the chained call that return different types yield no hint rather than a guess") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        // Same name, two return types: which one this chain went through is unknowable here.
        storage.upsertNode(
            GraphNode(NodeId("vendor/a/One.kt#One.get()"), NodeType.Method, "get",
                properties = mapOf("returns" to JsonPrimitive("RootAuthenticationSessionModel")))
        )
        storage.upsertNode(
            GraphNode(NodeId("vendor/b/Two.kt#Two.get()"), NodeType.Method, "get",
                properties = mapOf("returns" to JsonPrimitive("UserSessionModel")))
        )
        listOf("RootAuthenticationSessionModel", "UserSessionModel", "RealmModel", "ClientModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverCall = "get")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        // Unresolved, not resolved-to-the-first-one: a wrong narrowing is worse than none, because
        // it would emit a confident edge to a method this call never reaches.
        stats.resolved shouldBe 0
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).shouldBeEmpty()
    }

    test("a declared receiver type wins over the chained-call hint when both are present") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(
            GraphNode(NodeId("vendor/x/Src.kt#Src.get()"), NodeType.Method, "get",
                properties = mapOf("returns" to JsonPrimitive("RealmModel")))
        )
        listOf("UserSessionModel", "RealmModel", "ClientModel", "GroupModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt")
                .copy(receiverType = "UserSessionModel", receiverCall = "get")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        // The declared type is read straight off a declaration in the calling file; the chained
        // hint is inferred across files. The direct evidence is the one to trust.
        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).single().target shouldBe
            NodeId("vendor/models/UserSessionModel.kt#UserSessionModel.getId()")
    }

    test("return types that differ but share a supertype still type a chained receiver") {
        // Keycloak's `getUserSession`: 18 declarations return UserSessionModel, a couple return
        // adapters that implement it. Whichever one the chain went through, the next call reaches
        // the method UserSessionModel declares -- so the supertype is sound for all of them.
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("vendor/models/UserSessionModel.kt#UserSessionModel"), NodeType.Class, "UserSessionModel"))
        storage.upsertNode(
            GraphNode(NodeId("vendor/models/UserSessionAdapter.kt#UserSessionAdapter"), NodeType.Class, "UserSessionAdapter",
                properties = mapOf("supertypes" to JsonArray(listOf(JsonPrimitive("UserSessionModel")))))
        )
        storage.upsertNode(
            GraphNode(NodeId("vendor/a/One.kt#One.getUserSession()"), NodeType.Method, "getUserSession",
                properties = mapOf("returns" to JsonPrimitive("UserSessionModel")))
        )
        storage.upsertNode(
            GraphNode(NodeId("vendor/b/Two.kt#Two.getUserSession()"), NodeType.Method, "getUserSession",
                properties = mapOf("returns" to JsonPrimitive("UserSessionAdapter")))
        )
        listOf("UserSessionModel", "RealmModel", "ClientModel", "GroupModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverCall = "getUserSession")
        )

        ReferenceResolver(storage).resolveAll()

        storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run"))
            .filter { it.type == EdgeType.Calls }.single().target shouldBe
            NodeId("vendor/models/UserSessionModel.kt#UserSessionModel.getId()")
    }

    test("unrelated return types still yield no hint -- agreement means supertype, not majority") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        // Three of four say RealmModel, but ClientModel is not below it: a popularity contest
        // would narrow to a type this call may never have gone through.
        listOf("RealmModel", "RealmModel", "RealmModel").forEachIndexed { i, r ->
            storage.upsertNode(GraphNode(NodeId("vendor/m$i/T$i.kt#T$i.get()"), NodeType.Method, "get",
                properties = mapOf("returns" to JsonPrimitive(r))))
        }
        storage.upsertNode(GraphNode(NodeId("vendor/m9/T9.kt#T9.get()"), NodeType.Method, "get",
            properties = mapOf("returns" to JsonPrimitive("ClientModel"))))
        listOf("RealmModel", "ClientModel", "GroupModel", "RoleModel").forEach { type ->
            storage.upsertNode(GraphNode(NodeId("vendor/models/$type.kt#$type.getId()"), NodeType.Method, "getId"))
        }
        storage.insertUnresolvedReference(
            reference("getId", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(receiverCall = "get")
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 0
    }

    test("a call edge carries every call site's line, not just the last one") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("vendor/lib/B.kt#B.callee"), NodeType.Method, "callee"))
        listOf(12, 40, 25).forEach { line ->
            storage.insertUnresolvedReference(
                reference("callee", "src/caller/A.kt#A.run", "src/caller/A.kt").copy(line = line)
            )
        }

        ReferenceResolver(storage).resolveAll()

        val edge = storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run")).single { it.type == EdgeType.Calls }
        // Sorted, complete, and on the edge -- so "list every call site as file:line" is answerable
        // from the graph instead of by reopening the caller and searching it.
        (edge.properties["lines"] as JsonArray).map { (it as JsonPrimitive).content } shouldBe
            listOf("12", "25", "40")
    }

    test("extends/implements declared in the AST become queryable Implements edges") {
        storage.upsertNode(GraphNode(NodeId("vendor/models/UserSessionModel.kt#UserSessionModel"), NodeType.Class, "UserSessionModel"))
        storage.upsertNode(
            GraphNode(NodeId("vendor/models/UserSessionAdapter.kt#UserSessionAdapter"), NodeType.Class, "UserSessionAdapter",
                properties = mapOf("supertypes" to JsonArray(listOf(JsonPrimitive("UserSessionModel")))))
        )

        ReferenceResolver(storage).resolveAll()

        val edges = storage.getEdgesTo(NodeId("vendor/models/UserSessionModel.kt#UserSessionModel"))
            .filter { it.type == EdgeType.Implements }
        edges.single().source shouldBe NodeId("vendor/models/UserSessionAdapter.kt#UserSessionAdapter")
    }

    test("AC-14: an ambiguous name with 2-3 candidates resolves to an edge for each, at reduced confidence") {
        storage.upsertNode(GraphNode(NodeId("src/caller/A.kt#A.run"), NodeType.Method, "run"))
        storage.upsertNode(GraphNode(NodeId("vendor/m1/Impl.kt#Impl.handle"), NodeType.Method, "handle"))
        storage.upsertNode(GraphNode(NodeId("vendor/m2/Impl.kt#Impl.handle"), NodeType.Method, "handle"))
        storage.insertUnresolvedReference(reference("handle", "src/caller/A.kt#A.run", "src/caller/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1 // one *reference* resolved, into two edges
        val edges = storage.getEdgesFrom(NodeId("src/caller/A.kt#A.run"))
        edges shouldHaveSize 2
        edges.map { it.target } shouldContainExactlyInAnyOrder
            listOf(NodeId("vendor/m1/Impl.kt#Impl.handle"), NodeId("vendor/m2/Impl.kt#Impl.handle"))
        edges.forEach {
            it.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME_AMBIGUOUS
            (it.properties["rung"] as JsonPrimitive).content shouldBe "repo_unique_name"
        }
    }

    test("AC-15: a minConfidence of 0.8 returns only unambiguously-resolved call edges; a low one returns the wider ambiguous set") {
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.localTarget"), NodeType.Method, "localTarget"))
        storage.upsertNode(GraphNode(NodeId("vendor/m1/Impl.kt#Impl.handle"), NodeType.Method, "handle"))
        storage.upsertNode(GraphNode(NodeId("vendor/m2/Impl.kt#Impl.handle"), NodeType.Method, "handle"))

        storage.insertUnresolvedReference(reference("localTarget", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))
        storage.insertUnresolvedReference(reference("handle", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))

        ReferenceResolver(storage).resolveAll()

        val highConfidence = storage.getAllEdges(minConfidence = 0.8).filter { it.type == EdgeType.Calls }
        highConfidence shouldHaveSize 1
        highConfidence.single().target shouldBe NodeId("src/pkg/Widget.kt#Widget.localTarget")

        val allConfidence = storage.getAllEdges(minConfidence = 0.0).filter { it.type == EdgeType.Calls }
        allConfidence shouldHaveSize 3
    }

    test("a self-recursive reference resolves to its own declaration via local scope") {
        storage.upsertNode(GraphNode(NodeId("src/pkg/A.kt#A.recurse"), NodeType.Method, "recurse"))
        storage.insertUnresolvedReference(reference("recurse", "src/pkg/A.kt#A.recurse", "src/pkg/A.kt"))

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edges = storage.getEdgesFrom(NodeId("src/pkg/A.kt#A.recurse"))
        edges shouldHaveSize 1
        edges.single().target shouldBe NodeId("src/pkg/A.kt#A.recurse")
        edges.single().confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_LOCAL_SCOPE
    }

    test("resolveAll wipes and rebuilds the whole Calls set rather than accumulating stale edges") {
        // Simulate a previously-resolved edge to a target that no longer exists (e.g. the
        // declaration was renamed away on a later reindex, but nothing explicitly removed
        // the old Calls edge -- that's exactly what pass 2's full rebuild is for).
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("calls:A#caller:stale#target"),
                source = NodeId("A#caller"),
                target = NodeId("stale#target"),
                type = EdgeType.Calls
            )
        )
        storage.upsertNode(GraphNode(NodeId("A#caller"), NodeType.Method, "caller"))
        // No unresolved reference and no matching declaration for "stale#target" anymore.

        ReferenceResolver(storage).resolveAll()

        storage.getEdgesFrom(NodeId("A#caller")).shouldBeEmpty()
    }

    test("a non-Calls edge is untouched by resolveAll's rebuild") {
        storage.upsertNode(GraphNode(NodeId("A#file"), NodeType.CodeFile, "A.kt"))
        storage.upsertNode(GraphNode(NodeId("A#member"), NodeType.Method, "member"))
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("contains:A#file:A#member"),
                source = NodeId("A#file"),
                target = NodeId("A#member"),
                type = EdgeType.Contains
            )
        )

        ReferenceResolver(storage).resolveAll()

        storage.getEdgesFrom(NodeId("A#file")).map { it.type } shouldBe listOf(EdgeType.Contains)
    }

    test("resolves a reference across two different artifacts (the point of this slice)") {
        storage.upsertNode(GraphNode(NodeId("FileA.kt#caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("FileB.kt#callee"), NodeType.Method, "callee"))
        storage.insertUnresolvedReference(
            UnresolvedReference(
                referenceName = "callee",
                referringSymbolId = NodeId("FileA.kt#caller"),
                repoRelativePath = "FileA.kt",
                artifactId = ArtifactId("FileA.kt"),
                line = 3
            )
        )

        val stats = ReferenceResolver(storage).resolveAll()

        stats.resolved shouldBe 1
        val edge = storage.getEdgesFrom(NodeId("FileA.kt#caller")).single()
        edge.target shouldBe NodeId("FileB.kt#callee")
    }

    test("resolution is deterministic: the same source yields the same edges and confidences across runs") {
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.caller"), NodeType.Method, "caller"))
        storage.upsertNode(GraphNode(NodeId("src/pkg/Widget.kt#Widget.helper"), NodeType.Method, "helper"))
        storage.upsertNode(GraphNode(NodeId("vendor/m1/Impl.kt#Impl.handle"), NodeType.Method, "handle"))
        storage.upsertNode(GraphNode(NodeId("vendor/m2/Impl.kt#Impl.handle"), NodeType.Method, "handle"))
        storage.insertUnresolvedReference(reference("helper", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))
        storage.insertUnresolvedReference(reference("handle", "src/pkg/Widget.kt#Widget.caller", "src/pkg/Widget.kt"))

        ReferenceResolver(storage).resolveAll()
        val firstRun = storage.getAllEdges().filter { it.type == EdgeType.Calls }.toSet()

        ReferenceResolver(storage).resolveAll()
        val secondRun = storage.getAllEdges().filter { it.type == EdgeType.Calls }.toSet()

        secondRun shouldBe firstRun
    }
})
