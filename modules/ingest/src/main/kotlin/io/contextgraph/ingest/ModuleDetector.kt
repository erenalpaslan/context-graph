package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Where a module boundary came from. Purely informational — carried onto the node. */
enum class ModuleSource {
    GRADLE, NPM, PYTHON, CONFIG
}

/**
 * One detected module boundary. [path] is repo-relative, forward-slash separated, with no
 * leading or trailing slash; the empty string denotes the repo root itself.
 */
data class DetectedModule(
    val id: NodeId,
    val name: String,
    val path: String,
    val parentId: NodeId?,
    val source: ModuleSource
) {
    val buildSystem: String get() = when (source) {
        ModuleSource.GRADLE -> "gradle"
        ModuleSource.NPM -> "npm"
        ModuleSource.PYTHON -> "python"
        ModuleSource.CONFIG -> "config"
    }
}

/**
 * The module tree for one repo: a flat list of [DetectedModule]s plus the lookups slice 15
 * needs to roll a per-file symbol inventory up into a per-module one.
 */
class ModuleTree(val modules: List<DetectedModule>) {
    private val byPath = modules.associateBy { it.path }
    private val byId = modules.associateBy { it.id }

    fun children(module: DetectedModule): List<DetectedModule> =
        modules.filter { it.parentId == module.id }

    /**
     * The most specific module owning a repo-relative file path (forward-slash separated,
     * no leading slash), or null if the file sits outside every declared module boundary.
     *
     * A root module (`path == ""`) has no genuine directory prefix to test containment against,
     * so historically it fell back to claiming every file no other module matched. That is only
     * correct when the root module actually IS the whole repo — i.e. every other module is its
     * descendant. The moment an unrelated top-level module exists alongside it (a config-declared
     * root next to detected Gradle modules, say — see [buildTree]'s comment for why that's a
     * legitimate shape), the root's true boundary is unknown, so it must stop catching orphan
     * files rather than guess they're its own. Same discipline [buildTree] already applies to
     * parentage, applied here to file ownership: use the parent relationship [buildTree] actually
     * established, not a blanket rule keyed off the source path being empty.
     */
    fun ownerOf(relativeFilePath: String): DetectedModule? {
        val normalized = relativeFilePath.trimStart('/')
        val hasUnrelatedTopLevelModules = modules.any { it.path.isNotEmpty() && it.parentId == null }
        return modules
            .filter { m ->
                if (m.path.isEmpty()) !hasUnrelatedTopLevelModules
                else normalized == m.path || normalized.startsWith("${m.path}/")
            }
            .maxByOrNull { it.path.length }
    }

    /** Groups [files] (repo-relative, forward-slash paths) by owning module. Unowned files are dropped. */
    fun filesByModule(files: Collection<String>): Map<DetectedModule, List<String>> =
        files.mapNotNull { f -> ownerOf(f)?.let { it to f } }
            .groupBy({ it.first }, { it.second })

    fun toGraphNodes(): List<GraphNode> = modules.map { m ->
        GraphNode(
            id = m.id,
            type = NodeType.CodeModule,
            label = m.name,
            properties = mapOf(
                "path" to JsonPrimitive(m.path),
                "buildSystem" to JsonPrimitive(m.buildSystem),
                "parentId" to (m.parentId?.let { JsonPrimitive(it.value) } ?: JsonNull)
            )
        )
    }
}

/**
 * Detects module boundaries from build files: Gradle `include(...)`, npm/yarn `workspaces`,
 * and Python `pyproject.toml`. `.contextgraph/config.json`-declared roots override detection
 * for matching paths and add anything detection cannot see (iOS via `.pbxproj` chief among
 * them — that format is never parsed).
 *
 * No tree-sitter, no network, no writes: this is a pure read of build-file text into a
 * [ModuleTree] that later slices consume.
 */
class ModuleDetector {

    fun detect(root: Path, config: ContextGraphConfig): ModuleTree {
        val detected = detectGradle(root) + detectNpm(root) + detectPython(root)
        val merged = mergeWithConfig(detected, config)
        // Deterministic output order: identity (NodeId) is already stable per-path, but the
        // list order itself must not depend on filesystem iteration or JSON object ordering.
        return ModuleTree(buildTree(merged).sortedBy { it.path })
    }

