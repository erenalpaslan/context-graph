package io.contextgraph.ingest

import io.contextgraph.core.Artifact
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.ResourceExtractor
import io.contextgraph.core.UnresolvedReference
import kotlinx.datetime.Clock

/**
 * A minimal, fully deterministic [ResourceExtractor] used only by these tests to exercise
 * [IngestPipeline]'s two-pass resolution end-to-end.
 *
 * No real language extractor emits call-site [UnresolvedReference]s yet as of this slice --
 * every grammar (Java, the only one with real declaration extraction landed so far) walks
 * declarations only, never method bodies, so [ExtractionResult.references] is unpopulated in
 * production today. See slice 09's report for why teaching a grammar to detect call sites is
 * out of this slice's ownership. This fake stands in for "some future extractor that does",
 * so the resolution machinery (the part this slice actually owns) can be proven correct
 * against realistic pass-1 output without waiting on that seam.
 *
 * Each source file is a tiny line-oriented script, parsed fresh on every [extract] call (so
 * editing a file's content between indexing runs genuinely changes what pass 1 emits, the
 * same as a real extractor re-parsing changed source):
 *
 *   DECLARE <name>     -- emits a declaration-site GraphNode(type=Method) named <name>,
 *                          scoped to this file, and becomes the "current" declaration for
 *                          any REFERENCES lines that follow
 *   REFERENCES <name>  -- emits an UnresolvedReference to <name>, attributed to the most
 *                          recently DECLAREd symbol in this file (or the file node itself if
 *                          none has been declared yet)
 */
class FakeSymbolExtractor : ResourceExtractor {
    override val id = "fake-symbol"
    override val supportedTypes = setOf(NodeType.CodeFile)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val text = context.projectRoot.resolve(artifact.path).toFile().readText()
        val now = Clock.System.now()
        fun provenanceOf() = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))

        val fileNodeId = NodeId("${artifact.path}#file")
        val nodes = mutableListOf(
            GraphNode(id = fileNodeId, type = NodeType.CodeFile, label = artifact.path, provenance = provenanceOf())
        )
        val references = mutableListOf<UnresolvedReference>()
        var currentDeclId = fileNodeId

        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("DECLARE ") -> {
                    val name = line.removePrefix("DECLARE ").trim()
                    val declId = NodeId("${artifact.path}#$name")
                    nodes.add(GraphNode(id = declId, type = NodeType.Method, label = name, provenance = provenanceOf()))
                    currentDeclId = declId
                }
                line.startsWith("REFERENCES ") -> {
                    val name = line.removePrefix("REFERENCES ").trim()
                    references.add(
                        UnresolvedReference(
                            referenceName = name,
                            referringSymbolId = currentDeclId,
                            repoRelativePath = artifact.path,
                            artifactId = artifact.id,
                            line = 1
                        )
                    )
                }
            }
        }

        return ExtractionResult(artifact = artifact, nodes = nodes, edges = emptyList(), references = references)
    }
}
