package io.contextgraph.cli

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.extractors.TreeSitterExtractor
import io.contextgraph.ingest.ChecksumTracker
import io.contextgraph.ingest.FileDiscovery
import io.contextgraph.ingest.IngestPipeline
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Slice 20's headline proof: a call site tree-sitter actually observes in real Java
 * source -- not [io.contextgraph.ingest.FakeSymbolExtractor]'s `DECLARE`/`REFERENCES`
 * stand-in syntax, which is all [io.contextgraph.ingest.IngestPipelineResolutionTest]
 * (slice 09) could drive AC-10 against, since no grammar emitted call sites yet -- flows
 * through the real production path end to end: [io.contextgraph.treesitter.grammars.JavaGrammar]'s
 * `method_invocation` walk (pass 1) persists an
 * [io.contextgraph.core.UnresolvedReference], and [io.contextgraph.ingest.ReferenceResolver]
 * (pass 2, run by [IngestPipeline] after its single-writer channel drains) resolves it
 * against the symbol table into a real [EdgeType.Calls] edge.
 *
 * Wired exactly like [CodeExtractorRoutingTest]: [TreeSitterExtractor] registered where
 * production's `Main.kt`/`McpServer.kt` register it (the only code extractor since slice
 * 18 deleted the regex fallback and the router that chose between them), through the
 * real [IngestPipeline] into a real [SqliteStorageAdapter].
 */
class CallSiteResolutionEndToEndTest : FunSpec({

    test("AC-10 on production code: a real cross-file Java method call produces a Calls edge through the real IngestPipeline") {
        val root = Files.createTempDirectory("call-site-e2e-")
        try {
            root.resolve("Caller.java").writeText(
                """
                package com.example.app;

                public class Caller {
                    public void run() {
                        Callee.doWork();
                    }
                }
                """.trimIndent()
            )
            root.resolve("Callee.java").writeText(
                """
                package com.example.app;

                public class Callee {
                    public static void doWork() {
                    }
                }
                """.trimIndent()
            )

            val storage = SqliteStorageAdapter(root.resolve("graph.db"))
            try {
                val pipeline = IngestPipeline(
                    discovery = FileDiscovery(ContextGraphConfig()),
                    registry = ExtractorRegistry(listOf(TreeSitterExtractor())),
                    checksumTracker = ChecksumTracker(),
                    storage = storage,
                    context = ExtractionContext(root, ContextGraphConfig())
                )
                runBlocking { pipeline.index(root) }

                val callerMethod = storage.getAllNodes()
                    .single { it.id.value == "Caller.java#Caller.run()" }
                val calleeMethod = storage.getAllNodes()
                    .single { it.id.value == "Callee.java#Callee.doWork()" }

                val callsEdges = storage.getEdgesFrom(callerMethod.id).filter { it.type == EdgeType.Calls }
                callsEdges shouldHaveSize 1
                callsEdges.single().target shouldBe calleeMethod.id
            } finally {
                storage.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test(
        "a call to a same-type method with an ambiguous name elsewhere in the repo resolves via local " +
            "scope (slice 10), not the guess slice 09's naive full-rebuild would have had to refuse"
    ) {
        val root = Files.createTempDirectory("call-site-e2e-ambiguous-")
        try {
            root.resolve("A.java").writeText(
                """
                package com.example.ambiguous;

                public class A {
                    public void caller() {
                        helper();
                    }
                    private void helper() {
                    }
                }
                """.trimIndent()
            )
            // A second, unrelated declaration with the same simple name -- makes "helper"
            // ambiguous repo-wide (two candidates). Slice 09's naive resolver left this
            // unresolved outright; slice 10's ladder instead notices that only A's own
            // "helper" is reachable from A.caller()'s own type, and resolves to it
            // unambiguously via the local-scope rung -- B's "helper" is never a candidate.
            root.resolve("B.java").writeText(
                """
                package com.example.ambiguous;

                public class B {
                    void helper() {
                    }
                }
                """.trimIndent()
            )

            val storage = SqliteStorageAdapter(root.resolve("graph.db"))
            try {
                val pipeline = IngestPipeline(
                    discovery = FileDiscovery(ContextGraphConfig()),
                    registry = ExtractorRegistry(listOf(TreeSitterExtractor())),
                    checksumTracker = ChecksumTracker(),
                    storage = storage,
                    context = ExtractionContext(root, ContextGraphConfig())
                )
                runBlocking { pipeline.index(root) }

                val callerMethod = storage.getAllNodes().single { it.id.value == "A.java#A.caller()" }
                val aHelper = storage.getAllNodes().single { it.id.value == "A.java#A.helper()" }
                val callsEdges = storage.getEdgesFrom(callerMethod.id).filter { it.type == EdgeType.Calls }

                callsEdges shouldHaveSize 1
                callsEdges.single().target shouldBe aHelper.id
                callsEdges.single().confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_LOCAL_SCOPE
            } finally {
                storage.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
