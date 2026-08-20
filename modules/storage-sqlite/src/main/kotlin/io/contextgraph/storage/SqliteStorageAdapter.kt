package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.IdentifierSplitter
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.core.EdgeType
import io.contextgraph.core.UnresolvedReference
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.io.path.createDirectories

private val logger = KotlinLogging.logger {}

private val jsonSerializer = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private val FTS_TOKEN_REGEX = Regex("[\\p{L}\\p{N}_]+")

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

object UnresolvedReferencesTable : Table("unresolved_references") {
    val id = integer("id").autoIncrement()
    val artifactId = text("artifact_id")
    val repoRelativePath = text("repo_relative_path")
    val referenceName = text("reference_name")
    val referringSymbolId = text("referring_symbol_id")
    val line = integer("line")
    override val primaryKey = PrimaryKey(id)
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
        // NodeArtifactsTable is never populated; derive node IDs from ProvenanceTable instead.
        val nodeIds = ProvenanceTable
            .selectAll().where { ProvenanceTable.artifactId eq artifactId.value }
            .map { it[ProvenanceTable.entityId] }
            .distinct()

        // `calls` edges are pass 2's, not pass 1's: io.contextgraph.ingest.ReferenceResolver
        // owns their entire lifecycle, wiping and recomputing the complete set from the
        // current symbol table on every full index run. Deleting them here -- keyed on
        // *this* artifact's nodes -- would destroy edges *other* artifacts hold into this
        // one (e.g. another file's `Calls` edge to a method declared here) with no
        // mechanism to restore them, since the other artifact isn't being reprocessed. That
        // was the orphaning bug: a cross-artifact edge silently disappearing on an unrelated
        // reindex. Structural edges this file itself produced (Contains, Imports) are never
        // cross-artifact, so they are unaffected by excluding just this one type.
        val callsType = EdgeType.stringify(EdgeType.Calls)
        nodeIds.forEach { nodeId ->
            EdgesTable.deleteWhere { (sourceId eq nodeId) and (type neq callsType) }
            EdgesTable.deleteWhere { (targetId eq nodeId) and (type neq callsType) }
            ProvenanceTable.deleteWhere { entityId eq nodeId }
            NodesTable.deleteWhere { NodesTable.id eq nodeId }
        }
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

