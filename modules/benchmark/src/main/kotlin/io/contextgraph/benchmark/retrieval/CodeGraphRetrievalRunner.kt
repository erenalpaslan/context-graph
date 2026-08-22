package io.contextgraph.benchmark.retrieval

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * The CodeGraph side of the retrieval measurement: runs a question's **raw text** through
 * `codegraph explore` against that repo's `codegraph` working copy, and projects the file paths
 * out of the markdown via [CodeGraphExploreParser].
 *
 * One method, mirroring [ContextGraphRetrievalRunner]'s shape, because all three sides must be
 * asked the same thing in the same way. The seam copied here is [RipgrepBaselineRunner]'s -- a
 * subprocess -- not the in-process MCP bridge's: ContextGraph is driven in-process because it can
 * be, while CodeGraph is a native binary. Its CLI's `explore` routes into the same handler as its
 * `codegraph_explore` MCP tool, so shelling out measures the same surface an agent would use
 * while keeping this axis deterministic and LLM-free.
 *
 * The question text is passed after a `--` separator so a question that happens to begin with a
 * dash is read as a query rather than as an option. Nothing else is done to it: no token
 * extraction, no quoting of symbols, no lifting of names out of the gold facts. That is the
 * fairness invariant all three sides share, and the one [RipgrepQueryDeriver]'s KDoc already
 * defends for the baseline.
 */
class CodeGraphRetrievalRunner(
    private val workingCopy: Path,
    private val codegraphPath: String = "codegraph",
    private val timeout: Duration = CodeGraphProcess.DEFAULT_TIMEOUT
) {

    /**
     * Ranked repo-relative paths for [questionText], in CodeGraph's own emission order.
     *
     * An empty list means CodeGraph ran and found nothing -- a real zero. A failure never reaches
     * here as an empty list: [CodeGraphProcess] throws [CodeGraphExecutionException] on a timeout
     * or a non-zero exit, and the caller records that as an unmeasured question plus a skip.
     * Collapsing the two would make a broken invocation indistinguishable from "CodeGraph
     * retrieves nothing", which is the most damaging thing this instrument could get wrong.
     */
    fun rankedFiles(questionText: String): List<String> {
        val output = CodeGraphProcess.run(
            args = listOf("explore", "-p", workingCopy.toAbsolutePath().toString(), "--", questionText),
            codegraphPath = codegraphPath,
            timeout = timeout
        )
        return CodeGraphExploreParser.rankedFiles(output)
    }

    /**
     * Every repo-relative path CodeGraph's index holds, from `codegraph files --json --format
     * flat`, or `null` if that surface cannot be read.
     *
     * This is the per-file index read surface that makes CodeGraph's gold-file coverage a real
     * fraction rather than an explicit unknown -- the thing that was assumed not to exist when the
     * coverage remedy was chosen, and does. `null` here is what falls back to
     * [CoverageBasis.NOT_DETERMINABLE], so a future CodeGraph dropping the flag degrades to an
     * honest unknown instead of a wrong zero.
     */
    fun indexedFiles(): Set<String>? = try {
        val output = CodeGraphProcess.run(
            args = listOf("files", "-p", workingCopy.toAbsolutePath().toString(), "--json", "--format", "flat"),
            codegraphPath = codegraphPath,
            timeout = timeout
        )
        JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(IndexedFile.serializer()), output.trim())
            .map { it.path.removePrefix("./") }
            .toSet()
    } catch (e: Exception) {
        // Degrades to an explicit "not determinable" in the report rather than a wrong zero, but
        // the cause is logged: a reader asking why coverage is unknown should not have to guess.
        logger.warn(e) { "could not read CodeGraph's indexed file list for $workingCopy; reporting coverage as not determinable" }
        null
    }

    @Serializable
    private data class IndexedFile(val path: String)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
