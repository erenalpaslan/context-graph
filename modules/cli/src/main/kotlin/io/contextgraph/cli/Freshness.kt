package io.contextgraph.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.help
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.contextgraph.ingest.IndexStats
import io.contextgraph.ingest.ReindexPrimitive
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Slice 13: freshness triggers. Three thin wrappers -- [RefreshCommand] (CLI), [WatchCommand]
 * / [FileWatcher] (opt-in daemon), and [CiReindexCommand] (headless CI) -- over the single
 * reindex primitive, [ReindexPrimitive] (`modules:ingest`). None of them re-implement
 * extraction: they only decide *which db path* to write and *when* to call it. `IndexCommand`
 * in `Main.kt` and MCP's `contextgraph.index_project` tool (`modules:mcp-server`) go through
 * the same primitive, so there are five call sites and exactly one implementation.
 *
 * [ReindexPrimitive] moved out of this file (and out of `modules:cli` entirely) so
 * `modules:mcp-server` could call it too without a circular Gradle dependency -- see its KDoc
 * in `modules/ingest/src/main/kotlin/io/contextgraph/ingest/Reindex.kt` for why.
 */

/**
 * CLI trigger 1/3: explicit, always-available refresh of the current project. Writes the
 * local overlay only (`resolveWriteDbPath`, which never resolves to the committed baseline).
 * Re-parsing only changed files is not something this command does itself -- it falls out of
 * `IngestPipeline`'s own checksum comparison in pass 1, the same mechanism `index` already
 * relies on.
 */
class RefreshCommand : CliktCommand("refresh") {
    override fun help(context: Context) =
        "Re-parse changed files in the current project and bring the local graph in sync with the working tree"

    override fun run() {
        val root = projectRoot().toAbsolutePath().normalize()
        val config = loadConfig()
        echo("Refreshing $root...")
        val stats = ReindexPrimitive.run(root, config, resolveWriteDbPath())
        echo("Done: ${stats.artifactCount} artifacts, ${stats.nodeCount} nodes, ${stats.edgeCount} edges")
        if (stats.skipped > 0) echo("  Skipped (unchanged): ${stats.skipped}")
        if (stats.failed > 0) echo("  Failed: ${stats.failed}")
        if (stats.parseWarnings > 0) {
            echo("  Parse warnings: ${stats.parseWarnings} file(s) had syntax errors (partial extraction; see logs)")
        }
    }
}

/**
 * CLI trigger 2/3: the opt-in watcher daemon. Refuses to start unless `watcher.enabled` is
 * `true` in `.contextgraph/config.json` -- the watcher never runs by default, and no other
 * command reaches [FileWatcher] at all, so it can never start as a side effect of anything
 * else. Runs in the foreground until interrupted (Ctrl-C / SIGTERM), writing the local
 * overlay only, same as [RefreshCommand].
 */
class WatchCommand : CliktCommand("watch") {
    override fun help(context: Context) =
        "Watch the working tree and keep the local graph current as files change. " +
            "Opt-in: only starts when watcher.enabled=true in .contextgraph/config.json; " +
            "otherwise exits immediately without starting any background process."

    override fun run() {
        val config = loadConfig()
        if (!config.watcher.enabled) {
            echo(
                "Watcher is disabled (watcher.enabled=false in .contextgraph/config.json). " +
                    "Enable it with 'contextgraph config set watcher.enabled true' to use this command. Not starting."
            )
            return
        }
        val root = projectRoot().toAbsolutePath().normalize()
        val dbPath = resolveWriteDbPath()
        echo(
            "Watching $root (debounce ${config.watcher.debounceMillis}ms, " +
                "fallback rescan every ${config.watcher.fallbackIntervalMillis}ms). Ctrl-C to stop."
        )
        val watcher = FileWatcher(
            root = root,
            config = config,
            dbPath = dbPath,
            debounceMillis = config.watcher.debounceMillis,
            fallbackIntervalMillis = config.watcher.fallbackIntervalMillis
        )
        Runtime.getRuntime().addShutdownHook(Thread { watcher.stop() })
        watcher.run { stats ->
            echo("Reindexed: ${stats.artifactCount} artifacts, ${stats.nodeCount} nodes, ${stats.edgeCount} edges")
            if (stats.parseWarnings > 0) {
                echo("  Parse warnings: ${stats.parseWarnings} file(s) had syntax errors (partial extraction; see logs)")
            }
        }
    }
}

