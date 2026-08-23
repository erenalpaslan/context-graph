package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Proves the read half of the identifier segment vocabulary: a prose query whose words are
 * sub-words of a compound identifier can reach that identifier through `name_segment_vocab`,
 * appended strictly after every full-text hit, suppressed by a corpus-derived rarity guard
 * rather than any word list, and re-verified against live `nodes` so a deleted node's orphaned
 * row surfaces nothing.
 *
 * Every "buried" fixture below deliberately hides its target behind a full-text-search LIMIT
 * cutoff: a decoy matching two of the query's OR'd terms robustly outranks -- under FTS5's
 * bm25, which sums a positive per-term contribution for every distinct term matched -- a genuine
 * match on only one, so a small `limit` excludes the target from the ordinary full-text branch
 * entirely. This is not test artifice for its own sake: it is the exact shape the slice's own
 * notes describe -- "a name covering three of the query's words scores barely better ... than a
 * name covering one." A test that let the plain FTS branch also happen to find the target would
 * not actually be exercising the code this slice adds, since `name_segment_vocab` is built from
 * the same `IdentifierSplitter` that already augments `nodes_fts.label` -- so whatever the
 * vocabulary could match, the FTS branch could in principle match too, *unless* it is bumped out
 * of the ranked window first.
 */
