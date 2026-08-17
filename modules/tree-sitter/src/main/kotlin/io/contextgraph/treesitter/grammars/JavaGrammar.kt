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
 * JNI binding to the native `libcontextgraph_ts_java.{so,dylib}` compiled by
 * `:modules:tree-sitter`'s `compileTreeSitterGrammars` task from the vendored,
 * version-pinned `tree-sitter/tree-sitter-java` grammar (see `build.gradle.kts`).
 *
 * The class's fully-qualified name is load-bearing: it is baked into the JNI symbol
 * name the native library exports (`Java_io_contextgraph_treesitter_grammars_TreeSitterJava_language`).
 * Renaming this object or moving its package requires updating the matching `GrammarSpec`
 * in `build.gradle.kts`.
 */
internal object TreeSitterJava {
    init { NativeGrammarLoader.load("contextgraph_ts_java") }

    external fun language(): Long
}

/**
 * Java language support: parses via the vendored `tree-sitter-java` grammar and extracts
 * declaration-site symbol nodes -- the first real implementation of the seam every other
 * grammar slice (04-08) copies. See [JavaSymbolExtractor] for the walk itself.
 */
object JavaLanguageSupport : LanguageSupport {
    override val id: String = "java"
    override val extensions: Set<String> = setOf("java")
    override fun language(): Language = Language(TreeSitterJava.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        JavaSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Java tree and emits declaration-site nodes for types (class, interface,
 * enum, record), constructors, methods, fields (including enum constants) and imports.
 *
 * Node-type and field names below (`class_declaration`, `field: "declarator"`, etc.) were
 * confirmed against the exact pinned grammar commit (`tree-sitter/tree-sitter-java` v0.23.5)
 * by dumping `Node.sexp()` for a fixture covering every construct this walker handles,
 * including a syntax-error case -- not guessed from memory of the grammar.
 *
 * Tree-sitter is error-resilient: a syntax error anywhere still yields a tree with real
 * declaration nodes around the broken span plus `ERROR`/`MISSING` nodes in their place.
 * This walker never special-cases those -- they simply don't match any `when` branch below
 * and are silently skipped, so a malformed method still costs only itself, not its
 * siblings or the rest of the file.
 *
 * ## Call-site emission (slice 20)
 * Every method and constructor body is walked, after its own declaration node is built, for
 * `method_invocation` nodes -- the only call form this walker records, per the slice
 * instruction to record what the syntax says rather than infer a target. A
 * `method_invocation`'s `name` field is exactly what's emitted as
 * [UnresolvedReference.referenceName]; its `object` field (the receiver, if any) is
 * deliberately never inspected -- resolving through a receiver's type is a precision this
 * syntactic walker doesn't have, and guessing would violate the "record what the syntax
 * says" rule. [collectReferences] recurses freely into every descendant (including a local
 * or anonymous class's body, which this walker never extracts as its own declaration) so a
 * call nested arbitrarily deep in a method still attributes to that method -- the nearest
 * enclosing declaration this walker actually has an ID for. Constructor calls
 * (`object_creation_expression`, i.e. `new Foo()`) and field/enum-constant initializers are
 * deliberately out of scope for this slice; see the slice report for why.
 */
private class JavaSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()
    private var packageName: String? = null

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        for (child in request.tree.rootNode.namedChildren) {
            when (child.type) {
                "package_declaration" -> packageName = child.namedChildren.firstOrNull()?.textIn(request.sourceText)?.toString()
                "import_declaration" -> extractImport(child, fileId)
                "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" ->
                    extractType(child, fileId, emptyList())
                else -> Unit
            }
        }
        return SymbolExtraction(nodes, edges, references)
    }

