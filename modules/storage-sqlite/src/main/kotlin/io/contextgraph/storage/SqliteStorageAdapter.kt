package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.core.EdgeType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.io.path.createDirectories

private val logger = KotlinLogging.logger {}

private val jsonSerializer = Json { encodeDefaults = true; ignoreUnknownKeys = true }

object ArtifactsTable : Table("artifacts") {
    val id = text("id")
    val type = text("type")
    val path = text("path")
    val checksum = text("checksum")
    val size = long("size")
    val lastModified = long("last_modified")
    val indexedAt = long("indexed_at")
    override val primaryKey = PrimaryKey(id)
}

object NodesTable : Table("nodes") {
    val id = text("id")
    val type = text("type")
    val label = text("label")
    val properties = text("properties").default("{}")
    val confidence = double("confidence").default(1.0)
    override val primaryKey = PrimaryKey(id)
}

object EdgesTable : Table("edges") {
    val id = text("id")
    val sourceId = text("source_id")
    val targetId = text("target_id")
    val type = text("type")
    val properties = text("properties").default("{}")
    val confidence = double("confidence").default(1.0)
    override val primaryKey = PrimaryKey(id)
}

object ProvenanceTable : Table("provenance") {
    val id = integer("id").autoIncrement()
    val entityId = text("entity_id")
    val entityKind = text("entity_kind")
    val artifactId = text("artifact_id")
    val path = text("path")
    val lineStart = integer("line_start").nullable()
    val lineEnd = integer("line_end").nullable()
    val page = integer("page").nullable()
    val textSpan = text("text_span").nullable()
    val extractor = text("extractor")
    val extractedAt = long("extracted_at")
    override val primaryKey = PrimaryKey(id)
}

object NodeArtifactsTable : Table("node_artifacts") {
    val nodeId = text("node_id")
    val artifactId = text("artifact_id")
    override val primaryKey = PrimaryKey(nodeId, artifactId)
}

class SqliteStorageAdapter(private val dbPath: Path) : StorageAdapter {
    private val jdbcUrl: String

    init {
        dbPath.parent?.let { Files.createDirectories(it) }
        jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        runMigrations()
        Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    }

    private fun runMigrations() {
        Flyway.configure()
            .dataSource(jdbcUrl, "", "")
            .locations("classpath:db/migration")
            .load()
            .migrate()
        logger.info { "Database migrations applied at $dbPath" }
    }

    override fun upsertArtifact(artifact: Artifact): Unit = transaction {
        ArtifactsTable.upsert {
            it[id] = artifact.id.value
            it[type] = NodeType.stringify(artifact.type)
            it[path] = artifact.path
            it[checksum] = artifact.checksum
            it[size] = artifact.size
            it[lastModified] = artifact.lastModified.toEpochMilliseconds()
            it[indexedAt] = artifact.indexedAt.toEpochMilliseconds()
        }
    }

    override fun getArtifact(id: ArtifactId): Artifact? = transaction {
        ArtifactsTable.selectAll().where { ArtifactsTable.id eq id.value }.firstOrNull()?.toArtifact()
    }

    override fun deleteNodesForArtifact(artifactId: ArtifactId): Unit = transaction {
        val nodeIds = NodeArtifactsTable
            .selectAll().where { NodeArtifactsTable.artifactId eq artifactId.value }
            .map { it[NodeArtifactsTable.nodeId] }

        nodeIds.forEach { nodeId ->
            EdgesTable.deleteWhere { sourceId eq nodeId }
            EdgesTable.deleteWhere { targetId eq nodeId }
            ProvenanceTable.deleteWhere { entityId eq nodeId }
            NodesTable.deleteWhere { NodesTable.id eq nodeId }
        }
        NodeArtifactsTable.deleteWhere { NodeArtifactsTable.artifactId eq artifactId.value }
        ProvenanceTable.deleteWhere { ProvenanceTable.artifactId eq artifactId.value }
    }

