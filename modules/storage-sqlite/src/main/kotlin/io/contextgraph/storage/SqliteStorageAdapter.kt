package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ArtifactWriteBatch
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

/** Entity ids per `IN (...)`, comfortably under SQLite's 999-parameter default. */
private const val PROVENANCE_LOOKUP_CHUNK = 500

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
    val receiverType = text("receiver_type").nullable()
    val receiverCall = text("receiver_call").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * How many nodes share one `INSERT OR REPLACE INTO nodes_fts` statement.
 *
 * `nodes_fts` is an FTS5 virtual table, which Exposed cannot describe, so its rows go in as
 * literal SQL text rather than through a prepared statement -- and one statement per node was
 * a fifth of the ingest profile spent in `sqlite3_prepare`. Grouping is the whole fix.
 * Bounded rather than unbounded because SQLite caps a compound VALUES list at
 * SQLITE_MAX_COMPOUND_SELECT (500 by default) and a statement at SQLITE_MAX_SQL_LENGTH; 250
 * rows of three short columns sits comfortably under both while cutting prepares 250-fold.
 */
private const val FTS_CHUNK_SIZE = 250

/**
 * How many rows one prepared batch statement carries.
 *
 * A JDBC batch holds every row's bound arguments in memory until it executes, so an
 * unbounded batch turns a large edge set into a large heap allocation: pass 2 rebuilds
 * 286,106 `Calls` edges on Keycloak, whose ids average 279 characters because an id is the
 * concatenation of two declaration-site ids. Chunking bounds that without giving up anything
 * -- the chunks run inside the caller's single transaction, so this is still one commit, and
 * the prepared statement is still reused across every row of a chunk.
 */
private const val BULK_CHUNK_SIZE = 10_000

/**
 * The rarity guard: a segment reaching more than this fraction of every distinct name in
 * `name_segment_vocab` contributes no candidates to a search. A fixed fraction of the corpus's
 * own total, computed from this project's vocabulary (cached per adapter instance -- see
 * `cachedTotalVocabNames` -- and invalidated on every write) -- not a word list.
 *
 * **What this measurably is: a high-frequency domain-word guard, not a stopword substitute.**
 * Measured on excalidraw's built index (4,764 distinct names, so the 5% cut sits at 238 names
 * reached): this guard suppresses `element` (reaching 359 names), `get` (343) and `2` (269) --
 * three genuinely high-frequency identifier sub-words, each well past the 238 cut. It does
 * **not** suppress the project's own name, `excalidraw` (199 -- under the cut), nor any of the
 * ordinary English words a natural-language question tends to contain: `is` (163), `to` (155),
 * `a` (136), `on` (112), `from` (73), `the` (24), `how` (4), `does` (2). Every one of those is
 * rare *as an identifier sub-word* on this corpus, however common it is in English prose, and a
 * query containing it is harmless for that reason -- not because this guard caught it. Do not
 * read this constant as a stopword list's replacement; it catches a handful of genuinely
 * overrepresented domain words and nothing resembling general English stopwords, and a caller
 * relying on it to suppress a project's own name in particular should not.
 */
private const val SEGMENT_RARITY_MAX_FRACTION = 0.05

class SqliteStorageAdapter(private val dbPath: Path) : StorageAdapter {
    private val jdbcUrl: String

    /**
     * Cached `COUNT(DISTINCT name)` from `name_segment_vocab`, read by [totalVocabNames].
     *
     * That count is a full-table scan regardless of any index -- `COUNT(DISTINCT ...)` needs
     * every row, primary key or not -- and recomputing it on every [segmentCandidates] call
     * measured at ~960 ms at Keycloak scale (600K rows), the single largest share of that
     * function's cost. It changes only when [writeSegmentVocab] writes, which invalidates this
     * unconditionally (an `INSERT OR IGNORE` batch may or may not add a name the count has not
     * seen before, and recomputing once on the next read is far cheaper than risking a stale
     * rarity denominator). `@Volatile` for cross-thread visibility only; a redundant recompute
     * from a rare concurrent miss is harmless, so no further synchronisation is needed.
     */
    @Volatile private var cachedTotalVocabNames: Long? = null

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
        val propsJson = encodeProperties(node.properties)

