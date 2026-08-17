package io.contextgraph.storage

import io.contextgraph.core.GraphDb
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * End-to-end proof (AC-29, AC-30, AC-31) that the committed baseline and the local
 * overlay behave as a real SQLite-backed store, not just as file copies: a query
 * against a fresh clone (no overlay) is answered from the baseline; the first local
 * write seeds the overlay from the baseline instead of starting empty; and once seeded,
 * local writes never touch the baseline file.
 */
class GraphDbOverlaySeedingTest : FunSpec({

    fun node(id: String, label: String) = GraphNode(NodeId(id), NodeType.Class, label, confidence = 1.0)

    test("fresh clone with no overlay: reads resolve to the committed baseline") {
        val root = Files.createTempDirectory("overlay-seed-test")
        val baseline = SqliteStorageAdapter(GraphDb.baseline(root))
        baseline.upsertNode(node("UserService", "UserService"))
        baseline.close()

        // Simulate an agent querying a fresh clone before any local indexing.
        val reader = SqliteStorageAdapter(GraphDb.forRead(root))
        reader.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "UserService"
    }

    test("first local index seeds the overlay from the baseline rather than starting empty") {
        val root = Files.createTempDirectory("overlay-seed-test")
        val baseline = SqliteStorageAdapter(GraphDb.baseline(root))
        baseline.upsertNode(node("UserService", "UserService"))
        baseline.close()

        val writer = SqliteStorageAdapter(GraphDb.forLocalWrite(root))
        writer.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "UserService"
    }

    test("a local write lands only in the overlay; the baseline is untouched") {
        val root = Files.createTempDirectory("overlay-seed-test")
        val baseline = SqliteStorageAdapter(GraphDb.baseline(root))
        baseline.upsertNode(node("UserService", "UserService"))
        baseline.close()

        val writer = SqliteStorageAdapter(GraphDb.forLocalWrite(root))
        writer.upsertNode(node("NewLocalClass", "NewLocalClass"))
        writer.close()

        // The new node is visible through the read seam (overlay now exists)...
        val reader = SqliteStorageAdapter(GraphDb.forRead(root))
        reader.getNode(NodeId("NewLocalClass")).shouldNotBeNull().label shouldBe "NewLocalClass"

        // ...but opening the committed baseline directly shows it was never written.
        val rebaseline = SqliteStorageAdapter(GraphDb.baseline(root))
        rebaseline.getNode(NodeId("NewLocalClass")).shouldBeNull()
        rebaseline.getNode(NodeId("UserService")).shouldNotBeNull()
    }

    test("deleting the overlay is safe: the next local write reseeds it from the baseline, losing only local-only data") {
        val root = Files.createTempDirectory("overlay-seed-test")
        val baseline = SqliteStorageAdapter(GraphDb.baseline(root))
        baseline.upsertNode(node("UserService", "UserService"))
        baseline.close()

        val firstOverlay = SqliteStorageAdapter(GraphDb.forLocalWrite(root))
        firstOverlay.upsertNode(node("NewLocalClass", "NewLocalClass"))
        firstOverlay.close()

        Files.delete(GraphDb.overlay(root))

        val reseeded = SqliteStorageAdapter(GraphDb.forLocalWrite(root))
        reseeded.getNode(NodeId("UserService")).shouldNotBeNull()
        // Local-only data that never made it into the baseline is gone, as expected —
        // it lived only in the deleted overlay.
        reseeded.getNode(NodeId("NewLocalClass")).shouldBeNull()
    }
})
