package io.contextgraph.benchmark.questions

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

private const val MIN_GOLD_FACTS = 3
private const val MAX_GOLD_FACTS = 6

/**
 * Loads and validates benchmark question sets from YAML data files.
 *
 * This is a validator, not just a reader (task 03): a file that fails any of
 * the checks below is rejected outright — no partial load, no silently
 * dropped question. Every gold fact must carry [evidence][Evidence] (a
 * `file:line` citation, AC-4, which is unrepresentable in the model without
 * one — see [Evidence.parse]); every question must carry 3-6 gold facts
 * (AC-4); category values must be one of [QuestionCategory]'s values and
 * question ids must be unique (task 03 rejection list).
 *
 * Adding a question is a data-file edit only (AC-3) — nothing here changes
 * when a new question is added, only the YAML this loader reads.
 */
object QuestionSetLoader {

    /** Loads a single YAML question-set file. See [loadText] for the shape. */
    fun loadFile(path: Path): List<Question> = loadText(path.readText(), source = path.name)

    /**
     * Loads and merges every `*.yaml`/`*.yml` file directly under [dir]
     * (non-recursive — one file per repo is the convention; see
     * `modules/benchmark/README.md`). Question ids must be unique across the
     * merged set, not just within a single file.
     */
    fun loadDirectory(dir: Path): List<Question> {
        val files = Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .sorted()
                .toList()
        }
        val all = files.flatMap { loadFile(it) }
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw QuestionSetValidationException(
                errors = duplicates.map { "Duplicate question id '$it' across files in ${dir.name}" },
                source = dir.name
            )
        }
        return all
    }

    /**
     * Parses and validates YAML text of the shape:
     *
     * ```yaml
     * questions:
     *   - id: q1
     *     repoId: excalidraw
     *     text: "Which files depend on renderScene?"
     *     category: GRAPH_HEAVY
     *     goldFacts:
     *       - id: q1-f1
     *         statement: "App.tsx calls renderScene"
     *         evidence: "src/App.tsx:120"
     * ```
     *
     * Throws [QuestionSetValidationException] listing every problem found:
     * missing/malformed evidence (naming the owning gold fact's id, AC-4),
     * out-of-range gold fact counts, unrecognized categories, and duplicate
     * question ids within this text.
     */
    fun loadText(text: String, source: String = "<inline>"): List<Question> {
        val errors = mutableListOf<String>()

        val root = try {
            @Suppress("UNCHECKED_CAST")
            (Yaml().load<Any?>(text) as? Map<String, Any?>) ?: emptyMap()
        } catch (e: Exception) {
            throw QuestionSetValidationException(listOf("Could not parse YAML: ${e.message}"), source)
        }

        @Suppress("UNCHECKED_CAST")
        val rawQuestions = root["questions"] as? List<Map<String, Any?>>
            ?: throw QuestionSetValidationException(
                listOf("Top-level 'questions' key is missing or is not a list"),
                source
            )

        val seenIds = mutableSetOf<String>()
        val questions = mutableListOf<Question>()

        for ((index, raw) in rawQuestions.withIndex()) {
            val id = raw["id"] as? String
            if (id.isNullOrBlank()) {
                errors += "questions[$index]: missing or blank 'id'"
                continue
            }
            if (!seenIds.add(id)) {
                errors += "Duplicate question id '$id' in $source"
                continue
            }

            val parsed = parseQuestion(id, raw, errors)
            if (parsed != null) {
                questions += parsed
            }
        }

        if (errors.isNotEmpty()) {
            throw QuestionSetValidationException(errors, source)
        }

        return questions
    }

    private fun parseQuestion(id: String, raw: Map<String, Any?>, errors: MutableList<String>): Question? {
        val repoId = raw["repoId"] as? String
        if (repoId.isNullOrBlank()) errors += "Question '$id': missing or blank 'repoId'"

        val text = raw["text"] as? String
        if (text.isNullOrBlank()) errors += "Question '$id': missing or blank 'text'"

        val categoryRaw = raw["category"] as? String
        val category = when {
            categoryRaw.isNullOrBlank() -> {
                errors += "Question '$id': missing 'category'"
                null
            }
            else -> try {
                QuestionCategory.valueOf(categoryRaw)
            } catch (e: IllegalArgumentException) {
                errors += "Question '$id': unrecognized category '$categoryRaw' " +
                    "(expected one of ${QuestionCategory.entries.joinToString { it.name }})"
                null
            }
        }

        @Suppress("UNCHECKED_CAST")
        val rawFacts = raw["goldFacts"] as? List<Map<String, Any?>>
        if (rawFacts == null) {
            errors += "Question '$id': missing or malformed 'goldFacts'"
        }

        val goldFacts = rawFacts?.let { parseGoldFacts(id, it, errors) }

        @Suppress("UNCHECKED_CAST")
        val expectedSet = (raw["expectedSet"] as? List<Any?>)?.map { it.toString() }
        if (expectedSet != null && expectedSet.isEmpty()) {
            errors += "Question '$id': 'expectedSet' is present but empty -- a set question with " +
                "no expected elements can only ever score zero"
        }
        val isSetQuestion = expectedSet != null && expectedSet.isNotEmpty()

        // Two valid question shapes, and the count rule belongs only to the first. A set question
        // carries a mechanically derived answer set instead of hand-written key facts, so
        // demanding 3-6 of the latter would reject exactly the questions built to escape the
        // saturation the key-fact set suffers from.
        if (!isSetQuestion && rawFacts != null && goldFacts != null && goldFacts.size == rawFacts.size) {
            if (goldFacts.size < MIN_GOLD_FACTS || goldFacts.size > MAX_GOLD_FACTS) {
                errors += "Question '$id': has ${goldFacts.size} gold facts, " +
                    "must be between $MIN_GOLD_FACTS and $MAX_GOLD_FACTS"
            }
        }

        val factsWellFormed = rawFacts != null && goldFacts != null && goldFacts.size == rawFacts.size
        val validShape = factsWellFormed &&
            (isSetQuestion || goldFacts!!.size in MIN_GOLD_FACTS..MAX_GOLD_FACTS)

        return if (repoId != null && text != null && category != null && validShape) {
            Question(
                id = id,
                repoId = repoId,
                text = text,
                category = category,
                goldFacts = goldFacts!!,
                expectedSet = expectedSet
            )
        } else {
            null
        }
    }

    private fun parseGoldFacts(
        questionId: String,
        rawFacts: List<Map<String, Any?>>,
        errors: MutableList<String>
    ): List<GoldFact> {
        val seenFactIds = mutableSetOf<String>()
        val goldFacts = mutableListOf<GoldFact>()

        rawFacts.forEachIndexed { factIndex, rawFact ->
            val factId = (rawFact["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: "$questionId-goldFacts[$factIndex]"
            if (!seenFactIds.add(factId)) {
                errors += "Question '$questionId': duplicate gold fact id '$factId'"
                return@forEachIndexed
            }

            val statement = rawFact["statement"] as? String
            if (statement.isNullOrBlank()) {
                errors += "Question '$questionId' gold fact '$factId': missing or blank 'statement'"
            }

            val evidenceRaw = rawFact["evidence"]
            if (evidenceRaw !is String || evidenceRaw.isBlank()) {
                errors += "Question '$questionId' gold fact '$factId': missing 'evidence' " +
                    "(every gold fact requires a file:line citation, AC-4)"
                return@forEachIndexed
            }

            val evidence = try {
                Evidence.parse(evidenceRaw)
            } catch (e: IllegalArgumentException) {
                errors += "Question '$questionId' gold fact '$factId': ${e.message}"
                return@forEachIndexed
            }

            if (!statement.isNullOrBlank()) {
                goldFacts += GoldFact(id = factId, statement = statement, evidence = evidence)
            }
        }

        return goldFacts
    }
}