        // Update FTS. `nodes_fts` is a search index, not a source of truth -- the real label
        // lives in NodesTable above, untouched. FTS5's default tokenizer only splits on
        // non-alphanumeric characters, so a compound identifier with no separator (camelCase,
        // acronym runs, digit-adjacent words -- e.g. "RungDistribution") is indexed as a single
        // token and cannot be found by any of its component words. Appending the split
        // components alongside the original label lets a search for "rung" or "distribution"
        // retrieve "RungDistribution" without changing what is displayed or stored as truth.
        val ftsWords = IdentifierSplitter.split(node.label)
        val ftsLabel = if (ftsWords.size > 1) {
            (listOf(node.label) + ftsWords).joinToString(" ")
        } else {
            node.label
        }
        try {
            exec("INSERT OR REPLACE INTO nodes_fts(id, label, properties) VALUES ('${node.id.value.replace("'", "''")}', '${ftsLabel.replace("'", "''")}', '${propsJson.replace("'", "''")}')")
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

        val terms = ftsTerms(query)

        // FTS search. `query` may be a whole natural-language sentence, not a single term --
        // feeding it to MATCH verbatim used to mean two things went wrong at once. First,
        // FTS5's default MATCH semantics are implicit AND across the whole string, so every
        // word in the sentence had to appear on the same indexed row: for a multi-word question
        // that is close to impossible, and it was silently producing zero results across the
        // board. Second, MATCH's query language treats quotes, '*', ':', '-', '(' etc. as
        // syntax, so any sentence containing one of those (a question mark alone is harmless,
        // but a hyphenated word or an apostrophe is not) could throw `fts5: syntax error`.
        // Splitting the query into individual word-terms and OR-ing each one, quoted, fixes
        // both: OR means matching *any* term is enough (recall a natural-language query can
        // realistically get), and a quoted phrase is always a literal string in FTS5's query
        // language, never an operator -- so no term extracted from user text can ever be
        // misparsed as syntax, regardless of what punctuation surrounded it in the original
        // sentence. A single-term query (the existing, working case) degenerates to a
        // single quoted phrase, which matches exactly as a bareword search did before.
        val ftsResults: List<String> = if (terms.isEmpty()) {
            emptyList()
        } else {
            val matchExpr = terms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
            try {
                // ORDER BY rank asks SQLite for its built-in bm25 relevance score, so a row
                // matching more of the OR'd terms (or matching them more distinctively) sorts
                // ahead of a row matching only one -- otherwise OR-ing terms together would grow
                // the result set without any way to tell a strong match from a weak one, which
                // matters because ranking quality (MRR) is measured, not just presence/absence.
                exec("SELECT id FROM nodes_fts WHERE nodes_fts MATCH '${matchExpr.replace("'", "''")}' ORDER BY rank LIMIT $limit") { rs ->
                    val ids = mutableListOf<String>()
                    while (rs.next()) ids.add(rs.getString("id"))
                    ids
                } ?: emptyList()
            } catch (e: Exception) {
                // This used to be a bare `catch (_: Exception) {}`, which made a genuine FTS5
                // syntax error indistinguishable from "no matches" -- silently falling through
                // to a LIKE fallback that (before this change) searched for the *entire original
                // sentence* as one substring and could therefore never match a real label either.
                // Logging here means a real MATCH failure is now visible instead of masquerading
                // as an empty result.
                logger.warn(e) { "FTS5 MATCH failed for query='$query' (expr='$matchExpr'); falling back to per-term LIKE scan" }
                emptyList()
            }
        }

        val results = if (ftsResults.isNotEmpty()) {
            var q = NodesTable.selectAll().where {
                (NodesTable.id inList ftsResults) and (NodesTable.confidence greaterEq minConfidence)
            }
            if (types.isNotEmpty()) {
                val typeStrings = types.map { NodeType.stringify(it) }
                q = q.andWhere { NodesTable.type inList typeStrings }
            }
            // `inList` does not preserve the MATCH query's rank order, so re-impose it from
            // ftsResults (already ordered by rank) rather than trusting row order out of Exposed.
            val byId = q.associateBy({ it[NodesTable.id] }, { it.toGraphNode() })
            ftsResults.mapNotNull { byId[it] }
        } else if (terms.isNotEmpty()) {
            // LIKE fallback, now OR-ing the same extracted terms rather than substring-matching
            // the whole original sentence -- a label is (almost) never a full sentence, so the
            // old fallback was empty by construction and gave the illusion of a safety net that
            // never actually caught anything.
            var q = NodesTable.selectAll().where {
                val termCond: Op<Boolean> = terms
                    .map<String, Op<Boolean>> { term -> NodesTable.label like "%$term%" }
                    .reduce { a, b -> a or b }
                termCond and (NodesTable.confidence greaterEq minConfidence)
            }
            if (types.isNotEmpty()) {
                val typeStrings = types.map { NodeType.stringify(it) }
                q = q.andWhere { NodesTable.type inList typeStrings }
            }
            q.limit(limit).map { it.toGraphNode() }
        } else {
            emptyList()
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

    override fun findNodesByLabel(label: String): List<GraphNode> = transaction {
        NodesTable.selectAll().where { NodesTable.label eq label }.map { it.toGraphNode() }
    }

    override fun insertUnresolvedReference(reference: UnresolvedReference): Unit = transaction {
        UnresolvedReferencesTable.insert {
            it[artifactId] = reference.artifactId.value
            it[repoRelativePath] = reference.repoRelativePath
            it[referenceName] = reference.referenceName
            it[referringSymbolId] = reference.referringSymbolId.value
            it[line] = reference.line
        }
    }

    override fun deleteUnresolvedReferencesForArtifact(artifactId: ArtifactId): Unit = transaction {
        UnresolvedReferencesTable.deleteWhere { UnresolvedReferencesTable.artifactId eq artifactId.value }
    }

    override fun getAllUnresolvedReferences(): List<UnresolvedReference> = transaction {
        UnresolvedReferencesTable.selectAll().map {
            UnresolvedReference(
                referenceName = it[UnresolvedReferencesTable.referenceName],
                referringSymbolId = NodeId(it[UnresolvedReferencesTable.referringSymbolId]),
                repoRelativePath = it[UnresolvedReferencesTable.repoRelativePath],
                artifactId = ArtifactId(it[UnresolvedReferencesTable.artifactId]),
                line = it[UnresolvedReferencesTable.line]
            )
        }
    }

    override fun deleteEdgesOfType(type: EdgeType): Unit = transaction {
        val typeString = EdgeType.stringify(type)
        EdgesTable.deleteWhere { EdgesTable.type eq typeString }
    }

    // Mirrors FTS5's own unicode61 tokenizer (split on non-alphanumeric, i.e. exactly the
    // characters that are also MATCH syntax) so every term this extracts is guaranteed free of
    // anything FTS5's query language could misparse -- quoting each one afterwards is then a
    // second, independent layer of the same guarantee rather than the only one.
    private fun ftsTerms(query: String): List<String> =
        FTS_TOKEN_REGEX.findAll(query).map { it.value }.filter { it.isNotBlank() }.distinct().toList()

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
