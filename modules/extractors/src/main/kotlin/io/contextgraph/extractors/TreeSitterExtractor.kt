package io.contextgraph.extractors

import io.contextgraph.core.Artifact
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.ResourceExtractor
import io.contextgraph.treesitter.DeclarationSiteId
import io.contextgraph.treesitter.LanguageRegistry
import io.contextgraph.treesitter.SourceText
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.github.treesitter.ktreesitter.Parser
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * tree-sitter-backed [ResourceExtractor]. Resolves a file's grammar via
 * [LanguageRegistry], parses it, and delegates symbol extraction to that language's
 * [io.contextgraph.treesitter.LanguageSupport.extract] -- every language slice (02, then
 * 04-08) shares this one extractor; only the per-language `extract()` differs.
 *
 * A file whose extension no grammar covers -- including `.go` and `.rs`, dropped along
 * with the regex extractor in slice 18 -- produces a file node with no symbols and no
 * diagnostic (AC-4 in the spec): [LanguageRegistry.resolve] returning `null` is the
 * ordinary "nothing to do here" case, not a failure. It still gets a file node -- the
 * caller has no other source of one for a [NodeType.CodeFile]/[NodeType.TestFile]
 * artifact -- just no symbols and no diagnostic, so an uncovered file type stays visibly
 * distinct from a covered language's parse failure (which gets a file node *and* a
 * diagnostic, below).
 *
 * A file that parses with syntax errors still produces a file node, whatever partial
 * symbols tree-sitter's error recovery left recognizable, and an [ExtractionDiagnostic]
 * -- this method never throws on malformed source, so `IngestPipeline`'s per-extractor
 * try/catch (which would otherwise discard the whole result, file node included) never
 * triggers for a parse failure.
 */
class TreeSitterExtractor : ResourceExtractor {
    override val id = "tree-sitter"
    override val supportedTypes = setOf(NodeType.CodeFile, NodeType.TestFile)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        // artifact.path is repo-relative (slice 09's artifact-identity fix); resolve
        // against context.projectRoot to get an openable path.
        val path = context.projectRoot.resolve(artifact.path)
        val support = LanguageRegistry.resolve(path.name)
            ?: return ExtractionResult(artifact, listOf(uncoveredFileNode(artifact, path, context.projectRoot)), emptyList())

        val repoRelativePath = repoRelativePathOf(context.projectRoot, path)
        val source = path.readText()
        val now = Clock.System.now()

        val parser = Parser(support.language())
        val tree = parser.parse(source)

        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        if (tree.rootNode.hasError) {
            diagnostics.add(
                ExtractionDiagnostic(
                    severity = ExtractionDiagnostic.Severity.WARNING,
                    message = "Syntax error(s) in $repoRelativePath ($id/${support.id}); extraction is partial"
                )
            )
        }

        val fileNode = GraphNode(
            id = DeclarationSiteId.file(repoRelativePath),
            type = artifact.type,
            label = path.name,
            properties = mapOf("language" to JsonPrimitive(support.id)),
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, repoRelativePath, extractor = id, extractedAt = now))
        )

        val symbols = support.extract(
            SymbolExtractionRequest(
                tree = tree,
                source = source,
                sourceText = SourceText(source),
                repoRelativePath = repoRelativePath,
                artifactId = artifact.id,
                extractorId = id,
                extractedAt = now
            )
        )

        return ExtractionResult(
            artifact = artifact,
            nodes = listOf(fileNode) + symbols.nodes,
            edges = symbols.edges,
            diagnostics = diagnostics,
            references = symbols.references
        )
    }

    private fun repoRelativePathOf(projectRoot: Path, filePath: Path): String {
        val root = projectRoot.toAbsolutePath().normalize()
        val file = filePath.toAbsolutePath().normalize()
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/')
    }

    /** File node for an extension no [LanguageRegistry] entry covers -- no symbols, no diagnostic. */
    private fun uncoveredFileNode(artifact: Artifact, path: Path, projectRoot: Path): GraphNode {
        val repoRelativePath = repoRelativePathOf(projectRoot, path)
        return GraphNode(
            id = DeclarationSiteId.file(repoRelativePath),
            type = artifact.type,
            label = path.name,
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, repoRelativePath, extractor = id, extractedAt = Clock.System.now()))
        )
    }
}
