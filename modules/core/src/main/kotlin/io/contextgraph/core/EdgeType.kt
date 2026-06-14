package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface EdgeType {
    @Serializable data object Contains : EdgeType
    @Serializable data object Defines : EdgeType
    @Serializable data object Imports : EdgeType
    @Serializable data object Calls : EdgeType
    @Serializable data object DependsOn : EdgeType
    @Serializable data object Implements : EdgeType
    @Serializable data object Tests : EdgeType
    @Serializable data object References : EdgeType
    @Serializable data object Cites : EdgeType
    @Serializable data object Supports : EdgeType
    @Serializable data object Contradicts : EdgeType
    @Serializable data object Explains : EdgeType
    @Serializable data object Uses : EdgeType
    @Serializable data object SimilarTo : EdgeType
    @Serializable data object DerivedFrom : EdgeType
    @Serializable data class Custom(val name: String) : EdgeType

    companion object {
        fun fromString(s: String): EdgeType = when (s.lowercase()) {
            "contains" -> Contains
            "defines" -> Defines
            "imports" -> Imports
            "calls" -> Calls
            "depends_on", "dependson" -> DependsOn
            "implements" -> Implements
            "tests" -> Tests
            "references" -> References
            "cites" -> Cites
            "supports" -> Supports
            "contradicts" -> Contradicts
            "explains" -> Explains
            "uses" -> Uses
            "similar_to", "similarto" -> SimilarTo
            "derived_from", "derivedfrom" -> DerivedFrom
            else -> Custom(s)
        }

        fun stringify(t: EdgeType): String = when (t) {
            is Custom -> t.name
            Contains -> "contains"
            Defines -> "defines"
            Imports -> "imports"
            Calls -> "calls"
            DependsOn -> "depends_on"
            Implements -> "implements"
            Tests -> "tests"
            References -> "references"
            Cites -> "cites"
            Supports -> "supports"
            Contradicts -> "contradicts"
            Explains -> "explains"
            Uses -> "uses"
            SimilarTo -> "similar_to"
            DerivedFrom -> "derived_from"
        }
    }
}
