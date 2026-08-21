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
 * [UnresolvedReference.referenceName]. Its `object` field -- the receiver -- is read too, but
 * only as far as the file itself can answer for: [receiverTypeOf] reports the receiver's
 * *declared* type when it names a parameter, field or local in scope, and null otherwise. This
 * is still recording what the syntax says rather than inferring: the type is copied off a
 * declaration a few lines up, never deduced from a call's return value. It matters because
 * without it resolution is name-only, and a name like `getId` is declared on 1001 types in a
 * single real corpus -- ambiguous past any usable threshold, so those calls resolved to nothing
 * at all. See [UnresolvedReference.receiverType].
 *
 * [collectReferences] recurses freely into every descendant (including a local
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
        val supertypes = supertypesOf(node)
        val typeNode = declarationNode(
            astNode = node,
            type = if (kind == "class") NodeType.Class else NodeType.Custom(kind.replaceFirstChar { it.uppercase() }),
            label = simpleName,
            idScopeChain = scopeChain,
            fqnNameChain = scopeChain,
            extraProps = buildMap {
                put("kind", JsonPrimitive(kind))
                if (supertypes.isNotEmpty()) {
                    put("supertypes", JsonArray(supertypes.map { JsonPrimitive(it) }))
                }
            }
        )
        nodes.add(typeNode)
        addContains(parentId(fileId, enclosingScope), typeNode.id)

        val body = node.childByFieldName("body") ?: return
        // Read once per type, not once per method: every method in this body shares these fields
        // as the base of its own type environment.
        val fieldTypes = fieldTypesOf(body)
        for (member in body.namedChildren) {
            when (member.type) {
                "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" ->
                    extractType(member, fileId, scopeChain)
                "method_declaration" -> extractMethod(member, scopeChain, typeNode.id, simpleName, fieldTypes)
                "constructor_declaration" -> extractConstructor(member, scopeChain, typeNode.id, simpleName, fieldTypes)
                "field_declaration" -> extractField(member, scopeChain, typeNode.id)
                "enum_constant" -> extractEnumConstant(member, scopeChain, typeNode.id)
                else -> Unit
            }
        }
    }

    private fun extractMethod(
        node: Node,
        enclosingScope: List<String>,
        parentId: NodeId,
        enclosingType: String,
        fieldTypes: Map<String, String>
    ) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val paramTypes = paramTypesOf(node.childByFieldName("parameters"))
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        // The declared return type, recorded so pass 2 can type a *chained* receiver: in
        // `authSession.getParentSession().getId()` the receiver of `getId` is this method's
        // result, and its type is written right here at the declaration. Without it that whole
        // shape carries no receiver hint at all -- which is most of what still misses.
        val returns = simpleTypeName(node.childByFieldName("type")?.textIn(request.sourceText)?.toString())
        val methodNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = simpleName,
            idScopeChain = enclosingScope + segment,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = buildMap {
                put("kind", JsonPrimitive("method"))
                if (returns != null) put("returns", JsonPrimitive(returns))
            }
        )
        nodes.add(methodNode)
        addContains(parentId, methodNode.id)
        node.childByFieldName("body")?.let {
            collectReferences(it, methodNode.id, typeEnvironment(node, fieldTypes), enclosingType)
        }
    }

    private fun extractConstructor(
        node: Node,
        enclosingScope: List<String>,
        parentId: NodeId,
        enclosingType: String,
        fieldTypes: Map<String, String>
    ) {
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
        node.childByFieldName("body")?.let {
            collectReferences(it, ctorNode.id, typeEnvironment(node, fieldTypes), enclosingType)
        }
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
    private fun collectReferences(
        node: Node,
        referringId: NodeId,
        env: Map<String, String>,
        enclosingType: String?
    ) {
        for (child in node.namedChildren) {
            if (child.type == "method_invocation") {
                val nameNode = child.childByFieldName("name")
                val calledName = nameNode?.textIn(request.sourceText)?.toString()
                // The name token's node, not the invocation's. A `method_invocation` starts at the
                // beginning of its whole receiver chain, so in a builder split over several lines
                // every call in the chain would report the chain's first line -- `.name(x)`,
                // `.type(y)` and `.helpText(z)` all claiming to be on the line `builder.property()`
                // sits on. The exact same off-by-one silently corrupted this project's benchmark
                // ground truth, which was derived by a different tool making the same assumption.
                if (calledName != null && nameNode != null) {
                    emitReference(
                        calledName, nameNode, referringId,
                        receiverTypeOf(child, env, enclosingType),
                        receiverCallOf(child)
                    )
                }
            }
            collectReferences(child, referringId, env, enclosingType)
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
                line = callNode.startPoint.row.toInt() + 1,
                receiverType = receiverType,
                receiverCall = receiverCall
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

    /**
     * Strips a written-out type down to the simple name resolution matches on: `List<UserModel>`
     * becomes `List`, `org.keycloak.models.UserSessionModel` becomes `UserSessionModel`, `String[]`
     * becomes `String`.
     *
     * Generic arguments and array brackets are dropped rather than parsed because the receiver of
     * `x.foo()` is never the array or the type argument -- it is the erasure, which is what carries
     * the methods. A qualified name reduces to its last segment because that is the form
     * declaration ids and labels use.
     */
    private fun simpleTypeName(written: String?): String? {
        if (written == null) return null
        val erased = written.substringBefore('<').substringBefore('[').trim().substringAfterLast('.')
        return erased.ifEmpty { null }?.takeIf { it.first().isLetter() || it.first() == '_' }
    }

    /**
     * The declared type of every name a call inside [body] could use as a receiver: the enclosing
     * type's fields, this method's parameters, and every local variable declared anywhere in the
     * body.
     *
     * Flat and scope-blind on purpose. Java allows the same name to be a field and a local in
     * different blocks, and tracking that properly means a real scope stack; here a later
     * declaration simply overwrites an earlier one. The cost of being wrong is bounded to nothing:
     * the result is only ever a filter over same-name candidates, and pass 2 falls back to the
     * unfiltered set whenever the filter matches none -- so a bad hint degrades to today's
     * behaviour rather than to a wrong edge.
     */
    private fun typeEnvironment(methodNode: Node, enclosingFields: Map<String, String>): Map<String, String> {
        val env = HashMap(enclosingFields)
        methodNode.childByFieldName("parameters")?.namedChildren
            ?.filter { it.type == "formal_parameter" || it.type == "spread_parameter" }
            ?.forEach { p ->
                val n = p.childByFieldName("name")?.textIn(request.sourceText)?.toString()
                val t = simpleTypeName(p.childByFieldName("type")?.textIn(request.sourceText)?.toString())
                if (n != null && t != null) env[n] = t
            }
        methodNode.childByFieldName("body")?.let { collectLocals(it, env) }
        return env
    }

    private fun collectLocals(node: Node, env: MutableMap<String, String>) {
        for (child in node.namedChildren) {
            if (child.type == "local_variable_declaration") {
                val t = simpleTypeName(child.childByFieldName("type")?.textIn(request.sourceText)?.toString())
                if (t != null && t != "var") {
                    for (declarator in child.childrenByFieldName("declarator")) {
                        declarator.childByFieldName("name")?.textIn(request.sourceText)?.toString()
                            ?.let { env[it] = t }
                    }
                }
            }
            collectLocals(child, env)
        }
    }

    /** Field name to declared simple type for one type body, for [typeEnvironment] to start from. */
    private fun fieldTypesOf(typeBody: Node?): Map<String, String> {
        if (typeBody == null) return emptyMap()
        val out = HashMap<String, String>()
        for (member in typeBody.namedChildren) {
            if (member.type != "field_declaration") continue
            val t = simpleTypeName(member.childByFieldName("type")?.textIn(request.sourceText)?.toString()) ?: continue
            for (declarator in member.childrenByFieldName("declarator")) {
                declarator.childByFieldName("name")?.textIn(request.sourceText)?.toString()?.let { out[it] = t }
            }
        }
        return out
    }

    /**
     * The declared type of [callNode]'s receiver, or null when it cannot be read off a declaration.
     *
     * Handles the receivers a single file can actually answer for: a plain identifier that names a
     * parameter, field or local ([env]); `this`; `this.field`; and a bare type name used as the
     * receiver of a static call, recognised by Java's universal convention that type names begin
     * with an upper-case letter. A chained call's receiver is another call's return value and is
     * deliberately not guessed -- that needs the cross-file symbol table pass 2 is still building.
     */
    private fun receiverTypeOf(callNode: Node, env: Map<String, String>, enclosingType: String?): String? {
        val obj = callNode.childByFieldName("object") ?: return null
        return when (obj.type) {
            "identifier" -> {
                val name = obj.textIn(request.sourceText).toString()
                env[name] ?: name.takeIf { it.first().isUpperCase() }
            }
            "this" -> enclosingType
            "field_access" -> {
                val field = obj.childByFieldName("field")?.textIn(request.sourceText)?.toString()
                if (obj.childByFieldName("object")?.type == "this") env[field] else null
            }
            else -> null
        }
    }

    /**
     * When the receiver is itself a call, the name of that call -- `getParentSession` for
     * `authSession.getParentSession().getId()`.
     *
     * Pass 1 stops here rather than following the chain, because the method being called is
     * usually declared in another file and its return type is not knowable from this one. Pass 2
     * has the whole symbol table and finishes the job (see
     * [io.contextgraph.core.UnresolvedReference.receiverCall]). Recording the name is the part
     * only pass 1 can do: by pass 2 the syntax is gone.
     */
    /**
     * The simple names of everything [typeNode] extends or implements, in source order.
     *
     * Recorded on the declaration rather than resolved here, because a supertype is almost always
     * declared in another file. Pass 2 turns these names into [EdgeType.Implements] edges and
     * reads them when deciding whether two return types agree -- `UserSessionAdapter` and
     * `UserSessionModel` are not the same type, but a call site that reaches either reaches the
     * method the second declares.
     *
     * Field shapes confirmed against the pinned grammar by dumping `sexp()`: a class carries
     * `superclass:` and `interfaces:` fields, while an interface's `extends_interfaces` is an
     * unnamed child -- which is why the two are read differently below rather than through one
     * `childByFieldName` call.
     */
    private fun supertypesOf(typeNode: Node): List<String> {
        val out = mutableListOf<String>()
        typeNode.childByFieldName("superclass")?.namedChildren?.forEach { sup ->
            simpleTypeName(sup.textIn(request.sourceText).toString())?.let(out::add)
        }
        val interfaceLists = listOfNotNull(typeNode.childByFieldName("interfaces")) +
            typeNode.namedChildren.filter { it.type == "extends_interfaces" }
        interfaceLists.forEach { list ->
            list.namedChildren.filter { it.type == "type_list" }.forEach { types ->
                types.namedChildren.forEach { t ->
                    simpleTypeName(t.textIn(request.sourceText).toString())?.let(out::add)
                }
            }
        }
        return out.distinct()
    }

    private fun receiverCallOf(callNode: Node): String? {
        val obj = callNode.childByFieldName("object") ?: return null
        if (obj.type != "method_invocation") return null
        return obj.childByFieldName("name")?.textIn(request.sourceText)?.toString()
    }

    private fun javaKind(nodeType: String): String = when (nodeType) {
        "class_declaration" -> "class"
        "interface_declaration" -> "interface"
        "enum_declaration" -> "enum"
        "record_declaration" -> "record"
        else -> "class"
    }
}
