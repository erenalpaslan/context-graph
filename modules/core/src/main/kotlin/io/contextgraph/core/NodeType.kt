package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface NodeType {
    // Artifact-level types
    @Serializable data object CodeFile : NodeType
    @Serializable data object Document : NodeType
    @Serializable data object MarkdownFile : NodeType
    @Serializable data object PDF : NodeType
    @Serializable data object Image : NodeType
    @Serializable data object Diagram : NodeType
    @Serializable data object DatabaseSchema : NodeType
    @Serializable data object ConfigFile : NodeType
    @Serializable data object ResearchPaper : NodeType
    @Serializable data object TestFile : NodeType
    @Serializable data object PackageFile : NodeType

    // Entity-level types
    @Serializable data object Function : NodeType
    @Serializable data object Class : NodeType
    @Serializable data object Method : NodeType
    @Serializable data object Module : NodeType
    @Serializable data object Package : NodeType
    @Serializable data object API : NodeType
    @Serializable data object Route : NodeType
    @Serializable data object Component : NodeType
    @Serializable data object DatabaseTable : NodeType
    @Serializable data object Column : NodeType
    @Serializable data object Concept : NodeType
    @Serializable data object Claim : NodeType
    @Serializable data object Methodology : NodeType
    @Serializable data object Dataset : NodeType
    @Serializable data object Experiment : NodeType
    @Serializable data object Requirement : NodeType
    @Serializable data object Decision : NodeType
    @Serializable data object Person : NodeType
    @Serializable data object Organization : NodeType
    @Serializable data class Custom(val name: String) : NodeType

    companion object {
        fun fromStringOrNull(s: String): NodeType? {
            val t = fromString(s)
            return if (t is Custom) null else t
        }

        fun fromString(s: String): NodeType = when (s) {
            "CodeFile" -> CodeFile
            "Document" -> Document
            "MarkdownFile" -> MarkdownFile
            "PDF" -> PDF
            "Image" -> Image
            "Diagram" -> Diagram
            "DatabaseSchema" -> DatabaseSchema
            "ConfigFile" -> ConfigFile
            "ResearchPaper" -> ResearchPaper
            "TestFile" -> TestFile
            "PackageFile" -> PackageFile
            "Function" -> Function
            "Class" -> Class
            "Method" -> Method
            "Module" -> Module
            "Package" -> Package
            "API" -> API
            "Route" -> Route
            "Component" -> Component
            "DatabaseTable" -> DatabaseTable
            "Column" -> Column
            "Concept" -> Concept
            "Claim" -> Claim
            "Methodology" -> Methodology
            "Dataset" -> Dataset
            "Experiment" -> Experiment
            "Requirement" -> Requirement
            "Decision" -> Decision
            "Person" -> Person
            "Organization" -> Organization
            else -> Custom(s)
        }

        fun stringify(t: NodeType): String = when (t) {
            is Custom -> t.name
            else -> t::class.simpleName ?: "Unknown"
        }
    }
}