/**
 * The watcher's actual loop, factored out of [WatchCommand] so it can be started, driven,
 * and stopped directly in tests without going through a CLI process. Every trigger it fires
 * calls the exact same [ReindexPrimitive] as [RefreshCommand] and [CiReindexCommand].
 *
 * Handles the three failure modes called out in the task:
 *  - **Atomic saves** (write-temp-then-rename) look like a delete plus a create. This
 *    watcher does not try to interpret event kinds at all -- any event on a watched
 *    directory just marks a reindex pending. What actually changed is discovered by
 *    [ReindexPrimitive] -> `IngestPipeline`'s own checksum comparison, which is agnostic to
 *    how the change reached disk.
 *  - **Branch switches** touching thousands of files at once debounce into one reindex:
 *    [pendingSince] is set on the *first* event of a burst, and the reindex fires once
 *    [debounceMillis] after that, regardless of how many further events arrive in between.
 *  - **Dropped watch registrations** are covered by [fallbackIntervalMillis]: a full rescan
 *    fires on that schedule even with zero filesystem events, which is cheap because it
 *    goes through the same checksum-skip path as everything else -- a no-op tick costs a
 *    directory walk and some hashing, not a re-parse.
 */
internal class FileWatcher(
    private val root: Path,
    private val config: ContextGraphConfig,
    private val dbPath: Path,
    private val debounceMillis: Long,
    private val fallbackIntervalMillis: Long
) {
    private val stopped = AtomicBoolean(false)

    fun stop() {
        stopped.set(true)
    }

    /** Runs until [stop] is called (or the thread is interrupted). Fires [onReindexed] once per triggered reindex. */
    fun run(onReindexed: (IndexStats) -> Unit = {}) {
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            val keys = mutableMapOf<WatchKey, Path>()
            registerAll(root, watchService, keys)

            var pendingSince = -1L
            var lastRunAt = System.currentTimeMillis()

            while (!stopped.get() && !Thread.currentThread().isInterrupted) {
                val pollTimeout = (if (pendingSince >= 0) debounceMillis else fallbackIntervalMillis).coerceAtLeast(20)
                val key = try {
                    watchService.poll(pollTimeout, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }

                if (key != null) {
                    val watchedDir = keys[key]
                    if (watchedDir != null) {
                        for (event in key.pollEvents()) {
                            registerIfNewDirectory(event, watchedDir, watchService, keys)
                        }
                    } else {
                        key.pollEvents()
                    }
                    val stillValid = key.reset()
                    if (!stillValid) keys.remove(key)
                    if (pendingSince < 0) pendingSince = System.currentTimeMillis()
                }

                val now = System.currentTimeMillis()
                val debounceElapsed = pendingSince >= 0 && (now - pendingSince) >= debounceMillis
                val fallbackDue = (now - lastRunAt) >= fallbackIntervalMillis
                if (debounceElapsed || fallbackDue) {
                    val stats = ReindexPrimitive.run(root, config, dbPath)
                    onReindexed(stats)
                    pendingSince = -1
                    lastRunAt = System.currentTimeMillis()
                }
            }
        } finally {
            watchService.close()
        }
    }

    private fun registerIfNewDirectory(
        event: WatchEvent<*>,
        watchedDir: Path,
        watchService: WatchService,
        keys: MutableMap<WatchKey, Path>
    ) {
        val kind = event.kind()
        if (kind == StandardWatchEventKinds.OVERFLOW) return
        val context = event.context() as? Path ?: return
        val child = watchedDir.resolve(context)
        if (!shouldSkipDir(child) && runCatching { Files.isDirectory(child) }.getOrDefault(false)) {
            registerAll(child, watchService, keys)
        }
    }

    private fun registerAll(dir: Path, watchService: WatchService, keys: MutableMap<WatchKey, Path>) {
        runCatching {
            Files.walk(dir).use { stream ->
                stream
                    .filter { runCatching { Files.isDirectory(it) }.getOrDefault(false) && !shouldSkipDir(it) }
                    .forEach { d ->
                        runCatching {
                            val key = d.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE
                            )
                            keys[key] = d
                        }
                    }
            }
        }
    }

    private fun shouldSkipDir(dir: Path): Boolean {
        val name = dir.fileName?.toString() ?: return false
        return name in SKIP_DIR_NAMES
    }

    companion object {
        private val SKIP_DIR_NAMES = setOf(
            ".git", "node_modules", "build", ".gradle", ".contextgraph", "target", "__pycache__", ".kotlin", ".idea"
        )
    }
}

