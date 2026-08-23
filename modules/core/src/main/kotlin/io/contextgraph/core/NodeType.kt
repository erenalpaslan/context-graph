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
    @Serializable data object CodeModule : NodeType
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
            "CodeModule" -> CodeModule
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

        /**
         * Whether [t] represents a whole file rather than a symbol declared inside one -- the
         * "Artifact-level types" vs "Entity-level types" split the two comments above already
         * draw, now enforced by the compiler rather than left as prose: this `when` has no
         * `else` branch, so adding a new [NodeType] without deciding which side of the split it
         * falls on fails the build instead of silently defaulting either way. `Custom` (open-
         * ended extension types, e.g. from a project-specific extractor) is never a file type --
         * a caller that needs one to sometimes act as one should not reach for this.
         */
        fun isFileType(t: NodeType): Boolean = when (t) {
            CodeFile, Document, MarkdownFile, PDF, Image, Diagram, DatabaseSchema, ConfigFile,
            ResearchPaper, TestFile, PackageFile -> true
            Function, Class, Method, Module, Package, CodeModule, API, Route, Component,
            DatabaseTable, Column, Concept, Claim, Methodology, Dataset, Experiment, Requirement,
            Decision, Person, Organization -> false
            is Custom -> false
        }
    }
}