    override fun upsertNode(node: GraphNode): Unit = transaction {
        val propsJson = try {
            jsonSerializer.encodeToString(
                kotlinx.serialization.serializer<Map<String, JsonElement>>(),
                node.properties
            )
        } catch (_: Exception) { "{}" }

        NodesTable.upsert {
            it[id] = node.id.value
            it[type] = NodeType.stringify(node.type)
            it[label] = node.label
            it[properties] = propsJson
            it[confidence] = node.confidence
        }

        // Update FTS
        try {
            exec("INSERT OR REPLACE INTO nodes_fts(id, label, properties) VALUES ('${node.id.value.replace("'", "''")}', '${node.label.replace("'", "''")}', '${propsJson.replace("'", "''")}')")
        } catch (_: Exception) {}
    }

    override fun upsertEdge(edge: GraphEdge): Unit = transaction {
        val propsJson = try {
            jsonSerializer.encodeToString(
                kotlinx.serialization.serializer<Map<String, JsonElement>>(),
                edge.properties
            )
        } catch (_: Exception) { "{}" }

        EdgesTable.upsert {
            it[id] = edge.id.value
            it[sourceId] = edge.source.value
            it[targetId] = edge.target.value
            it[type] = EdgeType.stringify(edge.type)
            it[properties] = propsJson
            it[confidence] = edge.confidence
        }
    }

    override fun upsertProvenance(entityId: String, entityKind: String, provenance: Provenance): Unit = transaction {
        ProvenanceTable.insert {
            it[ProvenanceTable.entityId] = entityId
            it[ProvenanceTable.entityKind] = entityKind
            it[artifactId] = provenance.artifactId.value
            it[path] = provenance.path
            it[lineStart] = provenance.lineStart
            it[lineEnd] = provenance.lineEnd
            it[page] = provenance.page
            it[textSpan] = provenance.textSpan
            it[extractor] = provenance.extractor
            it[extractedAt] = provenance.extractedAt.toEpochMilliseconds()
        }
    }

    override fun searchNodes(query: String, types: List<NodeType>, minConfidence: Double, limit: Int): List<GraphNode> = transaction {
        if (query.isBlank()) {
            var q = NodesTable.selectAll().where { NodesTable.confidence greaterEq minConfidence }
            if (types.isNotEmpty()) {
                val typeStrings = types.map { NodeType.stringify(it) }
                q = q.andWhere { NodesTable.type inList typeStrings }
            }
            return@transaction q.limit(limit).map { it.toGraphNode() }
        }

        // FTS search
        val ftsResults = try {
            exec("SELECT id FROM nodes_fts WHERE nodes_fts MATCH '${query.replace("'", "''")}' LIMIT $limit") { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) ids.add(rs.getString("id"))
                ids
            } ?: emptyList()
        } catch (_: Exception) {
            // Fallback to LIKE
            emptyList<String>()
        }

        val results = if (ftsResults.isNotEmpty()) {
            var q = NodesTable.selectAll().where {
                (NodesTable.id inList ftsResults) and (NodesTable.confidence greaterEq minConfidence)
            }
            if (types.isNotEmpty()) {
                val typeStrings = types.map { NodeType.stringify(it) }
                q = q.andWhere { NodesTable.type inList typeStrings }
            }
            q.limit(limit).map { it.toGraphNode() }
        } else {
            // LIKE fallback
            var q = NodesTable.selectAll().where {
                (NodesTable.label like "%$query%") and (NodesTable.confidence greaterEq minConfidence)
            }
            if (types.isNotEmpty()) {
                val typeStrings = types.map { NodeType.stringify(it) }
                q = q.andWhere { NodesTable.type inList typeStrings }
            }
            q.limit(limit).map { it.toGraphNode() }
        }

        results
    }