        NodesTable.upsert {
            it[id] = node.id.value
            it[type] = NodeType.stringify(node.type)
            it[label] = node.label
            it[properties] = propsJson
            it[confidence] = node.confidence
        }

        try {
            exec("INSERT OR REPLACE INTO nodes_fts(id, label, properties) VALUES ('${sqlQuote(node.id.value)}', '${sqlQuote(ftsLabelFor(node.label))}', '${sqlQuote(propsJson)}')")
        } catch (_: Exception) {}

        writeSegmentVocab(listOf(node))
    }

    override fun upsertEdge(edge: GraphEdge): Unit = transaction {
        EdgesTable.upsert {
            it[id] = edge.id.value
            it[sourceId] = edge.source.value
            it[targetId] = edge.target.value
            it[type] = EdgeType.stringify(edge.type)
            it[properties] = encodeProperties(edge.properties)
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

    // --- Bulk verbs ---------------------------------------------------------------------
    //
    // The per-row methods above each open their own `transaction { }`, and Exposed's
    // `Database.connect(url, driver)` hands every top-level transaction a brand-new JDBC
    // connection. So one row cost: open a SQLite connection, prepare a statement, step it,
    // commit (an fsync, in the rollback-journal mode this database is in), close the
    // connection. A flight recording of one excalidraw ingest found 41% of samples in
    // `NativeDB.step`, 21% in `NativeDB.prepare_utf8`, and 19% in `_open_utf8`/`_close` --
    // that last fifth is connection churn doing no work at all.
    //
    // These overrides change what a write costs, not what it means. One transaction -- so
    // one connection, one commit -- and one prepared statement re-bound per row instead of
    // one per row. Nested `transaction { }` calls inside join the outer one rather than
    // starting their own (Exposed's default, `useNestedTransactions = false`), so the
    // per-row methods above remain correct when reached from in here.

    override fun writeArtifactBatch(batch: ArtifactWriteBatch): Unit = transaction {
        if (batch.clearExisting) {
            deleteNodesForArtifact(batch.artifact.id)
            deleteUnresolvedReferencesForArtifact(batch.artifact.id)
        }
        upsertArtifact(batch.artifact)
        upsertNodes(batch.nodes)
        upsertEdges(batch.edges)

        batch.references.chunked(BULK_CHUNK_SIZE).forEach { references ->
            UnresolvedReferencesTable.batchInsert(references, shouldReturnGeneratedValues = false) { reference ->
                this[UnresolvedReferencesTable.artifactId] = reference.artifactId.value
                this[UnresolvedReferencesTable.repoRelativePath] = reference.repoRelativePath
                this[UnresolvedReferencesTable.referenceName] = reference.referenceName
                this[UnresolvedReferencesTable.referringSymbolId] = reference.referringSymbolId.value
                this[UnresolvedReferencesTable.line] = reference.line
                this[UnresolvedReferencesTable.receiverType] = reference.receiverType
                this[UnresolvedReferencesTable.receiverCall] = reference.receiverCall
            }
        }

        // Flattened to (nodeId, provenance) pairs so every artifact's provenance is one
        // statement rather than one per node -- the per-row path's nested forEach produced
        // the same rows in the same order, which is what keeps this substitutable.
        val provenanceRows = batch.nodes.flatMap { node -> node.provenance.map { node.id.value to it } }
        provenanceRows.chunked(BULK_CHUNK_SIZE).forEach { rows ->
            ProvenanceTable.batchInsert(rows, shouldReturnGeneratedValues = false) { (nodeId, p) ->
                this[ProvenanceTable.entityId] = nodeId
                this[ProvenanceTable.entityKind] = "node"
                this[ProvenanceTable.artifactId] = p.artifactId.value
                this[ProvenanceTable.path] = p.path
                this[ProvenanceTable.lineStart] = p.lineStart
                this[ProvenanceTable.lineEnd] = p.lineEnd
                this[ProvenanceTable.page] = p.page
                this[ProvenanceTable.textSpan] = p.textSpan
                this[ProvenanceTable.extractor] = p.extractor
                this[ProvenanceTable.extractedAt] = p.extractedAt.toEpochMilliseconds()
            }
        }
    }

    override fun upsertNodes(nodes: Collection<GraphNode>) {
        if (nodes.isEmpty()) return
        transaction {
            val propsById = nodes.associate { it.id.value to encodeProperties(it.properties) }

            nodes.chunked(BULK_CHUNK_SIZE).forEach { chunk ->
                NodesTable.batchUpsert(chunk, NodesTable.id, shouldReturnGeneratedValues = false) { node ->
                    this[NodesTable.id] = node.id.value
                    this[NodesTable.type] = NodeType.stringify(node.type)
                    this[NodesTable.label] = node.label
                    this[NodesTable.properties] = propsById.getValue(node.id.value)
                    this[NodesTable.confidence] = node.confidence
                }
            }

            // Same rows, same escaping, same INSERT OR REPLACE as the single-node path --
            // only grouped, so one prepare serves a whole chunk instead of one per node.
            nodes.chunked(FTS_CHUNK_SIZE).forEach { chunk ->
                val values = chunk.joinToString(",") { node ->
                    "('${sqlQuote(node.id.value)}','${sqlQuote(ftsLabelFor(node.label))}','${sqlQuote(propsById.getValue(node.id.value))}')"
                }
                try {
                    exec("INSERT OR REPLACE INTO nodes_fts(id, label, properties) VALUES $values")
                } catch (_: Exception) {}
            }

            writeSegmentVocab(nodes)
        }
    }

    override fun upsertEdges(edges: Collection<GraphEdge>) {
        if (edges.isEmpty()) return
        transaction {
            edges.chunked(BULK_CHUNK_SIZE).forEach { chunk ->
                EdgesTable.batchUpsert(chunk, EdgesTable.id, shouldReturnGeneratedValues = false) { edge ->
                    this[EdgesTable.id] = edge.id.value
                    this[EdgesTable.sourceId] = edge.source.value
                    this[EdgesTable.targetId] = edge.target.value
                    this[EdgesTable.type] = EdgeType.stringify(edge.type)
                    this[EdgesTable.properties] = encodeProperties(edge.properties)
                    this[EdgesTable.confidence] = edge.confidence
                }
            }
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
                    // `.distinct()` (keeps first occurrence, i.e. the highest-ranked one) is a
                    // read-path guard against a write-path defect this change does not fix:
                    // nodes_fts is FTS5 with `id UNINDEXED`, so `INSERT OR REPLACE` above has no
                    // unique index to target a conflict on and every re-upsert of a node appends
                    // a second search row rather than replacing the first. Left undeduplicated,
                    // a node written twice can occupy two (or more) of this query's LIMIT slots
                    // by itself, at the direct expense of a distinct node that would otherwise
                    // have been found -- a defect in what gets returned, not just a wasted slot,
                    // since `limit` bounds the row count fetched *before* any de-duplication runs.
                    // Deliberately separable from that write-path defect (still open, see
                    // docs/identifier-segment-vocabulary.md section 6): this only changes how the
                    // rows already returned are consumed.
                    ids.distinct()
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

        // Segment-vocabulary candidates are appended after every full-text hit, never
        // interleaved. `results` above is already in MATCH rank order, and QueryRelevance.of
        // turns a candidate's position in this returned list directly into its seed score, so
        // appending means a segment candidate can only add a slot at the tail -- it can never
        // demote anything full-text search already found.
        //
        // Conservative bound: a segment candidate can only fill a short result, never grow one
        // that is already full -- `budget` is what is missing from a full page of `limit`, zero
        // once that page is already full. The full-text query ORs every query word, so it
        // frequently returns a full page on its own; when it does, this line makes the
        // vocabulary a deliberate no-op here rather than a source of hidden growth. A wider,
        // fixed-size budget that keeps proposing candidates even past a full page was measured
        // as its own code state (arm "A2-growth": budget widened from `limit - results.size` to
        // a flat 5) and changed nothing -- not MRR, not R@5, not R@10, across 5 cold cycles,
        // identical to this conservative bound to four decimal places (see
        // docs/identifier-segment-vocabulary.md, "The A2-growth null"). The rarity guard and the
        // re-verification join were the binding constraints, not the budget shape, so the
        // conservative bound is what ships: it is the one that can only help or do nothing,
        // never one that grows a result for no measured benefit.
        val segmentBudget = (limit - results.size).coerceAtLeast(0)
        val segmentResults = segmentCandidates(
            terms = terms,
            excludeIds = results.mapTo(mutableSetOf()) { it.id.value },
            budget = segmentBudget,
            types = types,
            minConfidence = minConfidence
        )

        results + segmentResults
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

    override fun getProvenanceFor(entityIds: Collection<String>): Map<String, List<Provenance>> = transaction {
        // Chunked because SQLite's default SQLITE_MAX_VARIABLE_NUMBER caps a single IN list, and a
        // ranking candidate set is unbounded from this layer's point of view.
        entityIds.distinct()
            .chunked(PROVENANCE_LOOKUP_CHUNK)
            .flatMap { chunk ->
                ProvenanceTable.selectAll()
                    .where { ProvenanceTable.entityId inList chunk }
                    .map { it[ProvenanceTable.entityId] to it.toProvenance() }
            }
            .groupBy({ it.first }, { it.second })
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
            it[receiverType] = reference.receiverType
            it[receiverCall] = reference.receiverCall
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
                line = it[UnresolvedReferencesTable.line],
                receiverType = it[UnresolvedReferencesTable.receiverType],
                receiverCall = it[UnresolvedReferencesTable.receiverCall]
            )
        }
    }

    override fun deleteEdgesOfType(type: EdgeType): Unit = transaction {
        val typeString = EdgeType.stringify(type)
        EdgesTable.deleteWhere { EdgesTable.type eq typeString }
    }

    /**
     * A node or edge's `properties` map as the JSON text the column holds, `{}` when it
     * cannot be encoded.
     *
     * Extracted so the single-row and bulk paths cannot drift: whatever one writes into
     * `properties`, the other writes the same bytes, which is what makes the bulk verbs a
     * substitution rather than a second implementation.
     */
    private fun encodeProperties(properties: Map<String, JsonElement>): String = try {
        jsonSerializer.encodeToString(kotlinx.serialization.serializer<Map<String, JsonElement>>(), properties)
    } catch (_: Exception) { "{}" }

    /**
     * What goes in `nodes_fts.label` for a node labelled [label].
     *
     * `nodes_fts` is a search index, not a source of truth -- the real label lives in
     * `nodes`, untouched. FTS5's default tokenizer only splits on non-alphanumeric
     * characters, so a compound identifier with no separator (camelCase, acronym runs,
     * digit-adjacent words -- e.g. "RungDistribution") is indexed as a single token and
     * cannot be found by any of its component words. Appending the split components
     * alongside the original lets a search for "rung" or "distribution" retrieve
     * "RungDistribution" without changing what is displayed or stored as truth.
     */
    private fun ftsLabelFor(label: String): String {
        val words = IdentifierSplitter.split(label)
        return if (words.size > 1) (listOf(label) + words).joinToString(" ") else label
    }

    /**
     * Writes one `name_segment_vocab` row per distinct sub-word of each of [nodes]' labels,
     * lower-cased in Kotlin before it is written, skipping file nodes ([NodeType.isFileType])
     * entirely. Both `upsertNode` and `upsertNodes` funnel through this single function, so the
     * single-node and bulk write paths are provably the same write rather than two
     * implementations that happen to agree today.
     *
     * Lower-cased *here*, in Kotlin, rather than compared case-insensitively in SQL
     * (`COLLATE NOCASE` or a `LOWER(segment)` wrapper at query time): `segment` is never read
     * back out as text -- it appears only in `WHERE`/`GROUP BY` -- so folding it once at write
     * time is lossless, and it is what lets [segmentCandidates] compare with a plain
     * `segment IN (...)` instead of wrapping the indexed column in a function call that stops
     * SQLite from using the primary key (see that function's own comment). It is also the only
     * fold that is symmetric with the query side: Kotlin's `String.lowercase()` is full Unicode,
     * but SQLite's built-in `LOWER()` folds ASCII `A`-`Z` only -- so comparing a Kotlin-lowered
     * query term against a `LOWER(segment)`-wrapped column left any non-ASCII uppercase segment
     * (Cyrillic, Greek, a Latin letter with a diacritic outside `A`-`Z`, ...) permanently
     * unreachable no matter how the query was cased. Folding at write time closes that gap by
     * construction, since both sides then go through the same fold. A narrow side effect worth
     * naming: the fold runs *before* the `.distinct()` below, so a label whose split happens to
     * produce two segments that are case-variants of one another (e.g. an identifier containing
     * the same word once in an acronym run and once lower-cased) now collapses to one row for
     * that label instead of two -- a small reduction in row count, on top of the primary
     * motivation above (searchability and correctness, not size).
     *
     * A label with no internal boundary still gets a row: [IdentifierSplitter.split] returns
     * such a label unchanged as the sole element of its result, so that becomes the label's
     * one segment (itself, lower-cased) rather than the label being silently skipped.
     *
     * `INSERT OR IGNORE` against the table's real `(segment, name)` primary key (see
     * `V6__name_segment_vocab.sql`) is what makes re-running this over an unchanged project
     * leave the row count identical -- unlike `nodes_fts`'s `INSERT OR REPLACE` above, which
     * never replaces because FTS5 gives that table no unique index for the conflict clause to
     * target.
     *
     * A `Transaction` extension, like `exec` itself, so it can only be called from inside an
     * open `transaction { }` block -- the same one already wrapping every call site below --
     * rather than opening (and committing) one of its own.
     */
    private fun Transaction.writeSegmentVocab(nodes: Collection<GraphNode>) {
        val rows = nodes.asSequence()
            .filter { !NodeType.isFileType(it.type) }
            .flatMap { node ->
                IdentifierSplitter.split(node.label).asSequence()
                    .map { it.lowercase() }
                    .distinct()
                    .map { it to node.label }
            }
            .toList()
        if (rows.isEmpty()) return

        // This write is about to (maybe) add a name totalVocabNames() has not counted yet --
        // invalidate unconditionally rather than trying to detect whether INSERT OR IGNORE
        // below actually added a new one; the next read recomputes once, which is far cheaper
        // than a search silently working from a stale rarity denominator.
        cachedTotalVocabNames = null

        // Chunked at the same size and for the same reason as the nodes_fts writes above --
        // SQLite treats a multi-row VALUES list as a compound SELECT, capped by
        // SQLITE_MAX_COMPOUND_SELECT (500 by default). Chunking by row (not by node) keeps
        // this safe even for the rare identifier that splits into many segments, since a
        // node-based chunk size would only bound row count if every node produced exactly
        // one row, which nodes_fts's writes do and this one does not.
        rows.chunked(FTS_CHUNK_SIZE).forEach { chunk ->
            val values = chunk.joinToString(",") { (segment, name) ->
                "('${sqlQuote(segment)}','${sqlQuote(name)}')"
            }
            try {
                exec("INSERT OR IGNORE INTO name_segment_vocab(segment, name) VALUES $values")
            } catch (e: Exception) {
                // Logged rather than swallowed, for the same reason the FTS MATCH catch in
                // searchNodes stopped being bare: a silent catch here would make a genuine
                // failure -- e.g. V6 not applied, a malformed statement -- indistinguishable
                // from "no segments in this chunk", and this table is the one this run's
                // verdict depends on. A silently-empty vocabulary must never be mistaken for an
                // honest null result.
                logger.warn(e) { "name_segment_vocab write failed for a chunk of ${chunk.size} row(s); first pair='${chunk.firstOrNull()}'" }
            }
        }
    }

    /**
     * `COUNT(DISTINCT name)` in `name_segment_vocab`, cached on [cachedTotalVocabNames] and
     * invalidated by every [writeSegmentVocab] write. See that field's own comment for why: the
     * query is a full scan no matter what, so the cache is what keeps [segmentCandidates] from
     * paying for it on every call.
     *
     * This counts every distinct `name` the table holds, including orphans left behind by a
     * node a later index removed ([writeSegmentVocab]'s row-count property, `V6__name_segment_vocab.sql`'s
     * "rows are proposals" note) -- there is no join back to `nodes` here, unlike
     * [segmentCandidates]'s own re-verification join. The rarity guard's denominator therefore
     * grows monotonically as a project is edited and re-indexed over time, even for names whose
     * only node was deleted; a segment's reach as a *fraction* of this count can only shrink
     * relative to what it would be against a denominator of live names alone, making the guard
     * strictly more permissive (never less) as orphans accumulate. Left uncorrected deliberately:
     * joining against `nodes` here would turn a cached full-table-scan cost into an uncached
     * full-table-*join* cost on every cache miss, for a correction that only ever loosens the
     * guard, never tightens it past what was already measured.
     */
    private fun Transaction.totalVocabNames(): Long {
        cachedTotalVocabNames?.let { return it }
        val n = exec("SELECT COUNT(DISTINCT name) AS n FROM name_segment_vocab") { rs ->
            if (rs.next()) rs.getLong("n") else 0L
        } ?: 0L
        cachedTotalVocabNames = n
        return n
    }

    /**
     * Segment-vocabulary candidates for [terms] that are not already present in [excludeIds], up
     * to [budget] of them -- the read half of the identifier segment vocabulary, re-verified
     * against live nodes, appended strictly after full-text hits, and suppressed by a rarity
     * guard. Called from inside [searchNodes]'s own `transaction { }`, like [writeSegmentVocab].
     *
     * Every candidate comes out of exactly one join from `name_segment_vocab` to `nodes` on
     * `label = name`. Re-verifying that a vocabulary row still names a live node is not a check
     * bolted onto the result afterwards -- it *is* this join: a row naming an identifier no
     * longer carried by any node (the node was removed by a later index; `V6__name_segment_vocab.sql`
     * explains why the orphaned row is deliberately left behind rather than swept) simply has no
     * join partner in `nodes` and contributes nothing. There is no separate existence check to
     * forget.
     *
     * The rarity guard ([SEGMENT_RARITY_MAX_FRACTION]) is computed here, from this corpus's own
     * vocabulary (via [totalVocabNames]) -- a segment reaching more than that fraction of every
     * distinct name in the table is suppressed before it ever reaches the join above, so it
     * costs nothing and proposes nothing.
     *
     * `terms` is expected already extracted via [ftsTerms] -- the same discipline [searchNodes]
     * uses to keep the FTS MATCH expression safe applies unchanged here, since every term is
     * already guaranteed free of anything that could break out of a quoted SQL literal.
     */
    private fun Transaction.segmentCandidates(
        terms: List<String>,
        excludeIds: Set<String>,
        budget: Int,
        types: List<NodeType>,
        minConfidence: Double
    ): List<GraphNode> {
        if (budget <= 0 || terms.isEmpty()) return emptyList()

        val totalNames = totalVocabNames()
        if (totalNames <= 0L) return emptyList()

        // Segments are stored lower-cased in Kotlin at write time (writeSegmentVocab), precisely
        // so this comparison never has to wrap the *column* in SQLite's own LOWER() -- which
        // folds ASCII A-Z only, unlike Kotlin's String.lowercase(), which is full Unicode.
        // Lower-casing the term here and comparing with a plain `segment IN (...)` keeps the
        // fold symmetric with the write side and, as a direct consequence, leaves `segment`
        // unwrapped so SQLite can search it by its primary key instead of scanning the whole
        // table into a temp b-tree (EXPLAIN QUERY PLAN, before: SCAN name_segment_vocab USING
        // TEMP B-TREE; after: SEARCH v USING PRIMARY KEY -- see
        // docs/identifier-segment-vocabulary.md).
        val lowerTerms = terms.map { it.lowercase() }.distinct()
        val termList = sqlInList(lowerTerms)

        // One grouped query for every term's reach at once, rather than one query per term: how
        // many distinct names each candidate segment reaches.
        val namesReached: Map<String, Long> = exec(
            "SELECT segment AS seg, COUNT(DISTINCT name) AS cnt FROM name_segment_vocab " +
                "WHERE segment IN ($termList) GROUP BY segment"
        ) { rs ->
            val m = mutableMapOf<String, Long>()
            while (rs.next()) m[rs.getString("seg")] = rs.getLong("cnt")
            m
        } ?: emptyMap()

        val acceptedTerms = lowerTerms.filter { term ->
            val reached = namesReached[term] ?: 0L
            reached > 0L && reached.toDouble() / totalNames <= SEGMENT_RARITY_MAX_FRACTION
        }
        if (acceptedTerms.isEmpty()) return emptyList()

        val acceptedList = sqlInList(acceptedTerms)
        val typeClause = if (types.isEmpty()) "" else
            " AND n.type IN (${sqlInList(types.map { NodeType.stringify(it) })})"
        val excludeClause = if (excludeIds.isEmpty()) "" else
            " AND n.id NOT IN (${sqlInList(excludeIds)})"

        // minConfidence is a caller-supplied Double, not a value this function derives from the
        // corpus -- interpolating it into raw SQL text the way the ids/types/segments above are
        // (all of them this function's own strings, already sanitised by sqlQuote) risks NaN or
        // Infinity rendering as a bare, unquoted identifier and throwing a SQL parse error out of
        // this unguarded exec. Unlike the FTS MATCH path above, which is wrapped in a try/catch
        // and logs on failure, that would surface as an uncaught exception out of searchNodes
        // itself. Applying the filter in the Exposed re-fetch below instead -- which already
        // re-reads every row of this query's result -- avoids the raw-SQL risk entirely, at the
        // cost of a candidate below minConfidence occupying a budget slot here that a passing
        // one could otherwise have used -- minConfidence defaults to 0.0 for every caller today,
        // so in practice this branch filters nothing.
        //
        // ORDER BY n.id is an arbitrary but deterministic tie-break, kept deliberately rather
        // than by omission: a first pass of this fix ordered by count of accepted segments
        // matched, descending, then id -- correctness-wise a stronger proposal, since a
        // candidate matching three accepted segments is more likely relevant than one matching
        // a single one -- but measuring it end to end (five cold cycles, excalidraw's 9
        // questions, see docs/identifier-segment-vocabulary.md) moved this arm's R@5 and R@10
        // below the cold baseline (MRR 0.5000, R@5 0.3148, R@10 0.3426 against baseline's
        // 0.4815/0.3426/0.3704) -- a real regression, not noise: all 5 cycles agreed exactly,
        // and the baseline's own recorded noise band never touches R@10. `ORDER BY n.id` is
        // what A2 originally shipped and measured at MRR 0.5556/R@5 0.3981/R@10 0.4259, so it is
        // kept -- this fix's job is SEARCH-vs-SCAN and the Unicode fold, not a ranking change,
        // and this codebase does not ship an unmeasured ranking change on the strength of an
        // untested intuition alone (see the "Do not touch QueryRelevance" guardrail this sits
        // beside).
        val ids = exec(
            "SELECT DISTINCT n.id FROM name_segment_vocab v " +
                "JOIN nodes n ON n.label = v.name " +
                "WHERE v.segment IN ($acceptedList)" +
                typeClause + excludeClause +
                " ORDER BY n.id LIMIT $budget"
        ) { rs ->
            val out = mutableListOf<String>()
            while (rs.next()) out.add(rs.getString("id"))
            out
        } ?: emptyList()
        if (ids.isEmpty()) return emptyList()

        // Same fetch-by-id-list shape as the FTS re-verification above: the join already proved
        // these ids are live nodes; minConfidence is applied here (see the comment above) rather
        // than in the raw SQL, and this Exposed query already re-reads every row regardless.
        val byId = NodesTable.selectAll().where {
            (NodesTable.id inList ids) and (NodesTable.confidence greaterEq minConfidence)
        }.associateBy({ it[NodesTable.id] }, { it.toGraphNode() })
        // `inList` does not preserve the order ids were supplied in -- re-impose it from `ids`
        // (already in the SQL's own ORDER BY n.id order) rather than trusting Exposed's row
        // order, the same reason the FTS branch above re-imposes order from `ftsResults`.
        return ids.mapNotNull { byId[it] }
    }

    /** SQL single-quote escaping, for the FTS statements that are built as literal text. */
    private fun sqlQuote(value: String): String = value.replace("'", "''")

    /**
     * `'a','b','c'` -- a quoted, comma-joined `IN (...)` list body, for the raw-SQL statements
     * built as literal text. [segmentCandidates] alone needed this shape four times (terms,
     * accepted segments, type names, excluded ids); extracted here so there is exactly one place
     * that decides how a `Collection<String>` becomes SQL list syntax.
     */
    private fun sqlInList(values: Collection<String>): String = values.joinToString(",") { "'${sqlQuote(it)}'" }

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
