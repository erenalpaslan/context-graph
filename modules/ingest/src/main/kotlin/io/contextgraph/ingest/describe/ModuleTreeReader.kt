package io.contextgraph.ingest.describe

import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.DetectedModule
import io.contextgraph.ingest.ModuleSource
import io.contextgraph.ingest.ModuleTree
import kotlinx.serialization.json.JsonPrimitive

/**
 * Rebuilds the [ModuleTree] `describe-modules` needs from whatever `CodeModule` nodes are
 * already sitting in the graph -- the inverse of [ModuleTree.toGraphNodes]. describe-modules is
 * a separate, later invocation from indexing (that's the whole point: no LLM calls on the
 * indexing path), so it can never hold a live [io.contextgraph.ingest.ModuleDetector] result and
 * must instead read the tree back out of storage the way every other consumer does.
 */
object ModuleTreeReader {
    fun read(storage: StorageAdapter): ModuleTree {
        val modules = storage.getAllNodes()
            .filter { it.type == NodeType.CodeModule }
            .map { node ->
                val path = (node.properties["path"] as? JsonPrimitive)?.content.orEmpty()
                val buildSystem = (node.properties["buildSystem"] as? JsonPrimitive)?.content
                val parentId = (node.properties["parentId"] as? JsonPrimitive)?.content
                DetectedModule(
                    id = node.id,
                    name = node.label,
                    path = path,
                    parentId = parentId?.let { NodeId(it) },
                    source = moduleSourceOf(buildSystem)
                )
            }
        return ModuleTree(modules)
    }

    private fun moduleSourceOf(buildSystem: String?): ModuleSource = when (buildSystem) {
        "gradle" -> ModuleSource.GRADLE
        "npm" -> ModuleSource.NPM
        "python" -> ModuleSource.PYTHON
        else -> ModuleSource.CONFIG
    }
}