    override fun getNode(id: NodeId): GraphNode? = transaction {
        NodesTable.selectAll().where { NodesTable.id eq id.value }.firstOrNull()?.toGraphNode()
    }

    override fun getEdgesFrom(source: NodeId): List<GraphEdge> = transaction {
        EdgesTable.selectAll().where { EdgesTable.sourceId eq source.value }.map { it.toGraphEdge() }
    }

    override fun getEdgesTo(target: NodeId): List<GraphEdge> = transaction {
        EdgesTable.selectAll().where { EdgesTable.targetId eq target.value }.map { it.toGraphEdge() }
    }

    override fun getProvenance(entityId: String): List<Provenance> = transaction {
        ProvenanceTable.selectAll().where { ProvenanceTable.entityId eq entityId }.map { it.toProvenance() }
    }

    override fun getAllNodes(minConfidence: Double): List<GraphNode> = transaction {
        NodesTable.selectAll().where { NodesTable.confidence greaterEq minConfidence }.map { it.toGraphNode() }
    }

    override fun getAllEdges(minConfidence: Double): List<GraphEdge> = transaction {
        EdgesTable.selectAll().where { EdgesTable.confidence greaterEq minConfidence }.map { it.toGraphEdge() }
    }

    override fun getAllArtifacts(): List<Artifact> = transaction {
        ArtifactsTable.selectAll().map { it.toArtifact() }
    }

    override fun getStats(): GraphStats = transaction {
        GraphStats(
            artifactCount = ArtifactsTable.selectAll().count().toInt(),
            nodeCount = NodesTable.selectAll().count().toInt(),
            edgeCount = EdgesTable.selectAll().count().toInt()
        )
    }

    override fun close() {}

    private fun ResultRow.toArtifact() = Artifact(
        id = ArtifactId(this[ArtifactsTable.id]),
        type = NodeType.fromString(this[ArtifactsTable.type]),
        path = this[ArtifactsTable.path],
        checksum = this[ArtifactsTable.checksum],
        size = this[ArtifactsTable.size],
        lastModified = Instant.fromEpochMilliseconds(this[ArtifactsTable.lastModified]),
        indexedAt = Instant.fromEpochMilliseconds(this[ArtifactsTable.indexedAt])
    )

    private fun ResultRow.toGraphNode(): GraphNode {
        val propsJson = this[NodesTable.properties]
        val props = try {
            jsonSerializer.decodeFromString<Map<String, JsonElement>>(propsJson)
        } catch (_: Exception) { emptyMap() }
        return GraphNode(
            id = NodeId(this[NodesTable.id]),
            type = NodeType.fromString(this[NodesTable.type]),
            label = this[NodesTable.label],
            properties = props,
            confidence = this[NodesTable.confidence]
        )
    }

    private fun ResultRow.toGraphEdge(): GraphEdge {
        val propsJson = this[EdgesTable.properties]
        val props = try {
            jsonSerializer.decodeFromString<Map<String, JsonElement>>(propsJson)
        } catch (_: Exception) { emptyMap() }
        return GraphEdge(
            id = io.contextgraph.core.EdgeId(this[EdgesTable.id]),
            source = NodeId(this[EdgesTable.sourceId]),
            target = NodeId(this[EdgesTable.targetId]),
            type = EdgeType.fromString(this[EdgesTable.type]),
            properties = props,
            confidence = this[EdgesTable.confidence]
        )
    }

    private fun ResultRow.toProvenance() = Provenance(
        artifactId = ArtifactId(this[ProvenanceTable.artifactId]),
        path = this[ProvenanceTable.path],
        lineStart = this[ProvenanceTable.lineStart],
        lineEnd = this[ProvenanceTable.lineEnd],
        page = this[ProvenanceTable.page],
        textSpan = this[ProvenanceTable.textSpan],
        extractor = this[ProvenanceTable.extractor],
        extractedAt = Instant.fromEpochMilliseconds(this[ProvenanceTable.extractedAt])
    )
}