    private fun detectGradle(root: Path): List<RawModule> {
        val settingsFile = listOf("settings.gradle.kts", "settings.gradle")
            .map { root.resolve(it) }
            .firstOrNull { it.exists() } ?: return emptyList()

        val text = settingsFile.readText()
        val parenIncludes = INCLUDE_CALL.findAll(text)
            .flatMap { call -> GRADLE_PATH.findAll(call.groupValues[1]).map { it.groupValues[1] } }
        val noParenIncludes = INCLUDE_LINE_NO_PARENS.findAll(text)
            .flatMap { call -> GRADLE_PATH.findAll(call.groupValues[1]).map { it.groupValues[1] } }
        val includePaths = (parenIncludes + noParenIncludes).toList()

        return includePaths.map { gradlePath ->
            val relPath = gradlePath.removePrefix(":").replace(":", "/")
            RawModule(path = relPath, name = gradlePath.substringAfterLast(":"), source = ModuleSource.GRADLE)
        }
    }

    private fun detectNpm(root: Path): List<RawModule> {
        val packageJson = root.resolve("package.json")
        if (!packageJson.exists()) return emptyList()

        val patterns = try {
            val element = Json.parseToJsonElement(packageJson.readText()).jsonObject
            val workspaces = element["workspaces"] ?: return emptyList()
            when {
                workspaces is kotlinx.serialization.json.JsonArray -> workspaces.jsonArray.map { it.jsonPrimitive.content }
                else -> workspaces.jsonObject["packages"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return patterns.flatMap { pattern -> resolveNpmGlob(root, pattern) }
    }

    private fun resolveNpmGlob(root: Path, pattern: String): List<RawModule> {
        val candidateDirs = if (pattern.endsWith("/*")) {
            val parent = root.resolve(pattern.removeSuffix("/*"))
            if (!parent.exists() || !parent.isDirectory()) emptyList()
            else parent.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        } else {
            listOf(root.resolve(pattern))
        }

        return candidateDirs
            .filter { it.resolve("package.json").exists() }
            .map { dir ->
                val relPath = root.relativize(dir).toString().replace('\\', '/')
                val pkgName = try {
                    Json.parseToJsonElement(dir.resolve("package.json").readText())
                        .jsonObject["name"]?.jsonPrimitive?.content
                } catch (_: Exception) { null }
                RawModule(path = relPath, name = pkgName ?: dir.name, source = ModuleSource.NPM)
            }
    }

    /**
     * Python has no single canonical workspace manifest the way Gradle/npm do, so this walks
     * the tree for `pyproject.toml` files (skipping noise directories) and treats each one that
     * declares a project name (PEP 621 `[project]` or Poetry's `[tool.poetry]`) as a module
     * rooted at its own directory.
     *
     * Nesting is resolved here, not in [buildTree]: this walk is the one place that actually
     * knows which `pyproject.toml` sits inside which — including a root one at `""` — so each
     * module's parent is computed as the nearest ancestor path this same walk also found, and
     * carried forward explicitly via [RawModule.explicitParentPath]. That keeps [buildTree]'s
     * general rule to plain, source-agnostic directory-prefix containment: a bare `path == ""`
     * is never treated as a universal parent there, which matters once boundaries from other
     * sources (a config-declared root chief among them) can also land at the repo root.
     */
    private fun detectPython(root: Path): List<RawModule> {
        val declared = findPyProjectFiles(root).mapNotNull { file ->
            val text = try { file.readText() } catch (_: Exception) { return@mapNotNull null }
            val name = PEP621_NAME.find(text)?.groupValues?.get(1)
                ?: POETRY_NAME.find(text)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val dir = file.parent
            val relPath = root.relativize(dir).toString().replace('\\', '/')
            relPath to name
        }
        val declaredPaths = declared.map { it.first }.toSet()
        return declared.map { (path, name) ->
            val parentPath = declaredPaths
                .filter { candidate -> candidate != path && (path.startsWith("$candidate/") || candidate.isEmpty()) }
                .maxByOrNull { it.length }
            RawModule(path = path, name = name, source = ModuleSource.PYTHON, explicitParentPath = parentPath)
        }
    }

    private fun findPyProjectFiles(root: Path): List<Path> {
        val results = mutableListOf<Path>()
        fun walk(dir: Path) {
            val entries = try { dir.listDirectoryEntries() } catch (_: Exception) { return }
            for (entry in entries.sortedBy { it.name }) {
                when {
                    entry.isDirectory() && entry.name !in EXCLUDED_DIR_NAMES -> walk(entry)
                    entry.isRegularFile() && entry.name == "pyproject.toml" -> results.add(entry)
                }
            }
        }
        if (root.exists() && root.isDirectory()) walk(root)
        return results
    }

    private fun mergeWithConfig(detected: List<RawModule>, config: ContextGraphConfig): List<RawModule> {
        if (config.moduleRoots.isEmpty()) return detected
        val configured = config.moduleRoots.map {
            RawModule(
                path = it.path.trim('/'),
                name = it.name ?: it.path.trim('/').substringAfterLast("/"),
                source = ModuleSource.CONFIG
            )
        }
        val configuredPaths = configured.map { it.path }.toSet()
        return detected.filterNot { it.path in configuredPaths } + configured
    }

    /**
     * Assigns parents using only genuine directory-prefix containment (`b` is inside `a` when
     * `b == "$a/..."`), plus whatever explicit parent a detector already worked out for itself
     * (see [detectPython]). Deliberately does **not** treat a bare `path == ""` as an implicit
     * universal parent: multiple unrelated boundaries — a config-declared root next to detected
     * Gradle modules, say — can all legitimately sit at the repo root without nesting into one
     * another. A detector that has actually observed real nesting says so explicitly instead of
     * leaving this generic pass to guess it from a source tag.
     */
    private fun buildTree(raw: List<RawModule>): List<DetectedModule> {
        val sorted = raw.distinctBy { it.path }.sortedBy { it.path.length }
        val resolved = mutableListOf<DetectedModule>()
        val byPath = mutableMapOf<String, DetectedModule>()
        for (m in sorted) {
            val parent = m.explicitParentPath?.let { byPath[it] }
                ?: resolved
                    .filter { candidate -> m.path != candidate.path && m.path.startsWith("${candidate.path}/") }
                    .maxByOrNull { it.path.length }
            val node = DetectedModule(
                id = NodeId("module:${m.path}"),
                name = m.name,
                path = m.path,
                parentId = parent?.id,
                source = m.source
            )
            resolved.add(node)
            byPath[m.path] = node
        }
        return resolved
    }

    private data class RawModule(
        val path: String,
        val name: String,
        val source: ModuleSource,
        /** Set only by a detector that itself observed real nesting (see [detectPython]). */
        val explicitParentPath: String? = null
    )

    companion object {
        private val INCLUDE_CALL = Regex("include\\s*\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
        // Groovy allows the parens to be omitted: `include ':app', ':core'`. Line-scoped so it
        // never double-matches the parenthesized form above (which has no space before `(`).
        private val INCLUDE_LINE_NO_PARENS = Regex(
            "(?m)^\\s*include\\s+([\"'][^\"']+[\"'](?:\\s*,\\s*[\"'][^\"']+[\"'])*)\\s*$"
        )
        private val GRADLE_PATH = Regex("[\"'](:[A-Za-z0-9_.\\-:]*)[\"']")

        private val PEP621_NAME = Regex(
            "\\[project](?:(?!\\n\\[)[\\s\\S])*?\\bname\\s*=\\s*[\"']([^\"']+)[\"']"
        )
        private val POETRY_NAME = Regex(
            "\\[tool\\.poetry](?:(?!\\n\\[)[\\s\\S])*?\\bname\\s*=\\s*[\"']([^\"']+)[\"']"
        )

        private val EXCLUDED_DIR_NAMES = setOf(
            ".git", "node_modules", ".venv", "venv", "__pycache__", "build", "dist",
            ".tox", ".gradle", ".idea", "target", "site-packages", ".contextgraph"
        )
    }
}
