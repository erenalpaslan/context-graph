package io.contextgraph.query

enum class QueryIntent { DEVELOPER, RESEARCHER, GENERAL }

class IntentClassifier {
    private val developerKeywords = setOf(
        "caller", "callers", "call", "calls", "depend", "dependency", "dependencies",
        "import", "imports", "function", "method", "class", "test", "tests", "implements",
        "interface", "extend", "extends", "override", "package", "module", "api", "route"
    )
    private val researcherKeywords = setOf(
        "concept", "claim", "methodology", "dataset", "experiment", "result",
        "hypothesis", "finding", "citation", "reference", "paper", "study", "evidence"
    )

    fun classify(query: String): QueryIntent {
        val lower = query.lowercase()
        val devScore = developerKeywords.count { lower.contains(it) }
        val resScore = researcherKeywords.count { lower.contains(it) }
        return when {
            devScore > resScore -> QueryIntent.DEVELOPER
            resScore > devScore -> QueryIntent.RESEARCHER
            else -> QueryIntent.GENERAL
        }
    }
}