    private fun extractImport(node: Node, fileId: NodeId) {
        val parts = node.namedChildren
        val base = parts.firstOrNull()?.textIn(request.sourceText)?.toString() ?: return
        val importPath = if (parts.any { it.type == "asterisk" }) "$base.*" else base
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

    private fun extractType(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val kind = javaKind(node.type)
        val typeNode = declarationNode(
            astNode = node,
            type = if (kind == "class") NodeType.Class else NodeType.Custom(kind.replaceFirstChar { it.uppercase() }),
            label = simpleName,
            idScopeChain = scopeChain,
            fqnNameChain = scopeChain,
            extraProps = mapOf("kind" to JsonPrimitive(kind))
        )
        nodes.add(typeNode)
        addContains(parentId(fileId, enclosingScope), typeNode.id)

        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            when (member.type) {
                "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" ->
                    extractType(member, fileId, scopeChain)
                "method_declaration" -> extractMethod(member, scopeChain, typeNode.id)
                "constructor_declaration" -> extractConstructor(member, scopeChain, typeNode.id)
                "field_declaration" -> extractField(member, scopeChain, typeNode.id)
                "enum_constant" -> extractEnumConstant(member, scopeChain, typeNode.id)
                else -> Unit
            }
        }
    }

    private fun extractMethod(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val paramTypes = paramTypesOf(node.childByFieldName("parameters"))
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        val methodNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = simpleName,
            idScopeChain = enclosingScope + segment,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("method"))
        )
        nodes.add(methodNode)
        addContains(parentId, methodNode.id)
        node.childByFieldName("body")?.let { collectReferences(it, methodNode.id) }
    }

    private fun extractConstructor(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val paramTypes = paramTypesOf(node.childByFieldName("parameters"))
        val segment = "<init>(${paramTypes.joinToString(",")})"
        val ctorNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = simpleName,
            idScopeChain = enclosingScope + segment,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("constructor"))
        )
        nodes.add(ctorNode)
        addContains(parentId, ctorNode.id)
        node.childByFieldName("body")?.let { collectReferences(it, ctorNode.id) }
    }

    private fun extractField(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        for (declarator in node.childrenByFieldName("declarator")) {
            val simpleName = declarator.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: continue
            val fieldNode = declarationNode(
                astNode = node,
                type = NodeType.Custom("Field"),
                label = simpleName,
                idScopeChain = enclosingScope + simpleName,
                fqnNameChain = enclosingScope + simpleName,
                extraProps = mapOf("kind" to JsonPrimitive("field"))
            )
            nodes.add(fieldNode)
            addContains(parentId, fieldNode.id)
        }
    }

    private fun extractEnumConstant(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val constNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Field"),
            label = simpleName,
            idScopeChain = enclosingScope + simpleName,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("enum_constant"))
        )
        nodes.add(constNode)
        addContains(parentId, constNode.id)
    }

    /**
     * Recurses through every descendant of [node] (a method/constructor body) looking for
     * `method_invocation` call sites, attributing every one found -- however deeply nested
     * in blocks, lambdas, or an unextracted local/anonymous class -- to [referringId], the
     * nearest enclosing declaration this walker actually has an ID for. No boundary check
     * is needed: this walker never extracts a *second*, more specific declaration from
     * inside a method body (local/anonymous classes aren't extracted at all), so there is
     * no double-attribution risk to guard against.
     */
    private fun collectReferences(node: Node, referringId: NodeId) {
        for (child in node.namedChildren) {
            if (child.type == "method_invocation") {
                val calledName = child.childByFieldName("name")?.textIn(request.sourceText)?.toString()
                if (calledName != null) emitReference(calledName, child, referringId)
            }
            collectReferences(child, referringId)
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

    private fun parentId(fileId: NodeId, enclosingScope: List<String>): NodeId =
        if (enclosingScope.isEmpty()) fileId else DeclarationSiteId.of(request.repoRelativePath, enclosingScope)

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
            properties = withFqn(extraProps, namePrefix = packageName, nameChain = fqnNameChain),
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

    private fun paramTypesOf(parameters: Node?): List<String> {
        if (parameters == null) return emptyList()
        return parameters.namedChildren
            .filter { it.type == "formal_parameter" || it.type == "spread_parameter" }
            .map { p -> p.childByFieldName("type")?.textIn(request.sourceText)?.toString() ?: "?" }
    }

    private fun javaKind(nodeType: String): String = when (nodeType) {
        "class_declaration" -> "class"
        "interface_declaration" -> "interface"
        "enum_declaration" -> "enum"
        "record_declaration" -> "record"
        else -> "class"
    }
}
