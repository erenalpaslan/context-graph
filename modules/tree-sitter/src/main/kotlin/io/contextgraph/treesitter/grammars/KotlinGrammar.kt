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
 * JNI binding to the native `libcontextgraph_ts_kotlin.{so,dylib}` compiled from the
 * vendored, version-pinned `fwcd/tree-sitter-kotlin` grammar -- a community grammar, not
 * to be confused with the official `tree-sitter/kotlin-tree-sitter` *bindings* this
 * module consumes (see `JavaGrammar.kt` for how this binding is produced and why the
 * fully-qualified name is load-bearing).
 */
internal object TreeSitterKotlin {
    init { NativeGrammarLoader.load("contextgraph_ts_kotlin") }

    external fun language(): Long
}

/**
 * Kotlin language support: parses via the vendored (community-maintained) `tree-sitter-kotlin`
 * grammar and extracts declaration-site symbol nodes, following the shape [JavaGrammar.kt]
 * established. See [KotlinSymbolExtractor] for the walk itself and its class doc for the
 * grammar gaps found while probing coroutines, DSL builders, sealed hierarchies, context
 * receivers, delegated properties and expect/actual declarations.
 */
object KotlinLanguageSupport : LanguageSupport {
    override val id: String = "kotlin"
    override val extensions: Set<String> = setOf("kt")
    override fun language(): Language = Language(TreeSitterKotlin.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        KotlinSymbolExtractor(request).extract()
}

/**
 * Walks a parsed Kotlin tree (`fwcd/tree-sitter-kotlin`, pinned commit
 * `e1a2d5ad1f61f5740677183cd4125bb071cd2f30`) and emits declaration-site nodes for
 * classes, interfaces, objects, companion objects, enums (incl. enum entries), type
 * aliases, primary/secondary constructors, member and top-level functions, member and
 * top-level properties, and imports.
 *
 * Every node type and child ordering below was confirmed by dumping `Node.sexp()` (and,
 * where the grammar has no field names at all -- see below -- `Node.children`/`isNamed`)
 * for fixtures covering every construct this walker handles, against the exact pinned
 * grammar commit, not guessed from memory.
 *
 * ## Grammar shape: no field names
 * Unlike `tree-sitter-java`, this grammar's `node-types.json` declares `"fields": {}` on
 * every multi-child rule (`class_declaration`, `function_declaration`,
 * `property_declaration`, ...) -- nothing is reachable via `childByFieldName`. Every
 * lookup below is by **child type and position** among `namedChildren` instead.
 *
 * ## Grammar gap: "class" vs "interface" is not a named node
 * `class_declaration` covers both `class Foo` and `interface Foo` -- the grammar's
 * `choice("class", "interface")` keyword is an anonymous token, absent from
 * `namedChildren`/`sexp()`. [classKeywordKind] recovers it from `Node.children`
 * (all children, named and anonymous) via `isNamed == false`, which is where tree-sitter
 * always keeps unnamed literal tokens.
 *
 * ## Grammar gap: context receivers are not supported at all
 * `context(Logger) fun doWork() { ... }` -- a real, shipping Kotlin feature -- is not in
 * this grammar's `_modifier` choice or anywhere else in `grammar.js` (confirmed by
 * grepping the vendored `grammar.js` for `context`). Worse than a hard parse failure:
 * tree-sitter's error recovery does NOT set `hasError` for it. `context(Logger)` parses
 * as an ordinary top-level `call_expression` statement (a call to a function named
 * `context` with argument `Logger`), completely disconnected from the `fun doWork()` that
 * follows it, which the grammar happily parses as a plain (non-context-receiver) function
 * declaration. No diagnostic fires because there is no syntax error to detect -- the tree
 * is well-formed, just semantically wrong. `test-fixtures/kotlin-grammar/Gaps/ContextReceivers.kt`
 * captures this: `doWork` is extracted as an ordinary top-level function with no trace of
 * `context(Logger)` anywhere in the graph, and the golden snapshot bakes that gap in on
 * purpose so it stays visible rather than silently regressing further.
 *
 * Tree-sitter's error recovery otherwise behaves exactly as documented in
 * `JavaGrammar.kt`: a genuine syntax error yields real declaration nodes around the
 * broken span plus `ERROR`/`MISSING` nodes in their place, so a malformed declaration
 * costs only itself.
 *
 * ## Call-site emission (slice 20)
 * Every top-level/member function body (and secondary constructor body, if it has one) is
 * walked, after its own declaration node is built, for `call_expression` nodes -- see
 * [collectReferences] and [emitCall]. This grammar's `"fields": {}` quirk (documented
 * above) means the callee can't be read via `childByFieldName`: for a plain call
 * (`foo()`) the callee is a `simple_identifier`; for a member call (`obj.method()`) it's a
 * `navigation_expression` whose own last child, `navigation_suffix`, holds the
 * `simple_identifier` actually being invoked -- `obj` itself is never inspected, per the
 * slice's "record what the syntax says" rule. No nested declaration inside a function body
 * (a local function, local class, or lambda) is ever separately extracted by this walker,
 * so unlike Python's, this walk needs no boundary check to avoid double-attributing a call
 * -- everything found within a function's body, however deeply nested, belongs to that
 * function's own [UnresolvedReference.referringSymbolId].
 *
 * ## Receiver hints
 * Name-only resolution cannot tell `orders.load()` from the dozens of other `load`s in a repo,
 * so each call also records what the file can say about its receiver, the same three shapes
 * the Java walker records (see [UnresolvedReference]):
 * - a named receiver -- a parameter, local, or property of the enclosing type, including
 *   `this.x` -- gives its declared type ([UnresolvedReference.receiverType]); `this` gives the
 *   enclosing type, and a capitalised name is taken as an object or companion receiver;
 * - a receiver that is another call gives that call's name ([UnresolvedReference.receiverCall]);
 * - a receiver that is a property read off a named value, `useCases.loadOrder.execute()`, gives
 *   the property's name and the value's declared type ([UnresolvedReference.receiverProperty]).
 * Calling a named value directly, `loadOrder(id)` for a `loadOrder` property or parameter, is a
 * call to its type's `invoke` operator and is recorded as one.
 *
 * To let pass 2 finish what a single file cannot, declarations carry their declared types:
 * `type` on properties (constructor `val`/`var` parameters included), `returns` on functions
 * with an explicit return type, and `supertypes` on types. Types are reduced to their simple
 * name, without nullability or type arguments, the form ids and labels use. Only explicit types
 * are read: an inferred `val x = load()` carries no hint, and the call resolves by name as it
 * did before.
 */
private class KotlinSymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()
    private var packageName: String? = null

