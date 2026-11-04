package io.contextgraph.storage

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * What a name is findable by. The split words were always indexed; the joined pairs are what let
 * a search for a compound written as one word -- "viewmodel", "usecase" -- find anything at all.
 */
class FtsIndexTextTest : FunSpec({

    test("a compound identifier indexes itself, its words and its adjacent word pairs") {
        FtsIndexText.labelFor("OrderDetailsViewModel") shouldBe
            "OrderDetailsViewModel Order Details View Model OrderDetails DetailsView ViewModel"
    }

    test("a two-word identifier adds no pair beyond itself, and a single word is left alone") {
        FtsIndexText.labelFor("ViewModel") shouldBe "ViewModel View Model"
        FtsIndexText.labelFor("load") shouldBe "load"
    }

    test("a search for a compound written as one word finds the identifier") {
        val storage = SqliteStorageAdapter(Files.createTempDirectory("fts-compound").resolve("graph.db"))
        try {
            storage.upsertNode(GraphNode(NodeId("ui/Order.kt#OrderDetailsViewModel"), NodeType.Class, "OrderDetailsViewModel"))
            storage.upsertNode(GraphNode(NodeId("ui/Order.kt#OrderDetailsView"), NodeType.Class, "OrderDetailsView"))

            val found = storage.searchNodes("viewmodel", minConfidence = 0.0, limit = 10).map { it.label }
            found shouldContain "OrderDetailsViewModel"
            found.contains("OrderDetailsView") shouldBe false
        } finally {
            storage.close()
        }
    }
})
