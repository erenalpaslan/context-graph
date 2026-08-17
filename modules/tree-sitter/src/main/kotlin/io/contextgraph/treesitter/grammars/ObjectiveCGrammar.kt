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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * JNI binding to the native `libcontextgraph_ts_objc.{so,dylib}` compiled from the
 * vendored, version-pinned `tree-sitter-grammars/tree-sitter-objc` grammar. See
 * `JavaGrammar.kt` for how this binding is produced and why the fully-qualified name is
 * load-bearing.
 *
 * Slice 08 (Objective-C extraction) edits this file only -- it must not touch the
 * registry or the build.
 */
internal object TreeSitterObjectiveC {
    init { NativeGrammarLoader.load("contextgraph_ts_objc") }

    external fun language(): Long
}

/**
 * Objective-C language support: parses via the vendored `tree-sitter-objc` grammar and
 * extracts declaration-site symbol nodes for classes (`@interface`/`@implementation`),
 * categories, protocols, properties, methods and imports. See [ObjcSymbolExtractor] for
 * the walk itself.
 *
 * `.h` headers are ambiguous (C/C++/ObjC); [io.contextgraph.treesitter.LanguageRegistry]
 * resolves them to Objective-C per the slice spec.
 */
object ObjectiveCLanguageSupport : LanguageSupport {
    override val id: String = "objc"
    override val extensions: Set<String> = setOf("m", "h")
    override fun language(): Language = Language(TreeSitterObjectiveC.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        ObjcSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Objective-C tree and emits declaration-site nodes for classes
 * (`@interface`/`@implementation`), categories, protocols, properties, methods and
 * imports (`#import`/`#include`, `@import`).
 *
 * **The header/implementation split.** A class's `@interface` (in a `.h`) and its
 * `@implementation` (in a `.m`) are two separate declaration sites -- different files,
 * different line spans, correctly two distinct [DeclarationSiteId]s. But they name the
 * same type, so both route their `fqn` through the same `(namePrefix=null,
 * nameChain=[className])` (or `[className, categoryName]` for a category) -- see
 * [withFqn] -- so slice 11 can still group them. The same rule extends one level down:
 * a method declared in the header and defined in the `.m` share `fqn`
 * `className.selector:` even though their [DeclarationSiteId]s differ by file.
 *
 * **Macros defeat this grammar.** `NS_ASSUME_NONNULL_BEGIN`/`END` used bare (no
 * parentheses) and `typedef NS_ENUM(...)`/`NS_OPTIONS(...)` are not expanded by
 * tree-sitter and produce `ERROR` nodes -- confirmed by dumping `Node.sexp()` against the
 * exact pinned grammar commit (`tree-sitter-grammars/tree-sitter-objc` v3.0.2), not
 * guessed. `#define` (object-like and function-like) and `#ifdef`/`#else`/`#endif` do
 * NOT break parsing. Where a macro does break parsing, tree-sitter's error recovery
 * resyncs at the next recognizable `@interface`/`@protocol`/`@implementation` keyword, so
 * declarations AFTER the broken macro are usually still present in the tree -- just
 * nested one or more `ERROR` levels deep instead of as a direct child of
 * `translation_unit`. That is why, unlike [JavaLanguageSupport]'s direct-children-of-root
 * walk, [walk] here recurses into every unmatched node (including `ERROR`) rather than
 * stopping at the first level: a declaration lost to a direct-children-only walk is a
 * declaration this walker still finds. See `test-fixtures/objc-grammar/Macros/` for a
 * captured example of both the breakage and the recovery.
 *
 * ## Call-site emission (slice 20)
 * Every method body (`method_definition`'s `compound_statement`) is walked, after its own
 * declaration node is built, for two call forms: `message_expression` -- the idiomatic
 * `[receiver selector:arg ...]` form, see [selectorOf] -- and the plain C `call_expression`
 * (a bare function call, field `function`, only when it's a simple `identifier`). A
 * message's `receiver` is never inspected, exactly like every other grammar's "don't guess
 * through the receiver" rule; the emitted [UnresolvedReference.referenceName] is the whole
 * colon-joined selector (`doThing:with:`), matching exactly the label/fqn a `method`
 * declaration or `method_definition` gets in [extractMethod] -- both header declaration and
 * `.m` implementation resolve identically, per this class's fqn-sharing rule above. No
 * boundary check is needed in [collectReferences]: this walker never extracts a *second*
 * declaration from inside a method body (no nested `@interface`/`@implementation` exists in
 * Objective-C).
 */
private class ObjcSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        walk(request.tree.rootNode, fileId)
        return SymbolExtraction(nodes, edges, references)
    }

    /**
     * Recurses through the whole tree (not just direct children of the node passed in)
     * looking for the handful of node types this extractor understands. Any node type it
     * doesn't recognize -- including `ERROR`, `preproc_if`/`preproc_ifdef` branches, and
     * ordinary C declarations -- is walked into rather than skipped, so a real
     * declaration nested inside macro-induced parse-error wreckage is still found.
     */
    private fun walk(node: Node, fileId: NodeId) {
        for (child in node.namedChildren) {
            when (child.type) {
                "preproc_include" -> extractPreprocInclude(child, fileId)
                "module_import" -> extractModuleImport(child, fileId)
                "class_interface" -> extractClassLike(child, fileId, isImplementation = false)
                "class_implementation" -> extractClassLike(child, fileId, isImplementation = true)
                "protocol_declaration" -> extractProtocol(child, fileId)
                else -> walk(child, fileId)
            }
        }
    }

    private fun extractPreprocInclude(node: Node, fileId: NodeId) {
        val pathNode = node.childByFieldName("path") ?: return
        val importPath = pathNode.textIn(request.sourceText).toString().trim('"', '<', '>')
        if (importPath.isBlank()) return
        addImport(importPath, fileId, node)
    }

    private fun extractModuleImport(node: Node, fileId: NodeId) {
        val parts = node.childrenByFieldName("path").filter { it.type == "identifier" }
        val importPath = parts.joinToString(".") { it.textIn(request.sourceText).toString() }
        if (importPath.isBlank()) return
        addImport(importPath, fileId, node)
    }

    private fun addImport(importPath: String, fileId: NodeId, astNode: Node) {
        val importId = DeclarationSiteId.of(request.repoRelativePath, listOf("import:$importPath"))
        nodes.add(
            GraphNode(
                id = importId,
                type = NodeType.Module,
                label = importPath,
                properties = withFqn(emptyMap(), namePrefix = null, nameChain = listOf(importPath)),
                confidence = ConfidenceDefaults.IMPORT_RELATION,
                provenance = listOf(provenanceOf(astNode))
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

    /** Handles both `class_interface` (`@interface`) and `class_implementation` (`@implementation`). */
    private fun extractClassLike(node: Node, fileId: NodeId, isImplementation: Boolean) {
        val superclassNode = node.childByFieldName("superclass")
        val categoryNode = node.childByFieldName("category")
        val simpleName = node.namedChildren
            .firstOrNull { it.type == "identifier" && it != superclassNode && it != categoryNode }
            ?.textIn(request.sourceText)?.toString() ?: return

        val categoryName = categoryNode?.textIn(request.sourceText)?.toString()
        val isCategory = categoryName != null
        val scopeChain = if (isCategory) listOf(simpleName, categoryName) else listOf(simpleName)

        val extraProps = buildMap {
            put("kind", JsonPrimitive(if (isImplementation) "implementation" else "interface"))
            if (isCategory) put("extendedType", JsonPrimitive(simpleName))
            superclassNode?.let { put("superclass", JsonPrimitive(it.textIn(request.sourceText).toString())) }
            val protocols = conformedProtocols(node, superclassNode)
            if (protocols.isNotEmpty()) put("protocols", JsonPrimitive(protocols.joinToString(",")))
        }
        val label = if (isCategory) "$simpleName ($categoryName)" else simpleName
        val typeNode = declarationNode(
            astNode = node,
            type = if (isCategory) NodeType.Custom("Category") else NodeType.Class,
            label = label,
            idScopeChain = scopeChain,
            fqnNameChain = scopeChain,
            extraProps = extraProps
        )
        nodes.add(typeNode)
        addContains(fileId, typeNode.id)

        for (member in node.namedChildren) {
            when (member.type) {
                "property_declaration" -> extractProperty(member, scopeChain, typeNode.id)
                "method_declaration" -> extractMethod(member, scopeChain, typeNode.id)
                "implementation_definition" -> {
                    val inner = member.namedChildren.firstOrNull { it.type == "method_definition" }
                    if (inner != null) extractMethod(inner, scopeChain, typeNode.id)
                }
                else -> Unit
            }
        }
    }

    private fun extractProtocol(node: Node, fileId: NodeId) {
        val simpleName = node.namedChildren.firstOrNull { it.type == "identifier" }?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = listOf(simpleName)
        val extendedProtocols = node.namedChildren
            .firstOrNull { it.type == "protocol_reference_list" }
            ?.namedChildren
            ?.filter { it.type == "identifier" }
            ?.map { it.textIn(request.sourceText).toString() }
            ?: emptyList()

        val extraProps = buildMap {
            put("kind", JsonPrimitive("protocol"))
            if (extendedProtocols.isNotEmpty()) put("protocols", JsonPrimitive(extendedProtocols.joinToString(",")))
        }
        val protocolNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Protocol"),
            label = simpleName,
            idScopeChain = scopeChain,
            fqnNameChain = scopeChain,
            extraProps = extraProps
        )
        nodes.add(protocolNode)
        addContains(fileId, protocolNode.id)

        // Interface-declaration members (property_declaration/method_declaration) sit as
        // direct children; `@optional`/`@required` wrap a further batch of them in
        // qualified_protocol_interface_declaration.
        for (member in node.namedChildren) {
            when (member.type) {
                "property_declaration" -> extractProperty(member, scopeChain, protocolNode.id)
                "method_declaration" -> extractMethod(member, scopeChain, protocolNode.id)
                "qualified_protocol_interface_declaration" ->
                    for (qualified in member.namedChildren) {
                        when (qualified.type) {
                            "property_declaration" -> extractProperty(qualified, scopeChain, protocolNode.id)
                            "method_declaration" -> extractMethod(qualified, scopeChain, protocolNode.id)
                            else -> Unit
                        }
                    }
                else -> Unit
            }
        }
    }

    /**
     * A class header's `<...>` list is syntactically identical whether it's generic type
     * parameters (`Box<ObjectType>`, appearing before the `superclass:` field) or adopted
     * protocols (`Widget : NSObject <NSCopying>`, appearing after it) -- this grammar
     * does not distinguish them by node type, only by position. Protocols adopted by a
     * root class with no superclass are therefore not extracted here; see the slice
     * report.
     */
    private fun conformedProtocols(node: Node, superclassNode: Node?): List<String> {
        if (superclassNode == null) return emptyList()
        return node.namedChildren
            .filter { it.type == "parameterized_arguments" && it.startByte > superclassNode.startByte }
            .flatMap { it.namedChildren.filter { t -> t.type == "type_name" } }
            .map { it.textIn(request.sourceText).toString().trim() }
    }

    private fun extractProperty(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val attrs = node.namedChildren
            .firstOrNull { it.type == "property_attributes_declaration" }
            ?.namedChildren
            ?.filter { it.type == "property_attribute" }
            ?.map { it.textIn(request.sourceText).toString() }
            ?: emptyList()

        val declNode = node.namedChildren.firstOrNull { it.type == "struct_declaration" || it.type == "atomic_declaration" }
            ?: return
        val declaratorHolder = when (declNode.type) {
            "struct_declaration" -> declNode.namedChildren.firstOrNull { it.type == "struct_declarator" }
            else -> declNode.namedChildren.firstOrNull { it.type == "field_identifier" }
        } ?: return
        val propName = declaratorName(declaratorHolder) ?: return

        val propNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Property"),
            label = propName,
            idScopeChain = enclosingScope + propName,
            fqnNameChain = enclosingScope + propName,
            extraProps = mapOf(
                "kind" to JsonPrimitive("property"),
                "attributes" to JsonPrimitive(attrs.joinToString(","))
            )
        )
        nodes.add(propNode)
        addContains(parentId, propNode.id)
    }

    /**
     * Unwraps a C-style declarator chain (`pointer_declarator`, `array_declarator`,
     * `function_declarator` -> `parenthesized_declarator` -> `block_pointer_declarator`,
     * ...) down to the plain `identifier`/`field_identifier` at its core -- the same
     * shape whether the property is a scalar (`BOOL flag`), an object pointer
     * (`NSString *name`), a lightweight-generic pointer (`NSArray<NSString *> *tags`) or
     * a block (`void (^completion)(BOOL success)`); confirmed against real `Node.sexp()`
     * output for each shape.
     */
    private fun declaratorName(node: Node): String? {
        if (node.type == "identifier" || node.type == "field_identifier") return node.textIn(request.sourceText).toString()
        val declaratorField = node.childByFieldName("declarator")
        if (declaratorField != null) return declaratorName(declaratorField)
        val onlyChild = node.namedChildren.firstOrNull() ?: return null
        return declaratorName(onlyChild)
    }

    /** Handles both `method_declaration` (interface/protocol) and `method_definition` (implementation). */
    private fun extractMethod(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val isClassMethod = node.textIn(request.sourceText).toString().trimStart().startsWith("+")
        val segments = selectorSegments(node)
        if (segments.isEmpty()) return
        val selector = segments.joinToString("") { (name, hasParam) -> if (hasParam) "$name:" else name }

        val methodNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = selector,
            idScopeChain = enclosingScope + selector,
            fqnNameChain = enclosingScope + selector,
            extraProps = mapOf(
                "kind" to JsonPrimitive(if (isClassMethod) "class_method" else "instance_method"),
                "selector" to JsonPrimitive(selector)
            )
        )
        nodes.add(methodNode)
        addContains(parentId, methodNode.id)
        node.namedChildren.firstOrNull { it.type == "compound_statement" }?.let { collectReferences(it, methodNode.id) }
    }

    /**
     * A selector is an alternation of keyword segments (`identifier`) and their
     * arguments (`method_parameter`), e.g. `doThing:(id)a with:(id)b` walks as
     * `identifier("doThing") method_parameter(a) identifier("with") method_parameter(b)`.
     * A no-argument selector (`bar`) is a lone `identifier` with no following
     * `method_parameter`. Returns each keyword segment's text paired with whether it took
     * an argument (i.e. whether the caller must append `:`).
     */
    private fun selectorSegments(node: Node): List<Pair<String, Boolean>> {
        val relevant = node.namedChildren.filter { it.type == "identifier" || it.type == "method_parameter" }
        val segments = mutableListOf<Pair<String, Boolean>>()
        var i = 0
        while (i < relevant.size) {
            val current = relevant[i]
            if (current.type != "identifier") {
                i += 1
                continue
            }
            val next = relevant.getOrNull(i + 1)
            if (next?.type == "method_parameter") {
                segments.add(current.textIn(request.sourceText).toString() to true)
                i += 2
            } else {
                segments.add(current.textIn(request.sourceText).toString() to false)
                i += 1
            }
        }
        return segments
    }

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

    /**
     * Recurses through every descendant of [node] looking for `message_expression` and
     * plain-identifier `call_expression` call sites, attributing each one found to
     * [referringId]. See this class's doc comment for why no boundary check against nested
     * declarations is needed here.
     */
    private fun collectReferences(node: Node, referringId: NodeId) {
        for (child in node.namedChildren) {
            when (child.type) {
                "message_expression" -> selectorOf(child)?.let { emitReference(it, child, referringId) }
                "call_expression" -> {
                    val function = child.childByFieldName("function")
                    if (function?.type == "identifier") emitReference(function.textIn(request.sourceText).toString(), child, referringId)
                }
                else -> Unit
            }
            collectReferences(child, referringId)
        }
    }

    /**
     * Rebuilds the colon-joined selector text (`doThing:with:`, or a bare `foo` for a
     * zero-argument message) from a `message_expression`'s `method` field -- one or more
     * `identifier` children, exactly mirroring [selectorSegments]'s identifier/hasParam
     * pairing but for a call site instead of a declaration: here, "does this keyword take
     * an argument" is read via [Node.fieldNameForChild] against [Node.children] (all
     * children, not just the `method`-field identifiers) to see whether a literal `:`
     * token immediately follows.
     */
    private fun selectorOf(node: Node): String? {
        val children = node.children
        val sb = StringBuilder()
        for (i in children.indices) {
            if (node.fieldNameForChild(i.toUInt()) != "method") continue
            sb.append(children[i].textIn(request.sourceText))
            val next = children.getOrNull(i + 1)
            if (next != null && !next.isNamed && next.type == ":") sb.append(":")
        }
        return sb.toString().ifEmpty { null }
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
}
