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
 * JNI binding to the native `libcontextgraph_ts_go.{so,dylib}` compiled by
 * `:modules:tree-sitter`'s `compileTreeSitterGrammars` task from the vendored,
 * version-pinned `tree-sitter/tree-sitter-go` grammar (see `build.gradle.kts`).
 *
 * The class's fully-qualified name is load-bearing: it is baked into the JNI symbol
 * name the native library exports (`Java_io_contextgraph_treesitter_grammars_TreeSitterGo_language`).
 * Renaming this object or moving its package requires updating the matching `GrammarSpec`
 * in `build.gradle.kts`.
 */
internal object TreeSitterGo {
    init { NativeGrammarLoader.load("contextgraph_ts_go") }

    external fun language(): Long
}

/**
 * Go language support: parses via the vendored `tree-sitter-go` grammar and extracts
 * declaration-site symbol nodes. See [GoSymbolExtractor] for the walk itself.
 */
object GoLanguageSupport : LanguageSupport {
    override val id: String = "go"
    override val extensions: Set<String> = setOf("go")
    override fun language(): Language = Language(TreeSitterGo.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        GoSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Go tree and emits declaration-site nodes for named types (struct,
 * interface, alias), their fields and interface method sets, package-level functions,
 * methods (attributed to their receiver's type), package-level `const`/`var` names, and
 * imports -- plus the call sites inside every function and method body.
 *
 * Node-type and field names below (`method_declaration`, `field: "receiver"`,
 * `method_elem`, ...) were read out of the pinned commit's own generated
 * `src/node-types.json` (`tree-sitter/tree-sitter-go` v0.23.4), not recalled from memory of
 * the grammar. Two shapes there are easy to get wrong and are worth naming: an interface's
 * method set is `method_elem`, *not* the `method_spec` older versions of this grammar used;
 * and `import_declaration`/`var_declaration` each wrap their specs in a `..._list` node only
 * when the source used the parenthesised block form, so both forms have to be flattened.
 *
 * ## Where Go differs from the languages already here, and what that costs
 *
 * **A method is not nested inside its type.** Every other grammar in this module reads a
 * method out of the body of the type that owns it; Go declares methods at file top level
 * with a `receiver` field naming the type. Scope-correct identity therefore comes from the
 * receiver, not from the walk's position: `func (engine *Engine) ServeHTTP(...)` in `gin.go`
 * becomes `gin.go#Engine.ServeHTTP(ResponseWriter,*Request)`, so a second `ServeHTTP` on a
 * different receiver in the same file stays a distinct node. [declaredTypeNames] pre-scans
 * the file for the type names it declares so the method can be hung off its type's own node
 * rather than off the file -- a pre-pass is needed because Go lets a method precede the
 * `type` declaration it belongs to.
 *
 * A receiver whose type is declared in *another file* of the same package (legal, and common
 * in large packages) falls back to a `Contains` edge from the file. The method node's ID and
 * `fqn` still lead with the receiver type either way, which is what makes the identity
 * correct; the containment edge is the only thing that degrades.
 *
 * **Go has no overloading**, so a name is unique within its scope and parameter types are not
 * needed to disambiguate. They are still written into the ID segment, because every other
 * grammar here does and a reader comparing a Go ID against a Java one should not have to
 * learn a second shape.
 *
 * **`:=` carries no written type.** [typeEnvironment] reads receivers, parameters and
 * explicitly-typed `var` declarations, and stops there: the type of a short variable
 * declaration is its right-hand side's result type, which is usually declared in another
 * file. That is the same line every grammar here draws -- record what the syntax says, never
 * infer -- and the cost is bounded, because [UnresolvedReference.receiverType] is a hint pass
 * 2 falls back from rather than a fact it trusts.
 *
 * Tree-sitter is error-resilient: a syntax error anywhere still yields a tree with real
 * declaration nodes around the broken span plus `ERROR`/`MISSING` nodes in their place. This
 * walker never special-cases those -- they simply don't match any `when` branch below and are
 * silently skipped, so a malformed function still costs only itself.
 */
private class GoSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()
    private var packageName: String? = null

    fun extract(): SymbolExtraction {
        val root = request.tree.rootNode
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        val declaredTypes = declaredTypeNames(root)

        for (child in root.namedChildren) {
            when (child.type) {
                "package_clause" ->
                    packageName = child.namedChildren.firstOrNull()?.textIn(request.sourceText)
                "import_declaration" -> extractImports(child, fileId)
                "type_declaration" -> for (spec in child.namedChildren) extractTypeSpec(spec, fileId)
                "function_declaration" -> extractFunction(child, fileId)
                "method_declaration" -> extractMethod(child, fileId, declaredTypes)
                "const_declaration" -> extractValues(child, fileId, "const", NodeType.Custom("Constant"))
                "var_declaration" -> extractValues(child, fileId, "var", NodeType.Custom("Variable"))
                else -> Unit
            }
        }
        return SymbolExtraction(nodes, edges, references)
    }

    /**
     * Every type name this file declares, read before the main walk so a `method_declaration`
     * can resolve its receiver to that type's own node. Go permits the method to appear above
     * the `type` declaration it extends, so a single forward pass would miss those.
     */
    private fun declaredTypeNames(root: Node): Set<String> = root.namedChildren
        .filter { it.type == "type_declaration" }
        .flatMap { it.namedChildren }
        .filter { it.type == "type_spec" || it.type == "type_alias" }
        .mapNotNull { it.childByFieldName("name")?.textIn(request.sourceText) }
        .toSet()

    private fun extractImports(node: Node, fileId: NodeId) {
        // `import "fmt"` puts the spec directly under the declaration; `import ( ... )` wraps
        // the specs in an `import_spec_list`. Flatten both into one list of `import_spec`.
        val specs = node.namedChildren
            .flatMap { if (it.type == "import_spec_list") it.namedChildren else listOf(it) }
            .filter { it.type == "import_spec" }

        for (spec in specs) {
            val importPath = spec.childByFieldName("path")
                ?.textIn(request.sourceText)
                ?.trim('"', '`')
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val importId = DeclarationSiteId.of(request.repoRelativePath, listOf("import:$importPath"))
            nodes.add(
                GraphNode(
                    id = importId,
                    type = NodeType.Module,
                    label = importPath,
                    properties = withFqn(emptyMap(), namePrefix = null, nameChain = listOf(importPath)),
                    confidence = ConfidenceDefaults.IMPORT_RELATION,
                    provenance = listOf(provenanceOf(spec))
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
    }

    private fun extractTypeSpec(spec: Node, fileId: NodeId) {
        if (spec.type != "type_spec" && spec.type != "type_alias") return
        val simpleName = spec.childByFieldName("name")?.textIn(request.sourceText) ?: return
        val underlying = spec.childByFieldName("type")
        val kind = when {
            spec.type == "type_alias" -> "type_alias"
            underlying?.type == "struct_type" -> "struct"
            underlying?.type == "interface_type" -> "interface"
            else -> "type"
        }
        // A struct is the closest thing Go has to the Class every other grammar emits, and is
        // what `explore`'s type-shaped queries look for; the rest keep their own vocabulary.
        val nodeType = when (kind) {
            "struct" -> NodeType.Class
            "interface" -> NodeType.Custom("Interface")
            "type_alias" -> NodeType.Custom("TypeAlias")
            else -> NodeType.Custom("Type")
        }
        val embedded = embeddedTypesOf(underlying)
        val typeNode = declarationNode(
            astNode = spec,
            type = nodeType,
            label = simpleName,
            idScopeChain = listOf(simpleName),
            fqnNameChain = listOf(simpleName),
            extraProps = buildMap {
                put("kind", JsonPrimitive(kind))
                if (embedded.isNotEmpty()) put("supertypes", JsonArray(embedded.map { JsonPrimitive(it) }))
            }
        )
        nodes.add(typeNode)
        addContains(fileId, typeNode.id)

        // Dispatch on `kind`, which already decided the type_alias case above -- `type Foo =
        // struct{...}` must stay a bare alias, not gain the Field/Method children a real struct
        // or interface declaration gets, even though its `underlying` shape is a struct_type.
        // The `underlying != null` guard is what lets the compiler prove
        // `extractStructFields`/`extractInterfaceMethods` never receive a null receiver, via
        // smart cast, without a `!!`.
        if (underlying != null) {
            when (kind) {
                "struct" -> extractStructFields(underlying, simpleName, typeNode.id)
                "interface" -> extractInterfaceMethods(underlying, simpleName, typeNode.id)
            }
        }
    }

    /**
     * The names of the types [underlying] embeds -- a struct's anonymous fields, or an
     * interface's embedded interfaces.
     *
     * Recorded under the same `supertypes` key Java uses for `extends`/`implements`, because
     * embedding is how Go says the same thing: a `gin.Engine` that embeds `RouterGroup` answers
     * to `RouterGroup`'s methods without declaring any of them. Without this the promoted
     * methods have nothing tying them to the embedding type at all.
     */
    private fun embeddedTypesOf(underlying: Node?): List<String> = when (underlying?.type) {
        "struct_type" -> fieldDeclarationsOf(underlying)
            .filter { it.childrenByFieldName("name").isEmpty() }
            .mapNotNull { simpleTypeName(it.childByFieldName("type")?.textIn(request.sourceText)) }
        "interface_type" -> underlying.namedChildren
            .filter { it.type == "type_elem" }
            .mapNotNull { simpleTypeName(it.textIn(request.sourceText)) }
        else -> emptyList()
    }.distinct()

    private fun extractStructFields(structType: Node, typeName: String, parentId: NodeId) {
        for (field in fieldDeclarationsOf(structType)) {
            val writtenType = field.childByFieldName("type")?.textIn(request.sourceText)
            val names = field.childrenByFieldName("name").mapNotNull { it.textIn(request.sourceText) }
            // An embedded field has no `name` at all: the embedded type's own name is how the
            // rest of the package refers to it, so that is the label it gets.
            val effective = names.ifEmpty { listOfNotNull(simpleTypeName(writtenType)) }
            val fieldKind = if (names.isEmpty()) "embedded_field" else "field"

            for (name in effective) {
                val fieldNode = declarationNode(
                    astNode = field,
                    type = NodeType.Custom("Field"),
                    label = name,
                    idScopeChain = listOf(typeName, name),
                    fqnNameChain = listOf(typeName, name),
                    extraProps = buildMap {
                        put("kind", JsonPrimitive(fieldKind))
                        simpleTypeName(writtenType)?.let { put("type", JsonPrimitive(it)) }
                    }
                )
                nodes.add(fieldNode)
                addContains(parentId, fieldNode.id)
            }
        }
    }

    private fun fieldDeclarationsOf(structType: Node): List<Node> = structType.namedChildren
        .firstOrNull { it.type == "field_declaration_list" }
        ?.namedChildren
        ?.filter { it.type == "field_declaration" }
        .orEmpty()

    /**
     * An interface's declared method set. These are declarations with no body, and they are the
     * whole point of an interface for impact analysis: `binding.Binding`'s `Bind` is where a
     * signature change starts, and every concrete implementation is downstream of it.
     */
    private fun extractInterfaceMethods(interfaceType: Node, typeName: String, parentId: NodeId) {
        for (member in interfaceType.namedChildren) {
            if (member.type != "method_elem") continue
            val simpleName = member.childByFieldName("name")?.textIn(request.sourceText) ?: continue
            val methodNode = declarationNode(
                astNode = member,
                type = NodeType.Method,
                label = simpleName,
                idScopeChain = listOf(typeName, signatureSegment(simpleName, member)),
                fqnNameChain = listOf(typeName, simpleName),
                extraProps = buildMap {
                    put("kind", JsonPrimitive("interface_method"))
                    returnTypeOf(member)?.let { put("returns", JsonPrimitive(it)) }
                }
            )
            nodes.add(methodNode)
            addContains(parentId, methodNode.id)
        }
    }

    private fun extractFunction(node: Node, fileId: NodeId) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText) ?: return
        val fnNode = declarationNode(
            astNode = node,
            type = NodeType.Function,
            label = simpleName,
            idScopeChain = listOf(signatureSegment(simpleName, node)),
            fqnNameChain = listOf(simpleName),
            extraProps = buildMap {
                put("kind", JsonPrimitive("function"))
                returnTypeOf(node)?.let { put("returns", JsonPrimitive(it)) }
            }
        )
        nodes.add(fnNode)
        addContains(fileId, fnNode.id)
        node.childByFieldName("body")?.let {
            collectReferences(it, fnNode.id, typeEnvironment(node, receiver = null))
        }
    }

    private fun extractMethod(node: Node, fileId: NodeId, declaredTypes: Set<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText) ?: return
        val receiver = node.childByFieldName("receiver")
            ?.namedChildren
            ?.firstOrNull { it.type == "parameter_declaration" }
        val receiverType = simpleTypeName(receiver?.childByFieldName("type")?.textIn(request.sourceText))
        // A method whose receiver cannot be reduced to a type -- an error-recovered file can carry
        // a method_declaration with a real name but a MISSING/ERROR receiver -- still gets emitted,
        // scoped to the file exactly as a package-level function is: this class's own contract
        // (docs above) is that a malformed declaration costs only itself, not that it vanishes.
        val scopeChain = listOfNotNull(receiverType)

        val methodNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = simpleName,
            idScopeChain = scopeChain + signatureSegment(simpleName, node),
            fqnNameChain = scopeChain + simpleName,
            extraProps = buildMap {
                put("kind", JsonPrimitive("method"))
                receiverType?.let { put("receiver", JsonPrimitive(it)) }
                returnTypeOf(node)?.let { put("returns", JsonPrimitive(it)) }
            }
        )
        nodes.add(methodNode)
        // The receiver's type, when this file declares it -- otherwise the file, either because
        // the type lives in another file of the same package (this walker has no ID for it) or
        // because the receiver itself could not be reduced to a type at all.
        val parentId = if (receiverType != null && receiverType in declaredTypes) {
            DeclarationSiteId.of(request.repoRelativePath, scopeChain)
        } else {
            fileId
        }
        addContains(parentId, methodNode.id)

        node.childByFieldName("body")?.let {
            collectReferences(it, methodNode.id, typeEnvironment(node, receiver))
        }
    }

    /**
     * Package-level `const`/`var` names. Both forms -- a bare `var x = 1` and a parenthesised
     * `var ( ... )` block -- are flattened the same way, and a spec declaring several names at
     * once (`const a, b = 1, 2`) yields one node per name.
     */
    private fun extractValues(node: Node, fileId: NodeId, kind: String, type: NodeType) {
        val specs = node.namedChildren
            .flatMap { if (it.type.endsWith("_spec_list")) it.namedChildren else listOf(it) }
            .filter { it.type == "const_spec" || it.type == "var_spec" }

        for (spec in specs) {
            for (nameNode in spec.childrenByFieldName("name")) {
                if (nameNode.type != "identifier") continue
                val simpleName = nameNode.textIn(request.sourceText)
                val valueNode = declarationNode(
                    astNode = spec,
                    type = type,
                    label = simpleName,
                    idScopeChain = listOf(simpleName),
                    fqnNameChain = listOf(simpleName),
                    extraProps = buildMap {
                        put("kind", JsonPrimitive(kind))
                        simpleTypeName(spec.childByFieldName("type")?.textIn(request.sourceText))
                            ?.let { put("type", JsonPrimitive(it)) }
                    }
                )
                nodes.add(valueNode)
                addContains(fileId, valueNode.id)
            }
        }
    }

    /**
     * Recurses through every descendant of [node] (a function or method body) recording every
     * `call_expression`, attributing it to [referringId] -- the nearest enclosing declaration
     * this walker has an ID for. Calls inside a `func` literal therefore attribute to the
     * function that contains the literal, which is correct: this walker never gives a literal
     * its own declaration node, so there is no more specific owner to lose them to.
     */
    private fun collectReferences(node: Node, referringId: NodeId, env: Map<String, String>) {
        for (child in node.namedChildren) {
            val fn = if (child.type == "call_expression") child.childByFieldName("function") else null
            when (fn?.type) {
                // `engine.handleHTTPRequest(c)` -- the called name is the selector's `field`,
                // and its operand is the receiver.
                "selector_expression" -> fn.childByFieldName("field")?.let { nameNode ->
                    emitReference(
                        nameNode.textIn(request.sourceText),
                        nameNode,
                        referringId,
                        receiverTypeOf(fn.childByFieldName("operand"), env),
                        receiverCallOf(fn)
                    )
                }
                // `serveError(c, code, body)` -- a package-local call, no receiver.
                "identifier" -> emitReference(
                    fn.textIn(request.sourceText), fn, referringId,
                    receiverType = null, receiverCall = null
                )
                else -> Unit
            }
            collectReferences(child, referringId, env)
        }
    }

    private fun emitReference(
        name: String,
        callNode: Node,
        referringId: NodeId,
        receiverType: String?,
        receiverCall: String?
    ) {
        references.add(
            UnresolvedReference(
                referenceName = name,
                referringSymbolId = referringId,
                repoRelativePath = request.repoRelativePath,
                artifactId = request.artifactId,
                // The name token's node, not the whole call's: a `call_expression` starts at the
                // beginning of its entire receiver chain, so every call in a chain split over
                // several lines would otherwise report the chain's first line. See JavaGrammar's
                // note on the same trap -- it silently corrupted this project's benchmark ground
                // truth once already.
                line = callNode.startPoint.row.toInt() + 1,
                receiverType = receiverType,
                receiverCall = receiverCall
            )
        )
    }

    /**
     * The declared type of a call's receiver, or null when it cannot be read off a declaration
     * in this file.
     *
     * A bare identifier resolves through [env] (receiver, parameters, typed locals). An
     * identifier that is *not* in [env] but starts with an upper-case letter is taken to name a
     * package-level type -- Go's own export convention, and the same rule Java's walker applies
     * for a static call's receiver. A lower-case unknown identifier is almost always an imported
     * package name (`fmt`, `http`, `os`), which is not a receiver type at all, so it yields null
     * rather than a wrong hint.
     *
     * There is no `this`-shaped case to handle: Go's receiver is an ordinary named parameter, so
     * it is already bound in [env] like any other.
     */
    private fun receiverTypeOf(operand: Node?, env: Map<String, String>): String? {
        if (operand?.type != "identifier") {
            // Anything else -- `c.engine.Foo()`, `pool.Get().(*Context).Foo()` -- has a type that
            // is declared on a struct in some other file. Not guessed here; pass 2 has the symbol
            // table this file does not.
            return null
        }
        val name = operand.textIn(request.sourceText)
        return env[name] ?: name.takeIf { it.first().isUpperCase() }
    }

    /**
     * When the receiver is itself a call, that call's name -- `Get` for `engine.pool.Get().foo()`.
     * Pass 1 stops here rather than following the chain: the method being called is usually
     * declared in another file, and its result type is not knowable from this one.
     */
    private fun receiverCallOf(selector: Node): String? {
        val operand = selector.childByFieldName("operand") ?: return null
        if (operand.type != "call_expression") return null
        return when (val fn = operand.childByFieldName("function")) {
            null -> null
            else -> when (fn.type) {
                "selector_expression" -> fn.childByFieldName("field")?.textIn(request.sourceText)
                "identifier" -> fn.textIn(request.sourceText)
                else -> null
            }
        }
    }

    /**
     * The names a call inside this function's body could use as a receiver, mapped to their
     * written types: the method receiver, every parameter, and every explicitly-typed `var`
     * declared anywhere in the body.
     *
     * Flat and scope-blind, exactly as Java's equivalent is, and for the same reason: a later
     * declaration simply overwrites an earlier one, and the cost of being wrong is bounded to
     * nothing because pass 2 falls back to the unfiltered candidate set whenever this hint
     * matches none.
     */
    private fun typeEnvironment(fnNode: Node, receiver: Node?): Map<String, String> {
        val env = HashMap<String, String>()
        receiver?.let { bindParameter(it, env) }
        fnNode.childByFieldName("parameters")?.namedChildren
            ?.filter { it.type == "parameter_declaration" || it.type == "variadic_parameter_declaration" }
            ?.forEach { bindParameter(it, env) }
        fnNode.childByFieldName("body")?.let { collectTypedLocals(it, env) }
        return env
    }

    private fun bindParameter(param: Node, env: MutableMap<String, String>) {
        val type = simpleTypeName(param.childByFieldName("type")?.textIn(request.sourceText)) ?: return
        for (nameNode in param.childrenByFieldName("name")) {
            if (nameNode.type != "identifier") continue
            env[nameNode.textIn(request.sourceText)] = type
        }
    }

    private fun collectTypedLocals(node: Node, env: MutableMap<String, String>) {
        for (child in node.namedChildren) {
            if (child.type == "var_declaration") {
                val specs = child.namedChildren
                    .flatMap { if (it.type == "var_spec_list") it.namedChildren else listOf(it) }
                    .filter { it.type == "var_spec" }
                for (spec in specs) {
                    val type = simpleTypeName(spec.childByFieldName("type")?.textIn(request.sourceText))
                        ?: continue
                    for (nameNode in spec.childrenByFieldName("name")) {
                        if (nameNode.type != "identifier") continue
                        env[nameNode.textIn(request.sourceText)] = type
                    }
                }
            }
            collectTypedLocals(child, env)
        }
    }

    /** `Name(T1,T2)` -- the ID segment shape every grammar in this module uses for a callable. */
    private fun signatureSegment(simpleName: String, callable: Node): String =
        "$simpleName(${paramTypesOf(callable.childByFieldName("parameters")).joinToString(",")})"

    /**
     * One entry per declared parameter, in source order. `func f(a, b int)` is a single
     * `parameter_declaration` carrying two names and one type, so the type is repeated per name
     * -- otherwise the ID segment would under-report the function's arity.
     */
    private fun paramTypesOf(parameters: Node?): List<String> {
        if (parameters == null) return emptyList()
        return parameters.namedChildren
            .filter { it.type == "parameter_declaration" || it.type == "variadic_parameter_declaration" }
            .flatMap { param ->
                val written = param.childByFieldName("type")?.textIn(request.sourceText)
                    ?.filterNot { it.isWhitespace() }
                    ?: "?"
                val arity = param.childrenByFieldName("name").count { it.type == "identifier" }
                List(maxOf(arity, 1)) { written }
            }
    }

    /**
     * The written result type, reduced to a simple name, or null when the function returns
     * nothing or returns a multi-value tuple.
     *
     * A tuple (`(*Context, error)`, parsed as a `parameter_list`) is deliberately left null
     * rather than reduced to its first element: pass 2 reads `returns` to type a *chained*
     * receiver, and the receiver of a chained call on a multi-value return is not expressible
     * in Go anyway.
     */
    private fun returnTypeOf(callable: Node): String? {
        val result = callable.childByFieldName("result") ?: return null
        if (result.type == "parameter_list") return null
        return simpleTypeName(result.textIn(request.sourceText))
    }

    /**
     * Strips a written Go type down to the simple name resolution matches on: `*Engine` becomes
     * `Engine`, `[]HandlerFunc` becomes `HandlerFunc`, `http.ResponseWriter` becomes
     * `ResponseWriter`, `Tree[K]` becomes `Tree`.
     *
     * Pointer, variadic and slice/array markers are peeled because the receiver of `x.Foo()` is
     * never the pointer or the slice -- it is the named type underneath, which is what carries
     * the methods. A qualified name reduces to its last segment because that is the form
     * declaration IDs and labels use.
     */
    private fun simpleTypeName(written: String?): String? {
        if (written == null) return null
        var t = written.trim()
        while (true) {
            val next = when {
                t.startsWith("*") -> t.removePrefix("*")
                t.startsWith("...") -> t.removePrefix("...")
                t.startsWith("[]") -> t.removePrefix("[]")
                else -> break
            }
            t = next.trim()
        }
        val erased = t.substringBefore('[').trim().substringAfterLast('.')
        return erased.ifEmpty { null }?.takeIf { it.first().isLetter() || it.first() == '_' }
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

    private fun declarationNode(
        astNode: Node,
        type: NodeType,
        label: String,
        idScopeChain: List<String>,
        fqnNameChain: List<String>,
        extraProps: Map<String, JsonElement>
    ): GraphNode = GraphNode(
        id = DeclarationSiteId.of(request.repoRelativePath, idScopeChain),
        type = type,
        label = label,
        properties = withFqn(extraProps, namePrefix = packageName, nameChain = fqnNameChain),
        confidence = ConfidenceDefaults.AST_SYMBOL,
        provenance = listOf(provenanceOf(astNode))
    )

    private fun provenanceOf(astNode: Node): Provenance = Provenance(
        artifactId = request.artifactId,
        path = request.repoRelativePath,
        lineStart = astNode.startPoint.row.toInt() + 1,
        lineEnd = astNode.endPoint.row.toInt() + 1,
        extractor = request.extractorId,
        extractedAt = request.extractedAt
    )
}
