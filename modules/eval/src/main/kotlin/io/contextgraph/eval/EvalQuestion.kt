package io.contextgraph.eval

import kotlinx.serialization.Serializable

/**
 * The three question shapes the slice 19 brief calls out as exercising different parts of
 * the system -- a task set that only covers one type "will mislead" (task file). [LOCATE]
 * leans on module descriptions/embeddings, [IMPACT] on graded `Calls` edges, [CROSS_FILE]
 * on two-pass resolution linking a reference in one file to a declaration in another.
 */
@Serializable
enum class QuestionType { LOCATE, IMPACT, CROSS_FILE }

/**
 * One eval question with a **known correct answer**, fixed by reading the code independently
 * of either answering method (AC-34). [expectedPathFragments] are repo-relative substrings of
 * the ground-truth file(s); a method "hits" a question when it surfaces at least one of them.
 * Kept as *fragments* rather than exact paths so matching survives incidental path formatting
 * differences between what `explore` returns and what a human would type.
 */
@Serializable
data class EvalQuestion(
    val id: String,
    val text: String,
    val type: QuestionType,
    /** Human-readable ground truth, recorded independently -- what a correct answer says. */
    val correctAnswer: String,
    /** Repo-relative path substrings; any single match counts as a hit. */
    val expectedPathFragments: List<String>
)

/**
 * The ~10 real questions this eval answers about ContextGraph's own repository (the task
 * file's "real target repos rather than fixtures" -- there is no fixture big enough to make
 * the graph-vs-grep difference visible). Every [EvalQuestion.correctAnswer] here was
 * determined by reading the source directly, before either `explore` or the grep baseline
 * was run against it, so grading is not circular.
 */
object EvalTaskSet {
    val QUESTIONS: List<EvalQuestion> = listOf(
        EvalQuestion(
            id = "L1",
            text = "Where is the SQLite-backed implementation of StorageAdapter?",
            type = QuestionType.LOCATE,
            correctAnswer = "modules/storage-sqlite/src/main/kotlin/io/contextgraph/storage/SqliteStorageAdapter.kt",
            expectedPathFragments = listOf("storage-sqlite/src/main/kotlin/io/contextgraph/storage/SqliteStorageAdapter.kt")
        ),
        EvalQuestion(
            id = "L2",
            text = "Where does the code read verbatim source text off disk to include in an explore response?",
            type = QuestionType.LOCATE,
            correctAnswer = "modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt",
            expectedPathFragments = listOf("query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt")
        ),
        EvalQuestion(
            id = "L3",
            text = "Where are the confidence values for the call-resolution ladder defined?",
            type = QuestionType.LOCATE,
            correctAnswer = "modules/core/src/main/kotlin/io/contextgraph/core/ConfidenceDefaults.kt",
            expectedPathFragments = listOf("core/src/main/kotlin/io/contextgraph/core/ConfidenceDefaults.kt")
        ),
        EvalQuestion(
            id = "L4",
            text = "Which file computes per-language rung distribution for resolved Calls edges?",
            type = QuestionType.LOCATE,
            correctAnswer = "modules/ingest/src/main/kotlin/io/contextgraph/ingest/RungDistribution.kt",
            expectedPathFragments = listOf("ingest/src/main/kotlin/io/contextgraph/ingest/RungDistribution.kt")
        ),
        EvalQuestion(
            id = "I1",
            text = "What breaks if ReferenceResolver's resolveAll method signature changes -- what production code calls it?",
            type = QuestionType.IMPACT,
            correctAnswer = "modules/ingest/src/main/kotlin/io/contextgraph/ingest/IngestPipeline.kt (line ~85, the only production call site)",
            expectedPathFragments = listOf("ingest/src/main/kotlin/io/contextgraph/ingest/IngestPipeline.kt")
        ),
        EvalQuestion(
            id = "I2",
            text = "What production code depends on GraphDb.forLocalWrite's seeding behavior?",
            type = QuestionType.IMPACT,
            correctAnswer = "modules/cli/src/main/kotlin/io/contextgraph/cli/Main.kt's resolveWriteDbPath(), used by the index, describe-modules and serve-mcp commands",
            expectedPathFragments = listOf("cli/src/main/kotlin/io/contextgraph/cli/Main.kt")
        ),
        EvalQuestion(
            id = "I3",
            text = "What would need to change if ExploreEngine's read-only query dependencies (blast radius, verbatim source) changed shape?",
            type = QuestionType.IMPACT,
            correctAnswer = "modules/mcp-server/src/main/kotlin/io/contextgraph/mcp/ExploreEngine.kt, the sole caller of both BlastRadius and VerbatimSource",
            expectedPathFragments = listOf("mcp-server/src/main/kotlin/io/contextgraph/mcp/ExploreEngine.kt")
        ),
        EvalQuestion(
            id = "C1",
            text = "Which class constructs ExploreEngine in production, and what does it pass in?",
            type = QuestionType.CROSS_FILE,
            correctAnswer = "modules/mcp-server/src/main/kotlin/io/contextgraph/mcp/McpServer.kt (line ~75): storage, projectRoot, LiteLlmModuleEmbedder(), config.litellm",
            expectedPathFragments = listOf("mcp-server/src/main/kotlin/io/contextgraph/mcp/McpServer.kt")
        ),
        EvalQuestion(
            id = "C2",
            text = "After IngestPipeline finishes writing extracted nodes and edges, which class performs the second pass that turns unresolved call references into Calls edges?",
            type = QuestionType.CROSS_FILE,
            correctAnswer = "modules/ingest/src/main/kotlin/io/contextgraph/ingest/ReferenceResolver.kt, invoked at IngestPipeline.kt:85",
            expectedPathFragments = listOf("ingest/src/main/kotlin/io/contextgraph/ingest/ReferenceResolver.kt")
        ),
        EvalQuestion(
            id = "C3",
            text = "Where are LLM module descriptions and module embeddings actually generated, and which CLI command triggers both?",
            type = QuestionType.CROSS_FILE,
            correctAnswer = "modules/ingest/src/main/kotlin/io/contextgraph/ingest/describe/ModuleDescriptionService.kt and ModuleEmbeddingService.kt, triggered by DescribeModulesCommand in cli/Main.kt",
            expectedPathFragments = listOf(
                "ingest/src/main/kotlin/io/contextgraph/ingest/describe/ModuleDescriptionService.kt",
                "ingest/src/main/kotlin/io/contextgraph/ingest/describe/ModuleEmbeddingService.kt"
            )
        )
    )
}
