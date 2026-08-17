package io.contextgraph.extractors.snapshot

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeType
import io.contextgraph.extractors.TreeSitterExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Runs the real production extraction path ([TreeSitterExtractor], the same class
 * `IngestPipeline` calls) over every file in a fixture directory and asserts the result
 * against a committed golden snapshot.
 *
 * This is the harness the slice's acceptance criteria require to be extensible without
 * modification: adding a language's snapshot test is "write a fixture directory, write a
 * snapshot file, call [assertMatchesGoldenSnapshot] from a one-line test" -- nothing here
 * is Java-specific or otherwise language-aware. [GoldenSnapshotHarnessSelfTest] proves
 * that by pointing this exact object at a second, unrelated fixture with zero changes to
 * this file.
 */
object GoldenSnapshotHarness {
    const val SNAPSHOT_FILE_NAME = "snapshot.json"

    private val json = Json { prettyPrint = true }

    data class Extracted(
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>,
        val diagnostics: List<ExtractionDiagnostic>
    )

    /**
     * Extracts every file under [fixtureRoot] (recursively, excluding [SNAPSHOT_FILE_NAME]
     * itself) through [TreeSitterExtractor], exactly as `IngestPipeline` would for each
     * file independently -- one [Artifact] per file, no cross-file state.
     */
    fun extractFixture(fixtureRoot: Path): Extracted {
        val extractor = TreeSitterExtractor()
        val context = ExtractionContext(projectRoot = fixtureRoot, config = ContextGraphConfig())
        val now = Clock.System.now()

        val files = fixtureRoot.toFile().walkTopDown()
            .filter { it.isFile && it.name != SNAPSHOT_FILE_NAME }
            .sortedBy { it.path }
            .toList()

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val diagnostics = mutableListOf<ExtractionDiagnostic>()

        for (file in files) {
            val relative = fixtureRoot.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            val artifact = Artifact(
                id = ArtifactId(relative),
                type = NodeType.CodeFile,
                path = file.absolutePath,
                checksum = "golden-snapshot",
                size = file.length(),
                lastModified = now,
                indexedAt = now
            )
            val result = runBlocking { extractor.extract(artifact, context) }
            nodes += result.nodes
            edges += result.edges
            diagnostics += result.diagnostics
        }

        return Extracted(nodes, edges, diagnostics)
    }

    /**
     * Normalizes an [Extracted] result to deterministic, pretty-printed JSON: sorted by
     * ID so file-walk order never affects the diff, and stripped of anything that varies
     * run to run ([io.contextgraph.core.Provenance.extractedAt], [Artifact.indexedAt]) --
     * structure only, per the spec's "snapshots cover structure only" requirement.
     */
    fun normalize(extracted: Extracted): String {
        val obj = buildJsonObject {
            putJsonArray("nodes") {
                extracted.nodes.sortedBy { it.id.value }.forEach { node ->
                    addJsonObject {
                        put("id", node.id.value)
                        put("type", NodeType.stringify(node.type))
                        put("label", node.label)
                        put("confidence", node.confidence)
                        putJsonObject("properties") {
                            node.properties.toSortedMap().forEach { (k, v) -> put(k, v) }
                        }
                        putJsonArray("provenance") {
                            node.provenance.forEach { p ->
                                addJsonObject {
                                    put("path", p.path)
                                    put("lineStart", p.lineStart)
                                    put("lineEnd", p.lineEnd)
                                    put("extractor", p.extractor)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("edges") {
                extracted.edges
                    .sortedWith(compareBy({ it.source.value }, { it.target.value }, { EdgeType.stringify(it.type) }))
                    .forEach { edge ->
                        addJsonObject {
                            put("id", edge.id.value)
                            put("source", edge.source.value)
                            put("target", edge.target.value)
                            put("type", EdgeType.stringify(edge.type))
                            put("confidence", edge.confidence)
                        }
                    }
            }
            putJsonArray("diagnostics") {
                extracted.diagnostics.sortedBy { it.message }.forEach { d ->
                    addJsonObject {
                        put("severity", d.severity.name)
                        put("message", d.message)
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Regenerates the committed snapshot from the current extraction. Not called by any
     * test -- invoked once by hand (via a throwaway test/script) whenever a fixture is
     * deliberately changed, mirroring how any golden-snapshot workflow updates its baseline.
     */
    fun writeSnapshot(fixtureRoot: Path, snapshotFile: Path) {
        snapshotFile.writeText(normalize(extractFixture(fixtureRoot)))
    }

    /**
     * Asserts extraction of [fixtureRoot] matches the committed [snapshotFile] exactly.
     * Fails with both full documents and a line-level diff identifying what changed.
     */
    fun assertMatchesGoldenSnapshot(fixtureRoot: Path, snapshotFile: Path) {
        val actual = normalize(extractFixture(fixtureRoot))
        check(snapshotFile.exists()) {
            "No golden snapshot at $snapshotFile.\nRun GoldenSnapshotHarness.writeSnapshot() to create it. Fresh extraction:\n$actual"
        }
        val expected = snapshotFile.readText()
        if (actual.trim() != expected.trim()) {
            throw AssertionError(
                "Extraction of $fixtureRoot does not match golden snapshot $snapshotFile.\n\n" +
                    "--- diff (- expected, + actual) ---\n${lineDiff(expected, actual)}\n\n" +
                    "--- full expected ---\n$expected\n\n--- full actual ---\n$actual"
            )
        }
    }

    /** A minimal, dependency-free line diff: enough to point at what changed, not a full LCS alignment. */
    private fun lineDiff(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val max = maxOf(expectedLines.size, actualLines.size)
        val sb = StringBuilder()
        for (i in 0 until max) {
            val e = expectedLines.getOrNull(i)
            val a = actualLines.getOrNull(i)
            if (e != a) {
                if (e != null) sb.appendLine("- [line ${i + 1}] $e")
                if (a != null) sb.appendLine("+ [line ${i + 1}] $a")
            }
        }
        if (sb.isEmpty()) sb.appendLine("(line counts differ: expected ${expectedLines.size}, actual ${actualLines.size})")
        return sb.toString()
    }
}