class SegmentCandidatesTest : FunSpec({

    fun now() = Clock.System.now()

    fun freshDbPath(name: String) = Files.createTempDirectory("segment-candidates-$name").resolve("graph.db")

    fun makeNode(id: String, label: String, type: NodeType = NodeType.Class, confidence: Double = 1.0) =
        GraphNode(NodeId(id), type, label, confidence = confidence)

    fun makeArtifact(id: String) = Artifact(
        id = ArtifactId(id),
        type = NodeType.CodeFile,
        path = id,
        checksum = "sum-$id",
        size = 10L,
        lastModified = now(),
        indexedAt = now()
    )

    /**
     * [n] unrelated filler nodes, so a segment's rarity fraction is computed against a
     * denominator large enough to be meaningful -- the same reason a real corpus's rarity guard
     * only removes a segment once it reaches dozens of shared names, not one or two out of a
     * two-node fixture.
     */
    fun addFiller(storage: SqliteStorageAdapter, n: Int, prefix: String) {
        (0 until n).forEach { i -> storage.upsertNode(makeNode("$prefix-filler-$i", "Filler${prefix}Word$i")) }
    }

    test("a buried compound-identifier match is appended strictly after every full-text hit") {
        val dbPath = freshDbPath("ac11")
        SqliteStorageAdapter(dbPath).use { storage ->
            // Two-term decoy, type Function -- robustly outranks any one-term match under bm25,
            // so it takes the sole full-text slot that also survives the type filter below.
            storage.upsertNode(makeNode("decoyA", "ZuluYankee", NodeType.Function))
            // Two-term decoy, type Class -- takes the *other* untyped top-2 slot (crowding the
            // target out of the raw MATCH ... LIMIT window), then is itself dropped by the type
            // filter, so it never appears in the final result at all.
            storage.upsertNode(makeNode("decoyB", "XrayWhiskey", NodeType.Class))
            // The buried target: matches only one of the query's terms ("checkout"), so it
            // ranks below both two-term decoys and falls outside the raw MATCH LIMIT.
            val target = makeNode("target", "CheckoutFlow", NodeType.Function)
            storage.upsertNode(target)
            addFiller(storage, 20, "ac11")

            val results = storage.searchNodes(
                "zulu yankee xray whiskey checkout",
                types = listOf(NodeType.Function),
                limit = 2
            )

            // decoyA is the one and only full-text hit (decoyB is type Class, filtered out;
            // target is bumped out of the raw MATCH LIMIT entirely). The vocabulary then finds
            // target via segment "checkout" and appends it -- strictly after, never interleaved.
            results.map { it.id } shouldBe listOf(NodeId("decoyA"), target.id)
        }
    }

    test("a segment reaching a large fraction of the vocabulary's names contributes no candidates, a rare one does") {
        val dbPath = freshDbPath("ac12")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNode(makeNode("decoyA", "AlphaBravo", NodeType.Function))
            storage.upsertNode(makeNode("decoyB", "CharlieDelta", NodeType.Class))
            val commonTarget = makeNode("commonTarget", "CommonMike", NodeType.Function)
            val rareTarget = makeNode("rareTarget", "RareNovember", NodeType.Function)
            storage.upsertNode(commonTarget)
            storage.upsertNode(rareTarget)
            // 19 more names sharing "Common" as a leading segment, so it reaches 20 of the
            // vocabulary's names -- a large fraction under any reasonable cut -- while "rare"
            // reaches exactly one name (rareTarget itself).
            (0 until 19).forEach { i -> storage.upsertNode(makeNode("commonFiller$i", "Common${'A' + i}Word", NodeType.Class)) }
            addFiller(storage, 3, "ac12")

            val results = storage.searchNodes(
                "alpha bravo charlie delta common rare",
                types = listOf(NodeType.Function),
                limit = 2
            )

            // decoyA is the sole full-text hit (same burial mechanism as the "appended after full-text"
            // test's fixture above:
            // decoyA/decoyB each match two OR'd terms and take the untyped top-2 window,
            // decoyB is filtered out by type). Both commonTarget and rareTarget match only one
            // term each and are bumped out of the raw MATCH LIMIT identically -- the only
            // difference between them is how common their one matching segment is.
            results.map { it.id } shouldBe listOf(NodeId("decoyA"), rareTarget.id)
            results.any { it.id == commonTarget.id } shouldBe false
        }
    }

    test("a vocabulary row naming an identifier no node carries any more surfaces nothing") {
        val dbPath = freshDbPath("ac9-read")
        SqliteStorageAdapter(dbPath).use { storage ->
            val target = GraphNode(
                id = NodeId("src/A.kt#QuebecTangoOrphan"),
                type = NodeType.Function,
                label = "QuebecTangoOrphan",
                provenance = listOf(
                    Provenance(
                        artifactId = ArtifactId("src/A.kt"),
                        path = "src/A.kt",
                        lineStart = 1,
                        lineEnd = 2,
                        page = null,
                        textSpan = null,
                        extractor = "test",
                        extractedAt = now()
                    )
                ),
                confidence = 1.0
            )
            storage.upsertArtifact(makeArtifact("src/A.kt"))
            storage.upsertNode(target)
            storage.upsertProvenance(target.id.value, "node", target.provenance.first())

            fun vocabRowCountForTarget(): Int =
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().executeQuery(
                        "SELECT COUNT(*) AS n FROM name_segment_vocab WHERE name = 'QuebecTangoOrphan'"
                    ).use { rs -> rs.next(); rs.getInt("n") }
                }

            vocabRowCountForTarget() shouldBe 3 // Quebec, Tango, Orphan

            storage.deleteNodesForArtifact(ArtifactId("src/A.kt"))
            storage.getNode(target.id) shouldBe null

            // Deliberately not swept (orphan rows are proposals, not truth -- see V6__name_segment_vocab.sql):
            // the row is still there after the node is gone.
            vocabRowCountForTarget() shouldBe 3

            // The re-verification join (name_segment_vocab -> nodes on label) has nothing to
            // join against any more, so the orphaned row surfaces nothing.
            storage.searchNodes("orphan").any { it.id == target.id } shouldBe false
        }
    }

    test("a non-ASCII uppercase segment is reachable through the vocabulary (Unicode-correct fold, not SQLite's ASCII-only LOWER())") {
        val dbPath = freshDbPath("unicode")
        SqliteStorageAdapter(dbPath).use { storage ->
            // The real node the vocabulary row must resolve to. Its own label is plain ASCII,
            // deliberately unrelated to the Cyrillic word below, so nothing here can accidentally
            // be found through full-text search (which would defeat the point of this test) or
            // through the tokenizer's own diacritic folding.
            val target = makeNode("target", "OrderState", NodeType.Class)
            storage.upsertNode(target)
            addFiller(storage, 30, "unicode")

            // IdentifierSplitter's own separator regex ([^A-Za-z0-9]+) treats every non-ASCII
            // character as a boundary and strips it, so no segment *split* from a real identifier
            // can ever contain one -- a separate, pre-existing limitation this fix does not touch.
            // Seeded directly here so this test isolates exactly what changed: segmentCandidates'
            // comparison. "статус" is what writeSegmentVocab would store for a segment "Статус"
            // under the fix (lower-cased in Kotlin, at write time) -- the invariant its own tests
            // in SegmentVocabularyTest cover; this test is the read side's half of the same proof.
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                conn.createStatement()
                    .execute("INSERT INTO name_segment_vocab(segment, name) VALUES ('статус', 'OrderState')")
            }

            // The query is cased the way a real prose question would write it -- capitalised.
            // ftsTerms extracts it whole (its regex is \p{L}\p{N}_, full Unicode) and
            // segmentCandidates then lower-cases it in Kotlin before comparing: "Статус"
            // (U+0421 CYRILLIC CAPITAL LETTER ES, ...) folds to "статус" under Kotlin's
            // String.lowercase(), which is full-Unicode. Before this fix, the comparison instead
            // wrapped the *column* in SQLite's built-in LOWER(), which folds ASCII A-Z only and
            // leaves a Cyrillic capital untouched -- so 'статус' (the query, lower-cased in
            // Kotlin) would never have equalled 'Статус' (the column, unfolded by SQL), and this
            // row would have stayed permanently unreachable no matter how the query was cased.
            val results = storage.searchNodes("Статус", limit = 5)

            results.any { it.id == target.id } shouldBe true
        }
    }

    test("conservative bound: a query the full-text page already fills is unchanged, additions withheld") {
        val dbPath = freshDbPath("conservative-bound")
        SqliteStorageAdapter(dbPath).use { storage ->
            // Two-term match: robustly outranks the target's one-term match, taking the sole
            // slot once `limit` is 1.
            val decoy = makeNode("decoy", "PapaQuebec", NodeType.Class)
            val target = makeNode("target", "RomeoWidget", NodeType.Class)
            storage.upsertNode(decoy)
            storage.upsertNode(target)
            addFiller(storage, 20, "budget")

            val full = storage.searchNodes("papa quebec romeo", limit = 1)

            // Budget = limit(1) - results.size(1) = 0, so the vocabulary is never even queried.
            // "romeo" would otherwise pass the rarity guard on its own (1 of 22 names, same
            // fixture shape as the "appended after full-text" test's buried target) -- the only reason it is absent
            // here is the conservative bound, not rarity and not the join.
            full.map { it.id } shouldBe listOf(decoy.id)
        }
    }
})

private fun <T> SqliteStorageAdapter.use(block: (SqliteStorageAdapter) -> T): T = try {
    block(this)
} finally {
    close()
}
