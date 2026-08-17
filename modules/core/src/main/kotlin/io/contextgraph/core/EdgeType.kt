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
    /**
     * Links a non-primary declaration site to the primary one within a group of nodes
     * that share an `fqn` -- a type extended across several files, a Kotlin extension
     * function repeated on the same receiver, an Objective-C header/implementation split,
     * TypeScript declaration merging, or plain method overloads. Source is the sibling,
     * target is the primary declaration [io.contextgraph.ingest.SiblingGrouper] elects.
     * Never merges the nodes it connects -- see that class's doc for how primary is
     * chosen and why grouping is off `fqn` alone.
     */
    @Serializable data object SiblingOf : EdgeType
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
            "sibling_of", "siblingof" -> SiblingOf
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
            SiblingOf -> "sibling_of"
        }
    }
}
