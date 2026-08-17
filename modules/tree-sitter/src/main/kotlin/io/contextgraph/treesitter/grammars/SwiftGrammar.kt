package io.contextgraph.treesitter.grammars

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.UnresolvedReference
import io.contextgraph.treesitter.DeclarationSiteId
import io.contextgraph.treesitter.LanguageSupport
import io.contextgraph.treesitter.NativeGrammarLoader
import io.contextgraph.treesitter.SymbolExtraction
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.contextgraph.treesitter.textIn
import io.contextgraph.treesitter.withFqn
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Node
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * JNI binding to the native `libcontextgraph_ts_swift.{so,dylib}` compiled from the
 * vendored, version-pinned `alex-pinkus/tree-sitter-swift` grammar -- a community
 * grammar, not to be confused with `tree-sitter/swift-tree-sitter`, which is a
 * Swift-*language* binding to tree-sitter, not a grammar. See `JavaGrammar.kt` for how
 * this binding is produced and why the fully-qualified name is load-bearing.
 *
 * Slice 07 (Swift extraction) edits this file only -- it must not touch the registry or
 * the build.
 */
internal object TreeSitterSwift {
    init { NativeGrammarLoader.load("contextgraph_ts_swift") }

    external fun language(): Long
}

/**
 * Swift language support: parses via the vendored `tree-sitter-swift` (alex-pinkus)
 * grammar and extracts declaration-site symbol nodes -- the first Swift coverage this
 * project has ever had. See [SwiftSymbolExtractor] for the walk itself.
 */
object SwiftLanguageSupport : LanguageSupport {
    override val id: String = "swift"
    override val extensions: Set<String> = setOf("swift")
    override fun language(): Language = Language(TreeSitterSwift.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        SwiftSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Swift tree and emits declaration-site nodes for classes, structs,
 * enums (including cases with associated values), protocols, actors, extensions,
 * top-level and member functions, initialisers, computed and stored properties, type
 * aliases and imports.
 *
 * Node-type and field names below were confirmed against the exact pinned grammar
 * commit (`alex-pinkus/tree-sitter-swift`, commit `31d17fe`) by dumping `Node.sexp()`
 * and a full field-aware child walk for a fixture covering every construct this walker
 * handles, plus the risk areas called out in the task (result builders, property
 * wrappers, generics with `where` clauses, `async`/`await`, macros, operator
 * declarations) and a syntax-error case -- not guessed from memory of the grammar.
 *
 * A key quirk of this grammar: `class`, `struct`, `actor` and `extension` all parse to
 * the *same* node type, `class_declaration` -- they are distinguished only by an
 * unnamed `declaration_kind` field child whose own node *type* is the keyword itself
 * (`"class"`, `"struct"`, `"actor"`, `"extension"`). `enum` also parses to
 * `class_declaration`, but with an `enum_class_body` in place of `class_body`.
 * `protocol` is the one exception with its own top-level node type,
 * `protocol_declaration` -- it carries a `declaration_kind` field too (`"protocol"`),
 * so one code path here (see [declarationKind]) covers all six.
 *
 * **Extension identity vs. extension fqn** (the reason this slice matters beyond
 * coverage, per the task notes): an `extension Foo { ... }` block's own declaration-site
 * ID is disambiguated by its start line (`Foo+ext@<line>`) so that multiple extension
 * blocks of the same type in the same file -- an extremely common iOS convention, one
 * block per protocol conformance -- never collide, and so an extension block never
 * collides with a primary `Foo` declaration in the same file. Its **fqn**, and the fqn
 * of every member declared inside it, uses the *plain extended type name* (`Foo`, not
 * `Foo+ext@12`) -- exactly the fqn a member declared directly inside `Foo` would get.
 * That is what lets slice 11 group a type's primary declaration and all of its
 * extensions, however many files they are split across, under one fqn. This required
 * threading two parallel scope chains through the recursive walk: [idScope] (unique per
 * declaration site, feeds [DeclarationSiteId]) and [fqnScope] (the logical type name,
 * feeds [withFqn]) -- identical for every declaration kind except extensions, where they
 * diverge at the extension's own node and every member below it.
 *
 * Tree-sitter is error-resilient here exactly as it is for Java: a syntax error still
 * yields a tree with real declaration nodes around the broken span. This walker never
 * special-cases `ERROR`/`MISSING` nodes -- they simply don't match any `when` branch and
 * are silently skipped.
 *
 * Grammar gaps found while probing (recorded here, not smoothed over -- see the slice's
 * fixture `SwiftUI/ContentView.swift` and `test-fixtures/swift-grammar/snapshot.json`
 * for the corresponding committed evidence): none. Every risk area called out in the
 * task -- SwiftUI result builders / `@ViewBuilder` and `body` blocks, property wrappers
 * (`@State`, `@Published`), generics with `where` clauses, `async`/`await`, macros
 * (`macro_declaration` / `#externalMacro` / macro invocation), and custom operator
 * declarations (`infix operator +++`) -- parses cleanly (`hasError == false`) against
 * this grammar commit. Declared conformances are extracted as a `conformances` property
 * on the type node (a list of raw type-name strings) rather than as edges to a resolved
 * target node, per the task's explicit instruction not to guess: a name like `Codable`
 * or a conformance declared against a type in another file cannot be resolved to a real
 * declaration-site node without the two-pass resolution slice 09 builds.
 *
 * ## Call-site emission (slice 20)
 * Every top-level/member function body and every initializer body is walked, after its own
 * declaration node is built, for `call_expression` nodes -- see [collectReferences] and
 * [calleeName]. This grammar shares Kotlin's `"fields": {}` quirk on `call_expression`
 * (namedChildren = `[calleeExpr, call_suffix]`): a plain call's callee is a
 * `simple_identifier`; a member call's is a `navigation_expression`, whose `suffix` field
 * (a `navigation_suffix`) holds the `simple_identifier` actually being invoked -- the
 * navigated-through target is never inspected, per the slice's "record what the syntax
 * says" rule. As with Kotlin (and unlike Python), no nested declaration inside a function
 * or initializer body is ever separately extracted by this walker (this extractor only
 * descends into a type's own `body` field for members, never into a function/init's body
 * looking for local types or functions), so no boundary check is needed to avoid
 * double-attributing a call.
 */
private class SwiftSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        for (child in request.tree.rootNode.namedChildren) {
            when (child.type) {
                "import_declaration" -> extractImport(child, fileId)
                "class_declaration", "protocol_declaration" ->
                    extractType(child, fileId, idScope = emptyList(), fqnScope = emptyList())
                "function_declaration" -> extractFunction(child, idScope = emptyList(), fqnScope = emptyList(), parentId = fileId)
                "typealias_declaration" -> extractTypealias(child, idScope = emptyList(), fqnScope = emptyList(), parentId = fileId)
                else -> Unit
            }
        }
        return SymbolExtraction(nodes, edges, references)
    }

