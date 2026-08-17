package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.core.NodeId
import io.contextgraph.extractors.MarkdownExtractor
import io.contextgraph.extractors.SemanticExtractor
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * AC-4: "a file type no grammar covers still yields a file node."
 *
 * `Podfile` and `.plist` classify as [io.contextgraph.core.NodeType.Document]
 * (see ArtifactTypeDetector), which matches BOTH [MarkdownExtractor] and
 * [SemanticExtractor]'s `supportedTypes`. registry.findExtractors therefore returns both
 * for the same artifact, so `IngestPipeline`'s consumer sees two [io.contextgraph.core.ExtractionResult]s
 * for it. With litellm disabled (the default), SemanticExtractor returns an empty result.
 * Before the fix, the consumer ran `deleteNodesForArtifact` once per result rather than
 * once per artifact, so the empty Semantic result's delete wiped the nodes the Markdown
 * result had just written -- the artifact row survives, but it ends up with zero nodes.
 *
 * This drives the real [IngestPipeline] with the real [MarkdownExtractor] and
 * [SemanticExtractor] (not fakes) against a real [SqliteStorageAdapter], exactly the
 * production path, so it catches the bug where an in-process unit test of either
 * extractor alone would not.
 */
class IngestPipelineMultiExtractorTest : FunSpec({

    test("AC-4: a Podfile matched by two extractors still ends up with a file node") {
        // The db file lives outside `root` -- root is exactly what FileDiscovery walks, and
        // graph.db's extensionless name would itself classify as NodeType.Document (the same
        // type Podfile does), spuriously pulling the db file into the same multi-extractor
        // path this test is isolating.
        val root = Files.createTempDirectory("ingest-multi-extractor-podfile")
        val dbDir = Files.createTempDirectory("ingest-multi-extractor-podfile-db")
        root.resolve("Podfile").writeText(
            "platform :ios, '15.0'\n\ntarget 'MyApp' do\n  pod 'Alamofire'\nend\n"
        )

        val config = ContextGraphConfig()
        val storage = SqliteStorageAdapter(dbDir.resolve("graph.db"))
        try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(config),
                registry = ExtractorRegistry(listOf(MarkdownExtractor(), SemanticExtractor())),
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(root, config)
            )

            runBlocking { pipeline.index(root) }

            val artifact = storage.getAllArtifacts().single { it.path == "Podfile" }

            // MarkdownExtractor (one of the two extractors matched for NodeType.Document)
            // always emits a whole-file node under this predictable id -- see
            // MarkdownExtractor.extract's `NodeId("file:${artifact.id.value}")`. Before the
            // fix, SemanticExtractor's empty result (litellm disabled) deleted it right back
            // out after MarkdownExtractor wrote it.
            storage.getNode(NodeId("file:${artifact.id.value}")).shouldNotBeNull()

            storage.getAllNodes().shouldNotBeEmpty()
        } finally {
            storage.close()
        }
    }
})