    /** Node types that can appear as a receiver in an extension function/property. */
    private val receiverTypes = setOf("user_type", "nullable_type", "parenthesized_type")

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        for (child in request.tree.rootNode.namedChildren) {
            when (child.type) {
                "package_header" -> packageName = child.namedChildren.firstOrNull()?.textIn(request.sourceText)?.toString()
                "import_list" -> for (importHeader in child.namedChildren) extractImport(importHeader, fileId)
                "class_declaration", "object_declaration" -> extractType(child, fileId, emptyList())
                "function_declaration" -> extractFunction(child, emptyList(), fileId)
                "property_declaration" -> extractProperty(child, emptyList(), fileId)
                "type_alias" -> extractTypeAlias(child, emptyList(), fileId)
                else -> Unit
            }
        }
        return SymbolExtraction(nodes, edges, references)
    }

    private fun extractImport(node: Node, fileId: NodeId) {
        val base = node.namedChildren.firstOrNull { it.type == "identifier" }?.textIn(request.sourceText)?.toString() ?: return
        val importPath = if (node.namedChildren.any { it.type == "wildcard_import" }) "$base.*" else base
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

    /** Handles `class_declaration`, `object_declaration` and `companion_object` uniformly. */
    private fun extractType(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val isCompanion = node.type == "companion_object"
        val simpleName = node.namedChildren.firstOrNull { it.type == "type_identifier" }?.textIn(request.sourceText)?.toString()
            ?: if (isCompanion) "Companion" else return
        val scopeChain = enclosingScope + simpleName
        val kind = when {
            node.type == "object_declaration" -> "object"
            isCompanion -> "companion_object"
            else -> classKeywordKind(node)
        }
        val supertypes = supertypesOf(node)
        val typeNode = declarationNode(
            astNode = node,
            type = when (kind) {
                "class" -> NodeType.Class
                else -> NodeType.Custom(kind.split("_").joinToString("") { it.replaceFirstChar(Char::uppercase) })
            },
            label = simpleName,
            idScopeChain = scopeChain,
            fqnNameChain = scopeChain,
            extraProps = buildMap {
                put("kind", JsonPrimitive(kind))
                if (supertypes.isNotEmpty()) put("supertypes", JsonArray(supertypes.map { JsonPrimitive(it) }))
            }
        )
        nodes.add(typeNode)
        addContains(parentId(fileId, enclosingScope), typeNode.id)

        extractPrimaryConstructor(node, scopeChain, typeNode.id)

        val body = node.namedChildren.firstOrNull { it.type == "class_body" || it.type == "enum_class_body" }
        // Read once per type: every function in the body starts its type environment from these.
        val memberTypes = memberTypesOf(node, body)
        if (body == null) return
        for (member in body.namedChildren) {
            when (member.type) {
                "class_declaration", "object_declaration", "companion_object" -> extractType(member, fileId, scopeChain)
                "function_declaration" -> extractFunction(member, scopeChain, typeNode.id, memberTypes, simpleName)
                "property_declaration" -> extractProperty(member, scopeChain, typeNode.id)
                "secondary_constructor" -> extractSecondaryConstructor(member, scopeChain, typeNode.id, memberTypes, simpleName)
                "type_alias" -> extractTypeAlias(member, scopeChain, typeNode.id)
                "enum_entry" -> extractEnumEntry(member, scopeChain, typeNode.id)
                else -> Unit
            }
        }
    }

    private fun extractPrimaryConstructor(typeDeclNode: Node, typeScope: List<String>, typeId: NodeId) {
        val ctor = typeDeclNode.namedChildren.firstOrNull { it.type == "primary_constructor" } ?: return
        val params = ctor.namedChildren.filter { it.type == "class_parameter" }
        val paramTypes = params.map { classParameterType(it) }
        val segment = "<init>(${paramTypes.joinToString(",")})"
        val ctorNode = declarationNode(
            astNode = ctor,
            type = NodeType.Method,
            label = "<init>",
            idScopeChain = typeScope + segment,
            fqnNameChain = typeScope + "<init>",
            extraProps = mapOf("kind" to JsonPrimitive("constructor"))
        )
        nodes.add(ctorNode)
        addContains(typeId, ctorNode.id)

        // A class parameter is only a property (visible outside the constructor) when it
        // carries `val`/`var`; a bare constructor parameter isn't extracted separately.
        for (param in params) {
            if (param.namedChildren.none { it.type == "binding_pattern_kind" }) continue
            val paramName = param.namedChildren.firstOrNull { it.type == "simple_identifier" }?.textIn(request.sourceText)?.toString() ?: continue
            val declaredType = typeNodeAfterName(param)?.let(::simpleTypeName)
            val propNode = declarationNode(
                astNode = param,
                type = NodeType.Custom("Property"),
                label = paramName,
                idScopeChain = typeScope + paramName,
                fqnNameChain = typeScope + paramName,
                extraProps = buildMap {
                    put("kind", JsonPrimitive("property"))
                    if (declaredType != null) put("type", JsonPrimitive(declaredType))
                }
            )
            nodes.add(propNode)
            addContains(typeId, propNode.id)
        }
    }

    private fun extractSecondaryConstructor(
        node: Node,
        enclosingScope: List<String>,
        parentId: NodeId,
        memberTypes: Map<String, String>,
        enclosingType: String
    ) {
        val params = node.namedChildren.firstOrNull { it.type == "function_value_parameters" }
        val paramTypes = paramTypesOf(params)
        val segment = "<init>(${paramTypes.joinToString(",")})"
        val ctorNode = declarationNode(
            astNode = node,
            type = NodeType.Method,
            label = "<init>",
            idScopeChain = enclosingScope + segment,
            fqnNameChain = enclosingScope + "<init>",
            extraProps = mapOf("kind" to JsonPrimitive("constructor"))
        )
        nodes.add(ctorNode)
        addContains(parentId, ctorNode.id)
        node.namedChildren.firstOrNull { it.type == "statements" }?.let { statements ->
            collectReferences(statements, ctorNode.id, typeEnvironment(node, memberTypes), enclosingType)
        }
    }

    /**
     * Handles member, top-level AND extension functions uniformly. An extension
     * function's receiver -- if present -- is always the `namedChildren` entry
     * immediately before the function's `simple_identifier` name (the grammar inlines
     * `_receiver_type` there with no field to key off); when present it is prepended to
     * both the identity scope chain and the fqn name chain, so `fun String.shout()`
     * declared at file scope gets fqn `<pkg>.String.shout` -- grouped with `String`'s
     * other declarations, not with the file's top-level functions -- and so two
     * extension functions with the same name on different receivers (`Int.identity()` vs
     * `String.identity()`) never collide on identity either.
     */
    private fun extractFunction(
        node: Node,
        enclosingScope: List<String>,
        parentId: NodeId,
        memberTypes: Map<String, String> = emptyMap(),
        enclosingType: String? = null
    ) {
        val named = node.namedChildren
        val nameIdx = named.indexOfFirst { it.type == "simple_identifier" }
        if (nameIdx < 0) return
        val simpleName = named[nameIdx].textIn(request.sourceText)?.toString() ?: return
        val receiver = named.getOrNull(nameIdx - 1)?.takeIf { it.type in receiverTypes }
        val baseChain = enclosingScope + listOfNotNull(receiver?.let(::receiverSimpleName))

        val parameters = named.firstOrNull { it.type == "function_value_parameters" }
        val paramTypes = paramTypesOf(parameters)
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        // The declared return type, so pass 2 can type a chained receiver (`load(id).items()`).
        // It is the type node right after the parameter list; an expression body without one
        // has no declared type to read.
        val returns = parameters?.let { params ->
            named.getOrNull(named.indexOf(params) + 1)?.takeIf { it.type in typeNodeTypes }?.let(::simpleTypeName)
        }
        val functionNode = declarationNode(
            astNode = node,
            type = if (enclosingScope.isEmpty() && receiver == null) NodeType.Function else NodeType.Method,
            label = simpleName,
            idScopeChain = baseChain + segment,
            fqnNameChain = baseChain + simpleName,
            extraProps = buildMap {
                put("kind", JsonPrimitive("function"))
                if (returns != null) put("returns", JsonPrimitive(returns))
            }
        )
        nodes.add(functionNode)
        addContains(parentId, functionNode.id)
        named.firstOrNull { it.type == "function_body" }?.let { body ->
            // An extension function's receiver is `this` inside its body.
            val thisType = receiver?.let(::receiverSimpleName) ?: enclosingType
            collectReferences(body, functionNode.id, typeEnvironment(node, memberTypes), thisType)
        }
    }

    /** Handles member, top-level AND extension properties uniformly -- see [extractFunction]. */
    private fun extractProperty(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val named = node.namedChildren
        val declIdx = named.indexOfFirst { it.type == "variable_declaration" }
        if (declIdx < 0) return // multi_variable_declaration (destructuring) is not a valid member/top-level form; skip
        val varDecl = named[declIdx]
        val simpleName = varDecl.namedChildren.firstOrNull { it.type == "simple_identifier" }?.textIn(request.sourceText)?.toString() ?: return
        val receiver = named.getOrNull(declIdx - 1)?.takeIf { it.type in receiverTypes }
        val baseChain = enclosingScope + listOfNotNull(receiver?.let(::receiverSimpleName))

        val declaredType = typeNodeAfterName(varDecl)?.let(::simpleTypeName)
        val propNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Property"),
            label = simpleName,
            idScopeChain = baseChain + simpleName,
            fqnNameChain = baseChain + simpleName,
            extraProps = buildMap {
                put("kind", JsonPrimitive("property"))
                if (declaredType != null) put("type", JsonPrimitive(declaredType))
            }
        )
        nodes.add(propNode)
        addContains(parentId, propNode.id)
    }

    private fun extractTypeAlias(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val simpleName = node.namedChildren.firstOrNull { it.type == "type_identifier" }?.textIn(request.sourceText)?.toString() ?: return
        val aliasNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("TypeAlias"),
            label = simpleName,
            idScopeChain = enclosingScope + simpleName,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("type_alias"))
        )
        nodes.add(aliasNode)
        addContains(parentId, aliasNode.id)
    }

    private fun extractEnumEntry(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val simpleName = node.namedChildren.firstOrNull { it.type == "simple_identifier" }?.textIn(request.sourceText)?.toString() ?: return
        val entryNode = declarationNode(
            astNode = node,
            type = NodeType.Custom("Property"),
            label = simpleName,
            idScopeChain = enclosingScope + simpleName,
            fqnNameChain = enclosingScope + simpleName,
            extraProps = mapOf("kind" to JsonPrimitive("enum_entry"))
        )
        nodes.add(entryNode)
        addContains(parentId, entryNode.id)
    }

    /**
     * `class_declaration` covers both `class Foo` and `interface Foo`; `enum class Foo`
     * also parses as `class_declaration` (with an `enum_class_body`). The `class` /
     * `interface` / `enum` keyword itself is an anonymous token -- not present in
     * `namedChildren` -- so it's recovered from [Node.children] (all children, including
     * unnamed literal tokens) instead. See this class's doc comment.
     */
    private fun classKeywordKind(node: Node): String = when {
        node.children.any { !it.isNamed && it.type == "enum" } -> "enum"
        node.children.any { !it.isNamed && it.type == "interface" } -> "interface"
        else -> "class"
    }

    /**
     * The simple (grouping) name of a receiver type node: the last segment of a possibly
     * qualified `user_type` (`kotlin.String` -> `String`), unwrapped through
     * `nullable_type`/`parenthesized_type` (`String?` -> `String`), ignoring generic type
     * arguments (`List<String>` -> `List`) -- fqn is a grouping key and, like parameter
     * types elsewhere in this module, generic arguments don't belong in it.
     */
    private fun receiverSimpleName(node: Node): String = when (node.type) {
        "user_type" -> node.namedChildren.lastOrNull { it.type == "type_identifier" }?.textIn(request.sourceText)?.toString()
            ?: node.textIn(request.sourceText)?.toString() ?: "?"
        "nullable_type", "parenthesized_type" -> node.namedChildren.firstOrNull()?.let(::receiverSimpleName)
            ?: node.textIn(request.sourceText)?.toString() ?: "?"
        else -> node.textIn(request.sourceText)?.toString() ?: "?"
    }

    private fun classParameterType(node: Node): String {
        // class_parameter: [modifiers?] [binding_pattern_kind?] simple_identifier ":" type ["=" default]
        // The type is whichever named child follows the simple_identifier.
        val named = node.namedChildren
        val nameIdx = named.indexOfFirst { it.type == "simple_identifier" }
        return named.getOrNull(nameIdx + 1)?.textIn(request.sourceText)?.toString() ?: "?"
    }

    private fun paramTypesOf(parameters: Node?): List<String> {
        if (parameters == null) return emptyList()
        return parameters.namedChildren
            .filter { it.type == "parameter" || it.type == "parameter_with_optional_type" }
            .map { p ->
                val named = p.namedChildren
                val nameIdx = named.indexOfFirst { it.type == "simple_identifier" }
                named.getOrNull(nameIdx + 1)?.textIn(request.sourceText)?.toString() ?: "?"
            }
    }

    /** Node types that spell a declared type: `Foo`, `a.b.Foo<T>`, `Foo?`, `(Foo)`. */
    private val typeNodeTypes = setOf("user_type", "nullable_type", "parenthesized_type")

    /**
     * The simple name of a declared type node, or null for a type that names no declaration
     * (a function type, `dynamic`). `a.b.Foo<Bar>?` -> `Foo`: the last identifier of the user
     * type, without its package, its type arguments or its nullability.
     */
    private fun simpleTypeName(typeNode: Node): String? = when (typeNode.type) {
        "user_type" -> typeNode.namedChildren.lastOrNull { it.type == "type_identifier" }?.textIn(request.sourceText)?.toString()
        "nullable_type", "parenthesized_type" -> typeNode.namedChildren.firstOrNull()?.let(::simpleTypeName)
        else -> null
    }

    /** The declared type of a `name: Type` declaration (parameter, class parameter, variable): the type node after the name. */
    private fun typeNodeAfterName(declaration: Node): Node? {
        val named = declaration.namedChildren
        val nameIdx = named.indexOfFirst { it.type == "simple_identifier" }
        return named.getOrNull(nameIdx + 1)?.takeIf { it.type in typeNodeTypes }
    }

    /** The simple names a type extends or implements, in source order: each `delegation_specifier`'s type. */
    private fun supertypesOf(typeDeclaration: Node): List<String> =
        typeDeclaration.namedChildren
            .filter { it.type == "delegation_specifier" }
            .mapNotNull { spec ->
                val typeNode = spec.namedChildren.firstOrNull { it.type in typeNodeTypes }
                    ?: spec.namedChildren.firstOrNull { it.type == "constructor_invocation" }
                        ?.namedChildren?.firstOrNull { it.type in typeNodeTypes }
                typeNode?.let(::simpleTypeName)
            }
            .distinct()

    /**
     * Property name to declared simple type for one type: its `val`/`var` constructor
     * parameters and its body's properties with an explicit type.
     */
    private fun memberTypesOf(typeDeclaration: Node, body: Node?): Map<String, String> {
        val out = HashMap<String, String>()
        typeDeclaration.namedChildren.firstOrNull { it.type == "primary_constructor" }
            ?.namedChildren?.filter { it.type == "class_parameter" && it.namedChildren.any { c -> c.type == "binding_pattern_kind" } }
            ?.forEach { param -> nameAndType(param)?.let { (n, t) -> out[n] = t } }
        body?.namedChildren?.filter { it.type == "property_declaration" }?.forEach { property ->
            property.namedChildren.firstOrNull { it.type == "variable_declaration" }
                ?.let(::nameAndType)?.let { (n, t) -> out[n] = t }
        }
        return out
    }

    private fun nameAndType(declaration: Node): Pair<String, String>? {
        val name = declaration.namedChildren.firstOrNull { it.type == "simple_identifier" }
            ?.textIn(request.sourceText)?.toString() ?: return null
        val type = typeNodeAfterName(declaration)?.let(::simpleTypeName) ?: return null
        return name to type
    }

    /**
     * The declared type of every name a call inside [declaration] could use as a receiver: the
     * enclosing type's properties, the declaration's parameters, and every local with an explicit
     * type anywhere in its body.
     *
     * Flat and scope-blind, as in the Java walker, and for the same reason: a hint is only ever a
     * filter over same-name candidates, and pass 2 falls back to the unfiltered set when it
     * matches none, so a shadowed name costs a missed narrowing, never a wrong edge.
     */
    private fun typeEnvironment(declaration: Node, memberTypes: Map<String, String>): Map<String, String> {
        val env = HashMap(memberTypes)
        declaration.namedChildren.firstOrNull { it.type == "function_value_parameters" }
            ?.namedChildren?.filter { it.type == "parameter" }
            ?.forEach { p -> nameAndType(p)?.let { (n, t) -> env[n] = t } }
        declaration.namedChildren.firstOrNull { it.type == "function_body" || it.type == "statements" }
            ?.let { collectLocals(it, env) }
        return env
    }

    private fun collectLocals(node: Node, env: MutableMap<String, String>) {
        for (child in node.namedChildren) {
            if (child.type == "property_declaration") {
                child.namedChildren.firstOrNull { it.type == "variable_declaration" }
                    ?.let(::nameAndType)?.let { (n, t) -> env[n] = t }
            }
            collectLocals(child, env)
        }
    }

    /**
     * Recurses through every descendant of [node] looking for `call_expression` nodes,
     * attributing each one found to [referringId]. See this class's doc comment for why no
     * boundary check against nested declarations is needed here.
     */
    private fun collectReferences(node: Node, referringId: NodeId, env: Map<String, String>, enclosingType: String?) {
        for (child in node.namedChildren) {
            if (child.type == "call_expression") emitCall(child, referringId, env, enclosingType)
            collectReferences(child, referringId, env, enclosingType)
        }
    }

    /**
     * `call_expression`'s namedChildren are `[calleeExpr, call_suffix]` (this grammar has
     * no fields at all -- see class doc). For a plain call the callee is a
     * `simple_identifier`; for a member call it's a `navigation_expression` whose namedChildren
     * are `[receiver, navigation_suffix]`, the suffix holding the `simple_identifier` being
     * invoked. Any other callee shape (an indexing/call chain, a lambda invoked directly, ...)
     * emits nothing rather than guess.
     */
    private fun emitCall(callExpression: Node, referringId: NodeId, env: Map<String, String>, enclosingType: String?) {
        val callee = callExpression.namedChildren.firstOrNull { it.type != "call_suffix" } ?: return
        when (callee.type) {
            "simple_identifier" -> {
                val name = callee.textIn(request.sourceText).toString()
                // Calling a named value is a call to its type's `invoke` operator: `loadOrder(id)`
                // on a `loadOrder: LoadOrderUseCase` reaches `LoadOrderUseCase.invoke`.
                val valueType = env[name]
                if (valueType != null) {
                    emitReference("invoke", callExpression, referringId, Receiver(type = valueType))
                } else {
                    emitReference(name, callExpression, referringId, Receiver())
                }
            }
            "navigation_expression" -> {
                val nameNode = memberNameNode(callee) ?: return
                val receiver = callee.namedChildren.firstOrNull { it.type != "navigation_suffix" }
                // The member name's line, not the call's: a call expression starts where its
                // receiver chain starts, so in a chain split over several lines every call would
                // report the chain's first line (the Java walker reads the name token for the
                // same reason).
                emitReference(
                    nameNode.textIn(request.sourceText).toString(), nameNode, referringId,
                    receiverOf(receiver, env, enclosingType)
                )
            }
            else -> Unit
        }
    }

    /** The member a `navigation_expression` selects: the identifier in its last `navigation_suffix`. */
    private fun memberNameNode(navigation: Node): Node? =
        navigation.namedChildren.lastOrNull { it.type == "navigation_suffix" }
            ?.namedChildren?.firstOrNull { it.type == "simple_identifier" }

    private fun memberName(navigation: Node): String? = memberNameNode(navigation)?.textIn(request.sourceText)?.toString()

    /** What the receiver expression of a member call says about its type. See the class doc. */
    private fun receiverOf(receiver: Node?, env: Map<String, String>, enclosingType: String?): Receiver = when (receiver?.type) {
        "simple_identifier" -> {
            val name = receiver.textIn(request.sourceText).toString()
            Receiver(type = env[name] ?: name.takeIf { it.first().isUpperCase() })
        }
        "this_expression" -> Receiver(type = enclosingType)
        "call_expression" -> Receiver(call = receiver.namedChildren.firstOrNull { it.type != "call_suffix" }?.let { inner ->
            when (inner.type) {
                "simple_identifier" -> inner.textIn(request.sourceText).toString()
                "navigation_expression" -> memberName(inner)
                else -> null
            }
        })
        "navigation_expression" -> {
            val property = memberName(receiver)
            val owner = receiver.namedChildren.firstOrNull { it.type != "navigation_suffix" }
            when (owner?.type) {
                // `this.orders.load()`: `orders` is a property of the enclosing type, typed in the file.
                "this_expression" -> Receiver(type = property?.let(env::get))
                "simple_identifier" -> {
                    val ownerName = owner.textIn(request.sourceText).toString()
                    Receiver(property = property, propertyOwner = env[ownerName] ?: ownerName.takeIf { it.first().isUpperCase() })
                }
                else -> Receiver(property = property)
            }
        }
        else -> Receiver()
    }

    private data class Receiver(
        val type: String? = null,
        val call: String? = null,
        val property: String? = null,
        val propertyOwner: String? = null
    )

    private fun emitReference(name: String, callNode: Node, referringId: NodeId, receiver: Receiver) {
        references.add(
            UnresolvedReference(
                referenceName = name,
                referringSymbolId = referringId,
                repoRelativePath = request.repoRelativePath,
                artifactId = request.artifactId,
                line = callNode.startPoint.row.toInt() + 1,
                receiverType = receiver.type,
                receiverCall = receiver.call,
                receiverProperty = receiver.property,
                receiverPropertyOwner = receiver.propertyOwner
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
}