    private fun extractImport(node: Node, fileId: NodeId) {
        val identifier = node.namedChildren.firstOrNull { it.type == "identifier" } ?: return
        val importPath = identifier.textIn(request.sourceText).toString()
        val importId = DeclarationSiteId.of(request.repoRelativePath, listOf("import:$importPath"))
        nodes.add(
            GraphNode(
                id = importId,
                type = NodeType.Module,
                label = importPath,
                properties = withFqn(emptyMap(), namePrefix = null, nameChain = listOf(importPath)),
                confidence = ConfidenceDefaults.IMPORT_RELATION,
                provenance = listOf(provenanceOf(node))
            )
        )
        edges.add(
            GraphEdge(
                id = EdgeId("imports:${fileId.value}:${importId.value}"),
                source = fileId,
                target = importId,
                type = EdgeType.Imports,
                confidence = ConfidenceDefaults.IMPORT_RELATION
            )
        )
    }

    /** `class`, `struct`, `enum`, `actor`, `extension` (all `class_declaration`) or `protocol`. */
    private fun extractType(node: Node, fileId: NodeId, idScope: List<String>, fqnScope: List<String>) {
        val kind = declarationKind(node) ?: return
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return

        // Extensions get a line-disambiguated ID segment (multiple extension blocks of the
        // same type in one file are a routine Swift convention) but keep the plain type
        // name for fqn purposes -- see the class doc for why these two chains diverge here.
        val idSegment = if (kind == "extension") "$simpleName+ext@${node.startPoint.row.toInt() + 1}" else simpleName
        val newIdScope = idScope + idSegment
        val newFqnScope = fqnScope + simpleName

        val conformances = node.namedChildren
            .filter { it.type == "inheritance_specifier" }
            .mapNotNull { it.childByFieldName("inherits_from")?.textIn(request.sourceText)?.toString() }

        val extraProps = buildMap<String, JsonElement> {
            put("kind", JsonPrimitive(kind))
            if (conformances.isNotEmpty()) {
                put("conformances", JsonArray(conformances.map { JsonPrimitive(it) }))
            }
        }

        val typeNode = declarationNode(
            astNode = node,
            type = nodeTypeFor(kind),
            label = simpleName,
            idScopeChain = newIdScope,
            fqnNameChain = newFqnScope,
            extraProps = extraProps
        )
        nodes.add(typeNode)
        addContains(parentId(fileId, idScope), typeNode.id)

        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            when (member.type) {
                "class_declaration", "protocol_declaration" -> extractType(member, fileId, newIdScope, newFqnScope)
                "function_declaration", "protocol_function_declaration" ->
                    extractFunction(member, newIdScope, newFqnScope, typeNode.id)
                "init_declaration" -> extractInit(member, newIdScope, newFqnScope, typeNode.id)
                "property_declaration", "protocol_property_declaration" ->
                    extractProperty(member, newIdScope, newFqnScope, typeNode.id)
                "typealias_declaration" -> extractTypealias(member, newIdScope, newFqnScope, typeNode.id)
                "enum_entry" -> extractEnumEntry(member, newIdScope, newFqnScope, typeNode.id)
                else -> Unit
            }
        }
    }

    private fun extractFunction(node: Node, idScope: List<String>, fqnScope: List<String>, parentId: NodeId) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val paramTypes = paramTypesOf(node)
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        val isTopLevel = idScope.isEmpty()
        val funcNode = declarationNode(
            astNode = node,
            type = if (isTopLevel) NodeType.Function else NodeType.Method,
            label = simpleName,
            idScopeChain = idScope + segment,
            fqnNameChain = fqnScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive(if (isTopLevel) "function" else "method"))
        )
        nodes.add(funcNode)
        addContains(parentId, funcNode.id)
        node.childByFieldName("body")?.let { collectReferences(it, funcNode.id) }
    }

    private fun extractInit(node: Node, idScope: List<String>, fqnScope: List<String>, parentId: NodeId) {
        val paramTypes = paramTypesOf(node)
        val segment = "init(${paramTypes.joinToString(",")})"
        val initNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = "init",
            idScopeChain = idScope + segment,
            fqnNameChain = fqnScope + "init",
            extraProps = mapOf("kind" to JsonPrimitive("initializer"))
        )
        nodes.add(initNode)
        addContains(parentId, initNode.id)
        node.childByFieldName("body")?.let { collectReferences(it, initNode.id) }
    }

    private fun extractProperty(node: Node, idScope: List<String>, fqnScope: List<String>, parentId: NodeId) {
        val patternNode = node.childByFieldName("name") ?: return
        val simpleName = patternNode.childByFieldName("bound_identifier")?.textIn(request.sourceText)?.toString() ?: return
        val isComputed = node.childByFieldName("computed_value") != null
        val mutability = node.namedChildren.firstOrNull { it.type == "value_binding_pattern" }
            ?.childByFieldName("mutability")?.textIn(request.sourceText)?.toString() ?: "let"
        val kind = if (isComputed) "computed_property" else "stored_property"
        val propNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Field"),
            label = simpleName,
            idScopeChain = idScope + simpleName,
            fqnNameChain = fqnScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive(kind), "mutability" to JsonPrimitive(mutability))
        )
        nodes.add(propNode)
        addContains(parentId, propNode.id)
    }

    private fun extractTypealias(node: Node, idScope: List<String>, fqnScope: List<String>, parentId: NodeId) {
        val simpleName = node.childrenByFieldName("name").firstOrNull()?.textIn(request.sourceText)?.toString() ?: return
        val aliasNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("TypeAlias"),
            label = simpleName,
            idScopeChain = idScope + simpleName,
            fqnNameChain = fqnScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("typealias"))
        )
        nodes.add(aliasNode)
        addContains(parentId, aliasNode.id)
    }

    /**
     * One `enum_entry` node can declare several case names on one line (`case a, b, c`)
     * sharing a single `data_contents` slot at most -- each `name` field child becomes
     * its own declaration node, all sharing the entry's line span and (if present) its
     * associated-values text.
     */
    private fun extractEnumEntry(node: Node, idScope: List<String>, fqnScope: List<String>, parentId: NodeId) {
        val dataContents = node.childByFieldName("data_contents")?.textIn(request.sourceText)?.toString()
        for (nameNode in node.childrenByFieldName("name")) {
            val simpleName = nameNode.textIn(request.sourceText).toString()
            val extraProps = buildMap<String, JsonElement> {
                put("kind", JsonPrimitive("enum_case"))
                if (dataContents != null) put("associatedValues", JsonPrimitive(dataContents))
            }
            val caseNode = declarationNode(
                astNode = node,
                type = NodeType.Custom("Field"),
                label = simpleName,
                idScopeChain = idScope + simpleName,
                fqnNameChain = fqnScope + simpleName,
                extraProps = extraProps
            )
            nodes.add(caseNode)
            addContains(parentId, caseNode.id)
        }
    }

    /**
     * `class_declaration` covers class/struct/enum/actor/extension, distinguished only
     * by the `declaration_kind` field's node *type*; `protocol_declaration` carries the
     * same field for symmetry. Returns `null` for anything else (defensive; every caller
     * only reaches here from a `when` branch already gated on these two node types).
     */
    private fun declarationKind(node: Node): String? = node.childByFieldName("declaration_kind")?.type

    private fun nodeTypeFor(kind: String): NodeType = when (kind) {
        "class" -> NodeType.Class
        "protocol" -> NodeType.Custom("Protocol")
        "struct" -> NodeType.Custom("Struct")
        "enum" -> NodeType.Custom("Enum")
        "actor" -> NodeType.Custom("Actor")
        "extension" -> NodeType.Custom("Extension")
        else -> NodeType.Custom(kind.replaceFirstChar { it.uppercase() })
    }

    /**
     * Recurses through every descendant of [node] looking for `call_expression` nodes,
     * attributing each one found to [referringId]. See this class's doc comment for why no
     * boundary check against nested declarations is needed here.
     */
    private fun collectReferences(node: Node, referringId: NodeId) {
        for (child in node.namedChildren) {
            if (child.type == "call_expression") {
                calleeName(child)?.let { emitReference(it, child, referringId) }
            }
            collectReferences(child, referringId)
        }
    }

    /**
     * `call_expression`'s namedChildren are `[calleeExpr, call_suffix]` (no fields on this
     * node -- see class doc). A plain call's callee is a `simple_identifier`; a member
     * call's is a `navigation_expression`, whose `suffix` field (`navigation_suffix`) has
     * its own `suffix` field holding the `simple_identifier` being invoked (or an
     * `integer_literal` for tuple element access, which is never a call target and is
     * skipped). Any other callee shape returns `null` rather than guess.
     */
    private fun calleeName(callExpression: Node): String? {
        val callee = callExpression.namedChildren.firstOrNull { it.type != "call_suffix" } ?: return null
        return when (callee.type) {
            "simple_identifier" -> callee.textIn(request.sourceText).toString()
            "navigation_expression" -> callee.childByFieldName("suffix")
                ?.childByFieldName("suffix")
                ?.takeIf { it.type == "simple_identifier" }
                ?.textIn(request.sourceText)?.toString()
            else -> null
        }
    }

    private fun emitReference(name: String, callNode: Node, referringId: NodeId) {
        references.add(
            UnresolvedReference(
                referenceName = name,
                referringSymbolId = referringId,
                repoRelativePath = request.repoRelativePath,
                artifactId = request.artifactId,
                line = callNode.startPoint.row.toInt() + 1
            )
        )
    }

    private fun parentId(fileId: NodeId, idScope: List<String>): NodeId =
        if (idScope.isEmpty()) fileId else DeclarationSiteId.of(request.repoRelativePath, idScope)

    private fun addContains(sourceId: NodeId, targetId: NodeId) {
        edges.add(
            GraphEdge(
                id = EdgeId("contains:${sourceId.value}:${targetId.value}"),
                source = sourceId,
                target = targetId,
                type = EdgeType.Contains,
                confidence = ConfidenceDefaults.AST_SYMBOL
            )
        )
    }

    private fun declarationNode(
        astNode: Node,
        type: NodeType,
        label: String,
        idScopeChain: List<String>,
        fqnNameChain: List<String>,
        extraProps: Map<String, JsonElement>
    ): GraphNode {
        return GraphNode(
            id = DeclarationSiteId.of(request.repoRelativePath, idScopeChain),
            type = type,
            label = label,
            properties = withFqn(extraProps, namePrefix = null, nameChain = fqnNameChain),
            confidence = ConfidenceDefaults.AST_SYMBOL,
            provenance = listOf(provenanceOf(astNode))
        )
    }

    private fun provenanceOf(astNode: Node): Provenance = Provenance(
        artifactId = request.artifactId,
        path = request.repoRelativePath,
        lineStart = astNode.startPoint.row.toInt() + 1,
        lineEnd = astNode.endPoint.row.toInt() + 1,
        extractor = request.extractorId,
        extractedAt = request.extractedAt
    )

    /**
     * Each `parameter` node repeats the `name` field twice: once for the parameter's own
     * binding name, once for its type -- the last occurrence is always the type.
     */
    private fun paramTypesOf(node: Node): List<String> =
        node.namedChildren.filter { it.type == "parameter" }.map { p ->
            val nameFields = p.childrenByFieldName("name")
            (nameFields.lastOrNull() ?: nameFields.firstOrNull())?.textIn(request.sourceText)?.toString() ?: "?"
        }
}
