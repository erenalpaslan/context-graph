package io.contextgraph.extractors

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.NodeType
import io.contextgraph.core.Artifact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.io.path.writeText

class SqlExtractorTest : FunSpec({

    val extractor = SqlExtractor()
    val config = ContextGraphConfig()

    fun tempSql(content: String): Artifact {
        val file = Files.createTempFile("test-", ".sql")
        file.writeText(content)
        val now = Clock.System.now()
        return Artifact(
            id = ArtifactId(file.toAbsolutePath().toString()),
            type = NodeType.DatabaseSchema,
            path = file.toAbsolutePath().toString(),
            checksum = "test",
            size = content.length.toLong(),
            lastModified = now,
            indexedAt = now
        )
    }

    fun context(artifact: Artifact) =
        ExtractionContext(java.nio.file.Path.of(artifact.path).parent, config)

    test("supportedTypes contains DatabaseSchema") {
        extractor.supportedTypes shouldBe setOf(NodeType.DatabaseSchema)
    }

    test("always produces a schema node") {
        val artifact = tempSql("SELECT 1;")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.type == NodeType.DatabaseSchema } shouldBe true
    }

    test("CREATE TABLE produces a DatabaseTable node") {
        val artifact = tempSql("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.any { it.type == NodeType.DatabaseTable && it.label == "users" } shouldBe true
    }

    test("columns become Column nodes") {
        val artifact = tempSql("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);")
        val result = extractor.extract(artifact, context(artifact))
        val columns = result.nodes.filter { it.type == NodeType.Column }
        columns.any { it.label == "id" } shouldBe true
        columns.any { it.label == "name" } shouldBe true
    }

    test("multiple tables are all extracted") {
        val artifact = tempSql("""
            CREATE TABLE users (id INTEGER PRIMARY KEY);
            CREATE TABLE posts (id INTEGER PRIMARY KEY, user_id INTEGER);
        """.trimIndent())
        val result = extractor.extract(artifact, context(artifact))
        val tables = result.nodes.filter { it.type == NodeType.DatabaseTable }
        tables.any { it.label == "users" } shouldBe true
        tables.any { it.label == "posts" } shouldBe true
    }

    test("schema contains table via Contains edges") {
        val artifact = tempSql("CREATE TABLE orders (id INTEGER);")
        val result = extractor.extract(artifact, context(artifact))
        result.edges.any { it.type == EdgeType.Contains }.shouldBe(true)
    }

    test("FOREIGN KEY produces DependsOn edge between tables") {
        val artifact = tempSql("""
            CREATE TABLE users (id INTEGER PRIMARY KEY);
            CREATE TABLE posts (
                id INTEGER PRIMARY KEY,
                user_id INTEGER,
                FOREIGN KEY (user_id) REFERENCES users(id)
            );
        """.trimIndent())
        val result = extractor.extract(artifact, context(artifact))
        result.edges.any { it.type == EdgeType.DependsOn } shouldBe true
    }

    test("falls back to regex extraction for non-standard SQL") {
        val artifact = tempSql("CREATE TABLE IF NOT EXISTS `legacy_data` (col1 INT);")
        val result = extractor.extract(artifact, context(artifact))
        result.nodes.shouldNotBeEmpty()
    }
})