/**
 * Pure predicate behind [CiReindexCommand]'s refusal to run on an interactive terminal,
 * pulled out so it is testable without faking `System.console()`. CI runners have no
 * controlling TTY, so [hasConsole] is false there and the command proceeds; a developer's
 * own terminal has one, so it is refused there unless explicitly forced via
 * `CONTEXTGRAPH_CI=true` -- the enforcement AC-28 and the task notes ask for, in code rather
 * than only in documentation.
 */
internal fun ciWriteAllowed(hasConsole: Boolean, forceEnv: String?): Boolean =
    !hasConsole || forceEnv?.equals("true", ignoreCase = true) == true

/** Compact (single-line) JSON, deliberately separate from `Main.kt`'s pretty-printed `json` -- CI log tooling expects one machine-readable line per event, not a multi-line block. */
private val compactJson = kotlinx.serialization.json.Json { encodeDefaults = true }

@Serializable
internal data class CiReindexResult(
    val status: String,
    val artifacts: Int = 0,
    val nodes: Int = 0,
    val edges: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val parseWarnings: Int = 0,
    val message: String? = null
)

/**
 * CLI trigger 3/3: the CI entry point (invoked by `scripts/ci-reindex.sh`). This is the
 * **only** place in this trigger set that resolves to [GraphDb.baseline] for a write --
 * [RefreshCommand] and [WatchCommand] both go through `resolveWriteDbPath`
 * (`GraphDb.forLocalWrite`), which is structurally incapable of returning the baseline path.
 * That, plus the TTY refusal below, is what keeps "only CI writes the committed baseline"
 * enforced in code, not just documented.
 *
 * LiteLLM is force-disabled regardless of what is committed in `.contextgraph/config.json`,
 * so this path never needs credentials -- keyless CI is a hard requirement, not a nicety.
 * Output is a single line of JSON on stdout for machine-readable CI logs; human-readable
 * progress goes to stderr.
 */
class CiReindexCommand : CliktCommand("ci-reindex") {
    override fun help(context: Context) =
        "CI-only: reindex from scratch and write the COMMITTED baseline (.contextgraph/graph.db). " +
            "Refuses to run when an interactive terminal is attached (set CONTEXTGRAPH_CI=true to override), " +
            "which keeps the baseline single-writer. Always runs with LiteLLM disabled."

    private val path by argument().help("Directory to index").default(".")

    override fun run() {
        val hasConsole = System.console() != null
        if (!ciWriteAllowed(hasConsole, System.getenv("CONTEXTGRAPH_CI"))) {
            printJson(
                CiReindexResult(
                    status = "refused",
                    message = "ci-reindex writes the committed baseline and only runs headless (no TTY). " +
                        "Run it in CI, or set CONTEXTGRAPH_CI=true to override."
                ),
                err = true
            )
            throw ProgramResult(1)
        }

        val root = Path.of(path).toAbsolutePath().normalize()
        // CI must never require LLM credentials: force litellm off regardless of committed config.
        val config = loadConfig().let { it.copy(litellm = it.litellm.copy(enabled = false)) }
        val dbPath = GraphDb.baseline(root)

        try {
            val stats = ReindexPrimitive.run(root, config, dbPath)
            printJson(
                CiReindexResult(
                    status = "ok",
                    artifacts = stats.artifactCount,
                    nodes = stats.nodeCount,
                    edges = stats.edgeCount,
                    skipped = stats.skipped,
                    failed = stats.failed,
                    parseWarnings = stats.parseWarnings
                )
            )
        } catch (e: Exception) {
            printJson(CiReindexResult(status = "error", message = e.message ?: e.toString()), err = true)
            throw ProgramResult(1)
        }
    }

    private fun printJson(result: CiReindexResult, err: Boolean = false) {
        echo(compactJson.encodeToString(CiReindexResult.serializer(), result), err = err)
    }
}
