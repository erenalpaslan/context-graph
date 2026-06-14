package io.contextgraph.extractors

import io.contextgraph.core.Artifact
import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.ResourceExtractor
import kotlinx.datetime.Clock
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex
import java.nio.file.Path
import kotlin.io.path.readText

class SqlExtractor : ResourceExtractor {
    override val id = "sql"
    override val supportedTypes = setOf(NodeType.DatabaseSchema)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()
        val sql = Path.of(artifact.path).readText()

        val schemaNode = GraphNode(
            id = NodeId("schema:${artifact.id.value}"),
            type = NodeType.DatabaseSchema,
            label = Path.of(artifact.path).fileName.toString(),
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
        )
        nodes.add(schemaNode)

        try {
            val statements = CCJSqlParserUtil.parseStatements(sql)
            val tableNodes = mutableMapOf<String, GraphNode>()

            statements.statements.forEach { stmt ->
                if (stmt is CreateTable) {
                    val tableName = stmt.table.name
                    val tableId = NodeId("table:${artifact.id.value}:$tableName")
                    val tableNode = GraphNode(
                        id = tableId,
                        type = NodeType.DatabaseTable,
                        label = tableName,
                        confidence = ConfidenceDefaults.SQL_FOREIGN_KEY,
                        provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = "CREATE TABLE $tableName", extractor = id, extractedAt = now))
                    )
                    nodes.add(tableNode)
                    tableNodes[tableName.lowercase()] = tableNode
                    edges.add(GraphEdge(
                        id = EdgeId("contains:${schemaNode.id.value}:${tableId.value}"),
                        source = schemaNode.id, target = tableId, type = EdgeType.Contains, confidence = 1.0
                    ))

                    stmt.columnDefinitions?.forEach { col ->
                        val colId = NodeId("column:${artifact.id.value}:$tableName:${col.columnName}")
                        nodes.add(GraphNode(
                            id = colId,
                            type = NodeType.Column,
                            label = col.columnName,
                            confidence = ConfidenceDefaults.SQL_FOREIGN_KEY,
                            provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = col.columnName, extractor = id, extractedAt = now))
                        ))
                        edges.add(GraphEdge(
                            id = EdgeId("contains:${tableId.value}:${colId.value}"),
                            source = tableId, target = colId, type = EdgeType.Contains, confidence = 1.0
                        ))
                    }

                    stmt.indexes?.forEach { idx ->
                        if (idx is ForeignKeyIndex) {
                            val refTable = idx.table?.name
                            if (refTable != null) {
                                val refId = NodeId("table:${artifact.id.value}:$refTable")
                                edges.add(GraphEdge(
                                    id = EdgeId("fk:${tableId.value}:${refId.value}"),
                                    source = tableId, target = refId, type = EdgeType.DependsOn, confidence = ConfidenceDefaults.SQL_FOREIGN_KEY
                                ))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to regex-based extraction if JSQLParser fails
            extractWithRegex(artifact, schemaNode, sql, nodes, edges, now)
        }

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun extractWithRegex(
        artifact: Artifact, schemaNode: GraphNode, sql: String,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        now: kotlinx.datetime.Instant
    ) {
        val createTableRegex = Regex("""CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"]?(\w+)[`"]?""", RegexOption.IGNORE_CASE)
        createTableRegex.findAll(sql).forEach { m ->
            val tableName = m.groupValues[1]
            val tableId = NodeId("table:${artifact.id.value}:$tableName")
            nodes.add(GraphNode(id = tableId, type = NodeType.DatabaseTable, label = tableName, confidence = 0.85,
                provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = m.value, extractor = id, extractedAt = now))))
            edges.add(GraphEdge(id = EdgeId("contains:${schemaNode.id.value}:${tableId.value}"),
                source = schemaNode.id, target = tableId, type = EdgeType.Contains, confidence = 0.85))
        }
    }
}
