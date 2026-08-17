package io.contextgraph.cli

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.core.NodeType
import io.contextgraph.extractors.ConfigExtractor
import io.contextgraph.extractors.MarkdownExtractor
import io.contextgraph.extractors.PdfExtractor
import io.contextgraph.extractors.SemanticExtractor
import io.contextgraph.extractors.SqlExtractor
import io.contextgraph.extractors.TreeSitterExtractor
import io.contextgraph.ingest.ChecksumTracker
import io.contextgraph.ingest.FileDiscovery
import io.contextgraph.ingest.IngestPipeline
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Proves the wiring `io.contextgraph.ingest.ReindexPrimitive.buildPipeline()` (shared by
 * `Main.kt`'s `IndexCommand` and `McpServer.kt`'s `index_project` tool) uses since slice 18
 * deleted the regex extractor and the [io.contextgraph.extractors.CodeExtractorRouter]
 * that used to choose between it and [TreeSitterExtractor]: [TreeSitterExtractor] is now
 * the *only* [io.contextgraph.core.ResourceExtractor] registered for
 * [NodeType.CodeFile]/[NodeType.TestFile], run through the real [IngestPipeline]
 * (concurrent extraction, single-writer consumer, unchanged) into a real
 * [SqliteStorageAdapter].
 *
 * With the router gone, [io.contextgraph.core.ExtractorRegistry.findExtractors] returning
 * every matching extractor is no longer a double-registration hazard for code files --
 * there is exactly one candidate. What still matters, and what this test exists to prove,
 * is the distinction the deleted router used to encode structurally and
 * [TreeSitterExtractor] now encodes internally:
 *
 * 1. A file type tree-sitter covers gets real symbols under tree-sitter's declaration-site
 *    IDs (`Auth/UserService.java#UserService`).
 * 2. A file type NO grammar covers -- demonstrated here with `.go`, which
 *    [io.contextgraph.treesitter.LanguageRegistry] has never claimed and never will (Go
 *    and Rust were dropped, not deferred, per the interview decision) -- still gets a
 *    file node (so the artifact isn't silently missing from the graph), but *no* symbol
 *    nodes and *no* [io.contextgraph.core.ExtractionDiagnostic]. That last part is the
 *    substance of slice 18: an uncovered file type is not a failure, so it must not look
 *    like one. A covered language's genuine parse failure, by contrast, still gets a
 *    diagnostic -- proven separately by each grammar's own snapshot suite (e.g.
 *    `JavaGrammarSnapshotTest`'s "AC-3: a syntax error still produces a file node, a
 *    diagnostic, ..." test) -- conflating the two cases would either flood diagnostics
 *    with noise for every YAML/Podfile/shell script in a repo, or hide a real grammar
 *    regression among them.
 *
 * `test-fixtures/mixed-language-routing` has one Java file (tree-sitter, real grammar) and
 * one Go file (no grammar at all, and never will have one).
 */
class CodeExtractorRoutingTest : FunSpec({

    test("indexing a real directory gives a tree-sitter-covered file real symbols, and a genuinely uncovered extension a file node with no symbols") {
        val fixtureRoot = findFixture("mixed-language-routing")
        val workDir = Files.createTempDirectory("contextgraph-routing-")
        val dbPath = workDir.resolve("graph.db")

        try {
            val storage = SqliteStorageAdapter(dbPath)
            val stats = try {
                val registry = ExtractorRegistry(
                    listOf(
                        MarkdownExtractor(), TreeSitterExtractor(), PdfExtractor(),
                        SqlExtractor(), ConfigExtractor(), SemanticExtractor()
                    )
                )
                val pipeline = IngestPipeline(
                    discovery = FileDiscovery(ContextGraphConfig()),
                    registry = registry,
                    checksumTracker = ChecksumTracker(),
                    storage = storage,
                    context = ExtractionContext(fixtureRoot, ContextGraphConfig())
                )
                runBlocking { pipeline.index(fixtureRoot) }
            } finally {
                storage.close()
            }
            stats.artifactCount shouldBe 2

            // Reopen as a fresh adapter instance -- proves persistence across close/reopen,
            // not just in-memory state from the write.
            val reopened = SqliteStorageAdapter(dbPath)
            try {
                val allNodes = reopened.getAllNodes()

                // Java: exactly the tree-sitter declaration-site ID, and only one class node
                // for UserService -- nothing under a second, regex-derived identity scheme.
                val javaClassNodes = allNodes.filter { it.label == "UserService" && it.type == NodeType.Class }
                javaClassNodes shouldHaveSize 1
                javaClassNodes.single().id.value shouldBe "Auth/UserService.java#UserService"

                allNodes.any { it.id.value == "Auth/UserService.java#UserService.save(String)" } shouldBe true

                // No regex-extractor node exists for the Java file: those used "class:"/"method:"/
                // "file:" ID prefixes keyed by the artifact's absolute path -- the extractor
                // that produced them no longer exists.
                allNodes.none { it.id.value.startsWith("class:") } shouldBe true
                allNodes.none { it.id.value.startsWith("method:") } shouldBe true
                allNodes.none { it.id.value.startsWith("file:") } shouldBe true

                // NodeType.Custom("Field") survives a real write, close, reopen, read-back cycle.
                val nameField = allNodes.first { it.id.value == "Auth/UserService.java#UserService.name" }
                nameField.type shouldBe NodeType.Custom("Field")

                // Go: no LanguageRegistry entry exists for ".go" and never will (dropped, not
                // deferred) -- exactly one file node, no symbol nodes at all, and no Contains
                // edge out of it. This is the honest, sparse outcome the spec asks for, not a
                // regex-derived approximation.
                val goFileNode = allNodes.single { it.id.value == "Billing/invoice.go" }
                goFileNode.type shouldBe NodeType.CodeFile

                allNodes.none { it.label == "Invoice" } shouldBe true
                allNodes.none { it.label == "Amount" } shouldBe true
                reopened.getEdgesFrom(goFileNode.id).filter { it.type == EdgeType.Contains }.shouldBeEmpty()
            } finally {
                reopened.close()
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    test("an uncovered file type produces no ExtractionDiagnostic, unlike a covered language's parse failure") {
        val fixtureRoot = findFixture("mixed-language-routing")
        val goFile = fixtureRoot.resolve("Billing/invoice.go")
        val now = Clock.System.now()
        val artifact = Artifact(
            id = ArtifactId("Billing/invoice.go"),
            type = NodeType.CodeFile,
            path = goFile.absolutePathString(),
            checksum = "routing-test",
            size = Files.size(goFile),
            lastModified = now,
            indexedAt = now
        )

        val result = runBlocking {
            TreeSitterExtractor().extract(artifact, ExtractionContext(fixtureRoot, ContextGraphConfig()))
        }

        // A file node so the artifact isn't silently missing from the graph, but nothing else:
        // no symbols (nothing to extract), and critically no diagnostic -- an uncovered file
        // type is not a failure, so it must not be reported like one.
        result.nodes shouldHaveSize 1
        result.nodes.single().id.value shouldBe "Billing/invoice.go"
        result.edges.shouldBeEmpty()
        result.diagnostics.shouldBeEmpty()
    }

    test("AC-3: a real IngestPipeline run surfaces IndexStats.parseWarnings for a covered language's grammar failure") {
        // test-fixtures/java-grammar carries a deliberately-unparseable file
        // (Broken/Broken.java, reused rather than duplicated -- see
        // JavaGrammarSnapshotTest's "AC-3" test, which exercises the same fixture at the
        // extractor level) alongside three well-formed Java files. Before this fix, nothing
        // downstream of TreeSitterExtractor ever read ExtractionResult.diagnostics, so a real
        // `index` run couldn't tell the two apart.
        val fixtureRoot = findFixture("java-grammar")
        val dbDir = Files.createTempDirectory("contextgraph-parse-diagnostics-")
        val storage = SqliteStorageAdapter(dbDir.resolve("graph.db"))
        val stats = try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = ExtractorRegistry(listOf(TreeSitterExtractor())),
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(fixtureRoot, ContextGraphConfig())
            )
            runBlocking { pipeline.index(fixtureRoot) }
        } finally {
            storage.close()
            dbDir.toFile().deleteRecursively()
        }

        // Exactly one file in the fixture fails to parse -- Broken/Broken.java -- so exactly
        // one parse warning, no matter how many well-formed files ran alongside it.
        stats.parseWarnings shouldBe 1
        // "Failed" means an extractor threw. Broken.java's grammar error is recovered from,
        // not thrown -- see TreeSitterExtractor's KDoc -- so it must NOT also count as failed.
        stats.failed shouldBe 0
        // The other files in the same run are unaffected: still fully extracted.
        stats.artifactCount shouldBe 4
    }

    test("AC-4: a real IngestPipeline run over an uncovered file type reports zero parseWarnings") {
        // mixed-language-routing has one covered, well-formed Java file and one uncovered
        // .go file -- no grammar failure anywhere in this fixture, so parseWarnings must
        // stay at zero: an uncovered extension is not a parse failure.
        val fixtureRoot = findFixture("mixed-language-routing")
        val dbDir = Files.createTempDirectory("contextgraph-parse-diagnostics-uncovered-")
        val storage = SqliteStorageAdapter(dbDir.resolve("graph.db"))
        val stats = try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = ExtractorRegistry(listOf(TreeSitterExtractor())),
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(fixtureRoot, ContextGraphConfig())
            )
            runBlocking { pipeline.index(fixtureRoot) }
        } finally {
            storage.close()
            dbDir.toFile().deleteRecursively()
        }

        stats.parseWarnings shouldBe 0
        stats.failed shouldBe 0
        stats.artifactCount shouldBe 2
    }
})

/**
 * Test working directory is Gradle's per-module convention (e.g. `modules/cli`), which
 * differs between an IDE run and `./gradlew test`. Walk up to the repo root — identified
 * by `settings.gradle.kts` — rather than assume a fixed relative depth.
 */
private fun findFixture(name: String): Path {
    var dir = Path.of("").toAbsolutePath()
    while (dir.parent != null && !dir.resolve("settings.gradle.kts").exists()) {
        dir = dir.parent
    }
    val fixture = dir.resolve("test-fixtures").resolve(name)
    check(fixture.isDirectory()) { "Fixture source directory not found: $fixture" }
    return fixture
}
