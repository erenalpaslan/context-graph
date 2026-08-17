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
import kotlinx.serialization.json.buildJsonArray

/**
 * JNI binding to the native `libcontextgraph_ts_python.{so,dylib}` compiled from the
 * vendored, version-pinned `tree-sitter/tree-sitter-python` grammar (pinned to v0.23.6 --
 * see `build.gradle.kts` -- because newer tags emit ABI 15, which ktreesitter 0.24.1's
 * native core rejects). See `JavaGrammar.kt` for how this binding is produced and why the
 * fully-qualified name is load-bearing.
 */
internal object TreeSitterPython {
    init { NativeGrammarLoader.load("contextgraph_ts_python") }

    external fun language(): Long
}

/**
 * Python language support: parses via the vendored `tree-sitter-python` grammar and
 * extracts declaration-site symbol nodes, copying the shape [JavaLanguageSupport]/
 * [JavaSymbolExtractor] established. See [PythonSymbolExtractor] for the walk itself.
 */
object PythonLanguageSupport : LanguageSupport {
    override val id: String = "python"
    override val extensions: Set<String> = setOf("py")
    override fun language(): Language = Language(TreeSitterPython.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        PythonSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Python tree and emits declaration-site nodes for classes, functions,
 * methods (including nested/closures, `async def`, `__init__`, decorated definitions),
 * class attributes, module-level constants and imports (including relative imports).
 *
 * Node-type and field names below (`class_definition`, `function_definition` with an
 * unfielded leading `async` token, `decorated_definition` with `decorator`/`definition`
 * fields, `import_from_statement` with `module_name`/`name` fields, `relative_import`
 * with an `import_prefix` holding the literal dots, etc.) were confirmed against the
 * exact pinned grammar commit (`tree-sitter/tree-sitter-python` v0.23.6) by dumping
 * `Node.sexp()` and a full unfielded child walk for fixtures covering every construct
 * this walker handles -- not guessed from memory of the grammar.
 *
 * Python's dynamic dispatch means this extractor records only what the syntax actually
 * says: declaration sites, decorator text, and import targets. It never guesses at call
 * targets or attribute types to compensate for that weakness -- slice 09/10's resolution
 * and confidence ladder are what grade (or drop) anything uncertain.
 *
 * Tree-sitter is error-resilient: a syntax error anywhere still yields a tree with real
 * declaration nodes around the broken span plus `ERROR`/`MISSING` nodes in their place.
 * This walker never special-cases those -- they simply don't match any `when` branch below
 * and are silently skipped, so a malformed function still costs only itself, not its
 * siblings or the rest of the file.
 *
 * ## Call-site emission (slice 20)
 * Every function/method body is walked, after its own declaration node is built, for
 * `call` nodes -- see [collectReferences] and [calleeName]. Unlike the other grammars in
 * this project, Python's own walk above genuinely does extract a *second*, more specific
 * declaration from inside a body (a nested `function_definition`/`class_definition`, see
 * [walkFunctionBodyStatement]) -- so [collectReferences] must stop at those boundaries
 * ([BODY_SCOPE_BOUNDARY_TYPES]) to avoid attributing the same call twice, once to the
 * outer function and once (correctly) to the inner one when its own extraction walks its
 * own body. Module-level statements outside any function or class are walked the same way,
 * attributed to the file's own [io.contextgraph.treesitter.DeclarationSiteId.file] --
 * `main()`-style top-level calls are common enough in Python entry-point scripts to be
 * worth recording, per [UnresolvedReference.referringSymbolId]'s documented "outside any
 * declaration" case. As the class doc above already says: Python's dynamic dispatch means
 * this only ever records what a `call` node's `function` field literally names -- never a
 * guess at what a receiver's type resolves to.
 */
private class PythonSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()

    /**
     * Python has no `package` declaration; package identity comes entirely from
     * directory structure (and `__init__.py`), per the task notes. Derived once from
     * [SymbolExtractionRequest.repoRelativePath], e.g. `pkg/sub/mod.py` -> `pkg.sub.mod`,
     * `pkg/sub/__init__.py` -> `pkg.sub` (the package itself, not a member named `__init__`).
     */
    private val modulePath: String = modulePathOf(request.repoRelativePath)

    /** The directory segments containing this file -- the base every relative import resolves against. */
    private val packageDirs: List<String> =
        request.repoRelativePath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) emptyList() else it.split('/') }

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        for (child in request.tree.rootNode.namedChildren) {
            walkModuleLevelStatement(child, fileId)
            collectReferences(child, fileId)
        }
        return SymbolExtraction(nodes, edges, references)
    }

    private fun walkModuleLevelStatement(node: Node, fileId: NodeId) {
        when (node.type) {
            "import_statement" -> extractImport(node, fileId)
            "import_from_statement" -> extractImportFrom(node, fileId)
            "class_definition" -> extractClass(node, fileId, enclosingScope = emptyList(), decorators = emptyList())
            "function_definition" ->
                extractFunction(node, fileId, enclosingScope = emptyList(), inClassBody = false, decorators = emptyList())
            "decorated_definition" -> extractDecorated(node, fileId, enclosingScope = emptyList(), inClassBody = false)
            "expression_statement" -> extractAssignment(node, fileId, enclosingScope = emptyList(), context = BodyContext.MODULE)
            else -> Unit
        }
    }

    private enum class BodyContext { MODULE, CLASS, FUNCTION }

    private fun extractDecorated(node: Node, fileId: NodeId, enclosingScope: List<String>, inClassBody: Boolean) {
        // "decorator" is an unfielded named child of decorated_definition in this grammar
        // (only "definition" is a real field -- confirmed via a full unfielded child dump,
        // not the field-labelled sexp() alone, which silently omits it), so decorators are
        // gathered by node type rather than childrenByFieldName("decorator").
        val decorators = node.namedChildren.filter { it.type == "decorator" }.map { decoratorText(it) }
        val definition = node.childByFieldName("definition") ?: return
        when (definition.type) {
            "class_definition" -> extractClass(definition, fileId, enclosingScope, decorators, spanNode = node)
            "function_definition" ->
                extractFunction(definition, fileId, enclosingScope, inClassBody, decorators, spanNode = node)
            else -> Unit
        }
    }

    private fun decoratorText(decoratorNode: Node): String {
        // decorator: "@" <expression> -- the expression is the sole named child.
        val expr = decoratorNode.namedChildren.firstOrNull() ?: return decoratorNode.textIn(request.sourceText).toString().removePrefix("@")
        return expr.textIn(request.sourceText).toString()
    }

    private fun extractClass(
        node: Node,
        fileId: NodeId,
        enclosingScope: List<String>,
        decorators: List<String>,
        spanNode: Node = node
    ) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val classNode = declarationNode(
            astNode = spanNode,
            type = NodeType.Class,
            label = simpleName,
            scopeChain = scopeChain,
            extraProps = extraPropsOf(kind = "class", decorators = decorators)
        )
        nodes.add(classNode)
        addContains(parentId(fileId, enclosingScope), classNode.id)

        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            walkClassBodyStatement(member, fileId, scopeChain)
        }
    }

    private fun walkClassBodyStatement(node: Node, fileId: NodeId, classScope: List<String>) {
        when (node.type) {
            "class_definition" -> extractClass(node, fileId, classScope, decorators = emptyList())
            "function_definition" -> extractFunction(node, fileId, classScope, inClassBody = true, decorators = emptyList())
            "decorated_definition" -> extractDecorated(node, fileId, classScope, inClassBody = true)
            "expression_statement" -> extractAssignment(node, fileId, classScope, context = BodyContext.CLASS)
            else -> Unit
        }
    }

    private fun extractFunction(
        node: Node,
        fileId: NodeId,
        enclosingScope: List<String>,
        inClassBody: Boolean,
        decorators: List<String>,
        spanNode: Node = node
    ) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val isAsync = node.child(0u)?.type == "async"
        val scopeChain = enclosingScope + simpleName
        val kind = when {
            inClassBody && simpleName == "__init__" -> "constructor"
            inClassBody -> "method"
            else -> "function"
        }
        val funcNode = declarationNode(
            astNode = spanNode,
            type = if (inClassBody) NodeType.Method else NodeType.Function,
            label = simpleName,
            scopeChain = scopeChain,
            extraProps = extraPropsOf(kind = kind, decorators = decorators, async = isAsync)
        )
        nodes.add(funcNode)
        addContains(parentId(fileId, enclosingScope), funcNode.id)

        // Nested functions/classes/local assignments (skipped): walk the function body so
        // closures get the enclosing function in their scope chain, per the task's AC.
        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            walkFunctionBodyStatement(member, fileId, scopeChain)
            collectReferences(member, funcNode.id)
        }
    }

    private fun walkFunctionBodyStatement(node: Node, fileId: NodeId, functionScope: List<String>) {
        when (node.type) {
            "class_definition" -> extractClass(node, fileId, functionScope, decorators = emptyList())
            "function_definition" ->
                extractFunction(node, fileId, functionScope, inClassBody = false, decorators = emptyList())
            "decorated_definition" -> extractDecorated(node, fileId, functionScope, inClassBody = false)
            // Local variable assignments inside a function body are not module-level
            // constants or class attributes -- deliberately not extracted as nodes.
            else -> Unit
        }
    }

    private fun extractAssignment(exprStatement: Node, fileId: NodeId, enclosingScope: List<String>, context: BodyContext) {
        val assignment = exprStatement.namedChildren.firstOrNull { it.type == "assignment" } ?: return
        val left = assignment.childByFieldName("left") ?: return
        val targets = when (left.type) {
            "identifier" -> listOf(left)
            "pattern_list", "tuple_pattern" -> left.namedChildren.filter { it.type == "identifier" }
            else -> emptyList()
        }
        val kind = if (context == BodyContext.CLASS) "class_attribute" else "constant"
        val type = if (context == BodyContext.CLASS) NodeType.Custom("Field") else NodeType.Custom("Constant")
        for (target in targets) {
            val simpleName = target.textIn(request.sourceText).toString()
            val scopeChain = enclosingScope + simpleName
            val constNode = declarationNode(
                astNode = exprStatement,
                type = type,
                label = simpleName,
                scopeChain = scopeChain,
                extraProps = extraPropsOf(kind = kind, decorators = emptyList())
            )
            nodes.add(constNode)
            addContains(parentId(fileId, enclosingScope), constNode.id)
        }
    }

    private fun extractImport(node: Node, fileId: NodeId) {
        for (nameNode in node.childrenByFieldName("name")) {
            val (importPath, alias) = when (nameNode.type) {
                "aliased_import" -> {
                    val target = nameNode.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: continue
                    val aliasName = nameNode.childByFieldName("alias")?.textIn(request.sourceText)?.toString()
                    target to aliasName
                }
                else -> nameNode.textIn(request.sourceText).toString() to null
            }
            addImportNode(fileId, node, importPath, alias, relative = false, raw = "import $importPath")
        }
    }

    private fun extractImportFrom(node: Node, fileId: NodeId) {
        val moduleNameNode = node.childByFieldName("module_name") ?: return
        val (resolvedModule, isRelative, rawModule) = resolveModule(moduleNameNode)

        val wildcard = node.namedChildren.any { it.type == "wildcard_import" }
        if (wildcard) {
            val importPath = if (resolvedModule.isEmpty()) "*" else "$resolvedModule.*"
            addImportNode(fileId, node, importPath, alias = null, relative = isRelative, raw = "from $rawModule import *")
            return
        }

        for (nameNode in node.childrenByFieldName("name")) {
            val (symbol, alias) = when (nameNode.type) {
                "aliased_import" -> {
                    val target = nameNode.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: continue
                    val aliasName = nameNode.childByFieldName("alias")?.textIn(request.sourceText)?.toString()
                    target to aliasName
                }
                else -> nameNode.textIn(request.sourceText).toString() to null
            }
            val importPath = if (resolvedModule.isEmpty()) symbol else "$resolvedModule.$symbol"
            addImportNode(
                fileId, node, importPath, alias, relative = isRelative,
                raw = "from $rawModule import $symbol"
            )
        }
    }

    /** Result of resolving an import's module reference: (dotted path, isRelative, literal source text). */
    private data class ResolvedModule(val dottedPath: String, val relative: Boolean, val raw: String)

    private operator fun ResolvedModule.component1() = dottedPath
    private operator fun ResolvedModule.component2() = relative
    private operator fun ResolvedModule.component3() = raw

    private fun resolveModule(moduleNameNode: Node): ResolvedModule {
        if (moduleNameNode.type != "relative_import") {
            val text = moduleNameNode.textIn(request.sourceText).toString()
            return ResolvedModule(text, relative = false, raw = text)
        }

        // relative_import: import_prefix (the literal dots, e.g. "." or "..") plus an
        // optional dotted_name for any package segments after the dots (e.g. "pkg" in
        // "..pkg"). One dot means "the package containing this module" (no ascent);
        // each additional dot ascends one further package level, per Python's relative
        // import semantics -- this is a structural resolution from repoRelativePath's
        // directory alone, never a guess at what the target module actually contains.
        val prefixNode = moduleNameNode.namedChildren.firstOrNull { it.type == "import_prefix" }
        val dots = prefixNode?.textIn(request.sourceText)?.toString()?.count { it == '.' } ?: 1
        val extraSegments = moduleNameNode.namedChildren
            .firstOrNull { it.type == "dotted_name" }
            ?.textIn(request.sourceText)?.toString()?.split(".")
            ?: emptyList()

        val ascendLevels = (dots - 1).coerceAtMost(packageDirs.size)
        val basePackage = packageDirs.dropLast(ascendLevels)
        val resolved = (basePackage + extraSegments).joinToString(".")
        val raw = ".".repeat(dots) + extraSegments.joinToString(".")
        return ResolvedModule(resolved, relative = true, raw = raw)
    }

    private fun addImportNode(fileId: NodeId, astNode: Node, importPath: String, alias: String?, relative: Boolean, raw: String) {
        val importId = DeclarationSiteId.of(request.repoRelativePath, listOf("import:$importPath"))
        val extraProps = buildMap {
            put("kind", JsonPrimitive("import"))
            put("relative", JsonPrimitive(relative))
            put("raw", JsonPrimitive(raw))
            if (alias != null) put("alias", JsonPrimitive(alias))
        }
        nodes.add(
            GraphNode(
                id = importId,
                type = NodeType.Module,
                label = importPath,
                properties = withFqn(extraProps, namePrefix = null, nameChain = listOf(importPath)),
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

    /**
     * Recurses through every descendant of [node] looking for `call` nodes, attributing
     * each one found to [referringId] -- except inside [BODY_SCOPE_BOUNDARY_TYPES], which
     * this extractor already gives their own declaration node and walks separately (with
     * their own, more specific, `referringId`); recursing into them here too would
     * double-attribute the same call site. See this class's doc comment.
     */
    private fun collectReferences(node: Node, referringId: NodeId) {
        if (node.type in BODY_SCOPE_BOUNDARY_TYPES) return
        if (node.type == "call") {
            calleeName(node)?.let { emitReference(it, node, referringId) }
        }
        for (child in node.namedChildren) {
            collectReferences(child, referringId)
        }
    }

    /**
     * A `call` node's `function` field is either a plain `identifier` (`foo()`) or an
     * `attribute` (`obj.method()`), whose own `attribute` field is the identifier actually
     * being invoked -- `obj` itself, the `object` field, is never inspected. Any other
     * callable shape (a subscript, a parenthesized expression, ...) returns `null` rather
     * than guess.
     */
    private fun calleeName(callNode: Node): String? {
        val function = callNode.childByFieldName("function") ?: return null
        return when (function.type) {
            "identifier" -> function.textIn(request.sourceText).toString()
            "attribute" -> function.childByFieldName("attribute")?.textIn(request.sourceText)?.toString()
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
        scopeChain: List<String>,
        extraProps: Map<String, JsonElement>
    ): GraphNode = GraphNode(
        id = DeclarationSiteId.of(request.repoRelativePath, scopeChain),
        type = type,
        label = label,
        properties = withFqn(extraProps, namePrefix = modulePath.ifEmpty { null }, nameChain = scopeChain),
        confidence = ConfidenceDefaults.AST_SYMBOL,
        provenance = listOf(provenanceOf(astNode))
    )

    private fun extraPropsOf(kind: String, decorators: List<String>, async: Boolean? = null): Map<String, JsonElement> =
        buildMap {
            put("kind", JsonPrimitive(kind))
            if (async != null) put("async", JsonPrimitive(async))
            if (decorators.isNotEmpty()) {
                put("decorators", buildJsonArray { decorators.forEach { add(JsonPrimitive(it)) } })
            }
        }

    private fun provenanceOf(astNode: Node): Provenance = Provenance(
        artifactId = request.artifactId,
        path = request.repoRelativePath,
        lineStart = astNode.startPoint.row.toInt() + 1,
        lineEnd = astNode.endPoint.row.toInt() + 1,
        extractor = request.extractorId,
        extractedAt = request.extractedAt
    )

    companion object {
        /**
         * Node types this extractor already gives their own declaration node (and walks
         * separately, with their own `referringId`) wherever they appear nested inside
         * another body -- [collectReferences] must not recurse into them a second time.
         */
        private val BODY_SCOPE_BOUNDARY_TYPES = setOf("function_definition", "class_definition", "decorated_definition")

        /**
         * `pkg/sub/mod.py` -> `pkg.sub.mod`; `pkg/sub/__init__.py` -> `pkg.sub` (the
         * package itself has no member named `__init__`); a root-level `mod.py` -> `mod`.
         */
        fun modulePathOf(repoRelativePath: String): String {
            val withoutExt = repoRelativePath.removeSuffix(".py")
            val parts = withoutExt.split("/").let { if (it.last() == "__init__") it.dropLast(1) else it }
            return parts.joinToString(".")
        }
    }
}
