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
import io.contextgraph.treesitter.SymbolExtraction
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.contextgraph.treesitter.textIn
import io.contextgraph.treesitter.withFqn
import io.github.treesitter.ktreesitter.Node
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared symbol extraction for TypeScript, TSX and JavaScript -- one walker for all three
 * dialects, per the slice's "three dialects, one implementation" mandate. [TypeScriptGrammar],
 * [TsxGrammar] and [JavaScriptGrammar] each just bind a JNI grammar and delegate here.
 *
 * Dialect differences are expressed as data, not duplicated code: TypeScript-only node
 * types (`interface_declaration`, `type_alias_declaration`, `enum_declaration`,
 * `public_field_definition`, `abstract_method_signature`, `required_parameter`/
 * `optional_parameter`, ...) simply never appear in a plain JavaScript parse tree, so the
 * one `when` dispatcher below handles all three grammars correctly without ever branching
 * on which dialect it is running under. Field names that genuinely differ between the JS
 * and TS grammars (`field_definition`'s `property` field vs. `public_field_definition`'s
 * `name` field) are handled with a fallback lookup, not a parallel code path.
 *
 * Node-type and field names were confirmed against the exact pinned grammar commits
 * (tree-sitter-javascript v0.23.1, tree-sitter-typescript v0.23.2) by dumping
 * `node-types.json` and `Node.sexp()` for fixtures covering every construct this walker
 * handles -- not guessed from memory of the grammars.
 *
 * Tree-sitter is error-resilient: nodes that don't match a `when` branch are silently
 * skipped, so a malformed construct costs only itself.
 *
 * ## Call-site emission (slice 20)
 * Every method/function body -- including a function-like value bound by
 * [extractVariableDeclarator] or [extractDefaultExportValue] (`const f = () => {...}`,
 * `export default function() {...}`) -- is walked, after its own declaration node is
 * built, for `call_expression` nodes; see [collectReferences] and [calleeName]. A call's
 * callee is either a plain `identifier` (`foo()`) or a `member_expression`, whose own
 * `property` field is the identifier actually being invoked -- the `object` field (the
 * receiver) is never inspected, per the slice's "record what the syntax says" rule. No
 * nested declaration inside a function/method body is ever separately extracted by this
 * walker (arrow functions and nested `function`s inside a body are never walked for their
 * own members), so no boundary check is needed to avoid double-attributing a call.
 */
internal class TsFamilySymbolExtractor(private val request: SymbolExtractionRequest) {
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val references = mutableListOf<UnresolvedReference>()

    fun extract(): SymbolExtraction {
        val fileId = DeclarationSiteId.file(request.repoRelativePath)
        for (child in request.tree.rootNode.namedChildren) {
            extractStatement(child, fileId, emptyList())
        }
        // Two statements can legitimately reference the same declaration-site ID -- most
        // commonly `export * from './x'` and `export { a } from './x'` in the same barrel
        // file, both scoped to the same target module. First occurrence wins rather than
        // emitting a duplicate node/edge pair.
        return SymbolExtraction(nodes.distinctBy { it.id.value }, edges.distinctBy { it.id.value }, references)
    }

    // ---- top-level / exported statement dispatch --------------------------------------

    private fun extractStatement(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        when (node.type) {
            "import_statement" -> extractImport(node, fileId)
            "export_statement" -> extractExportStatement(node, fileId, enclosingScope)
            "class_declaration", "abstract_class_declaration" -> extractClass(node, fileId, enclosingScope)
            "interface_declaration" -> extractInterface(node, fileId, enclosingScope)
            "type_alias_declaration" -> extractTypeAlias(node, fileId, enclosingScope)
            "enum_declaration" -> extractEnum(node, fileId, enclosingScope)
            "function_declaration", "generator_function_declaration" -> extractFunction(node, fileId, enclosingScope)
            "lexical_declaration", "variable_declaration" -> extractVariableDeclarators(node, fileId, enclosingScope)
            else -> Unit
        }
    }

    // ---- imports / re-exports (barrel files) -------------------------------------------

    /**
     * A regular `import ... from './mod'`: one [NodeType.Module] node per import statement,
     * named after the module specifier, plus an [EdgeType.Imports] edge. Every clause shape
     * (default, named, namespace, or a bare side-effect import) shares one `source` field,
     * so this needs no per-shape branching.
     */
    private fun extractImport(node: Node, fileId: NodeId) {
        val sourceNode = node.childByFieldName("source") ?: return
        addModuleRef(prefix = "import", modulePath = stringLiteralText(sourceNode), fileId = fileId, astNode = node)
    }

    /**
     * `export * from './x'`, `export * as ns from './x'` and `export { a, b as c } from './y'`
     * all carry a `source` field -- that alone identifies a re-export, regardless of which of
     * the three shapes it is. This is the barrel-file case the slice notes flag as the trap
     * that matters most: capture the source module name so slice 09 has something to resolve
     * later, even though nothing resolves it yet.
     */
    private fun extractReExport(node: Node, fileId: NodeId) {
        val sourceNode = node.childByFieldName("source") ?: return
        addModuleRef(prefix = "export", modulePath = stringLiteralText(sourceNode), fileId = fileId, astNode = node)
    }

    private fun addModuleRef(prefix: String, modulePath: String, fileId: NodeId, astNode: Node) {
        val refId = DeclarationSiteId.of(request.repoRelativePath, listOf("$prefix:$modulePath"))
        nodes.add(
            GraphNode(
                id = refId,
                type = NodeType.Module,
                label = modulePath,
                properties = withFqn(emptyMap(), namePrefix = null, nameChain = listOf(modulePath)),
                confidence = ConfidenceDefaults.IMPORT_RELATION,
                provenance = listOf(provenanceOf(astNode))
            )
        )
        edges.add(
            GraphEdge(
                id = EdgeId("imports:${fileId.value}:${refId.value}"),
                source = fileId,
                target = refId,
                type = EdgeType.Imports,
                confidence = ConfidenceDefaults.IMPORT_RELATION
            )
        )
    }

    private fun stringLiteralText(stringNode: Node): String {
        val fragments = stringNode.namedChildren.joinToString("") { it.textIn(request.sourceText).toString() }
        return fragments.ifEmpty { stringNode.textIn(request.sourceText).toString().trim('\'', '"', '`') }
    }

    // ---- export statement: re-export | default | named --------------------------------

    /**
     * `export_statement` has three mutually exclusive shapes in this grammar: a `source`
     * field (re-export -- see [extractReExport]), a `value` field (an anonymous default
     * export of an expression -- see [extractDefaultExportValue]), or a `declaration` field
     * (a named export, or a default export of something that already has its own name, e.g.
     * `export default function Foo() {}`; both parse identically here because
     * `function_declaration`/`class_declaration` require a name either way -- there is
     * nothing further to special-case). A bare `export { a };` with none of the three is a
     * re-export of an already-declared local binding and adds no new information.
     */
    private fun extractExportStatement(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        if (node.childByFieldName("source") != null) {
            extractReExport(node, fileId)
            return
        }
        val valueNode = node.childByFieldName("value")
        if (valueNode != null) {
            extractDefaultExportValue(node, valueNode, fileId)
            return
        }
        val declarationNode = node.childByFieldName("declaration")
        if (declarationNode != null) {
            extractStatement(declarationNode, fileId, enclosingScope)
        }
    }

    /**
     * `export default <expression>` where the expression has no name of its own (an arrow
     * function, an anonymous function/class expression, a HOC-wrapped function, or any other
     * expression such as a literal or identifier reference). Never left anonymous: a name is
     * taken from the unwrapped function if it has one, otherwise derived from the file name --
     * this is the one shape in the grammar where "usable name derived from the declaration or
     * the file" genuinely requires the file fallback.
     */
    private fun extractDefaultExportValue(node: Node, value: Node, fileId: NodeId) {
        val functionLike = if (value.type in FUNCTION_LIKE_TYPES) value else unwrapCallExpression(value)
        val name = functionLike?.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: fileBaseName()
        val isComponent = functionLike?.let { containsJsx(it) } ?: false
        val type = when {
            functionLike == null -> NodeType.Custom("Variable")
            isComponent -> NodeType.Component
            functionLike.type == "class" -> NodeType.Class
            else -> NodeType.Function
        }
        val declNode = declarationNode(
            astNode = node,
            type = type,
            label = name,
            idScopeChain = listOf(name),
            fqnNameChain = listOf(name),
            extraProps = mapOf("kind" to JsonPrimitive("default_export"))
        )
        nodes.add(declNode)
        addContains(fileId, declNode.id)
        functionLike?.childByFieldName("body")?.let { collectReferences(it, declNode.id) }
    }

    // ---- classes / interfaces / type aliases / enums -----------------------------------

    /** Decorators (`@Injectable()`, `@Component(...)`) are extra fielded children on the
     * class node itself -- they never displace `name`/`body`, so a decorated class extracts
     * exactly like an undecorated one with no special-casing needed. */
    private fun extractClass(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val kind = if (node.type == "abstract_class_declaration") "abstract_class" else "class"
        val classNode = declarationNode(node, NodeType.Class, simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive(kind)))
        nodes.add(classNode)
        addContains(parentId(fileId, enclosingScope), classNode.id)

        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            when (member.type) {
                "method_definition" -> extractMethod(member, scopeChain, classNode.id, hasBody = true)
                "abstract_method_signature" -> extractMethod(member, scopeChain, classNode.id, hasBody = false)
                "public_field_definition", "field_definition" -> extractProperty(member, scopeChain, classNode.id)
                else -> Unit
            }
        }
    }

    private fun extractInterface(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val ifaceNode = declarationNode(node, NodeType.Custom("Interface"), simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive("interface")))
        nodes.add(ifaceNode)
        addContains(parentId(fileId, enclosingScope), ifaceNode.id)

        val body = node.childByFieldName("body") ?: return
        for (member in body.namedChildren) {
            when (member.type) {
                "property_signature" -> extractProperty(member, scopeChain, ifaceNode.id)
                "method_signature" -> extractMethod(member, scopeChain, ifaceNode.id, hasBody = false)
                else -> Unit
            }
        }
    }

    private fun extractTypeAlias(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val aliasNode = declarationNode(node, NodeType.Custom("TypeAlias"), simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive("type_alias")))
        nodes.add(aliasNode)
        addContains(parentId(fileId, enclosingScope), aliasNode.id)
    }

    private fun extractEnum(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val scopeChain = enclosingScope + simpleName
        val enumNode = declarationNode(node, NodeType.Custom("Enum"), simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive("enum")))
        nodes.add(enumNode)
        addContains(parentId(fileId, enclosingScope), enumNode.id)

        val body = node.childByFieldName("body") ?: return
        val plainMembers = body.childrenByFieldName("name")
        val assignedMembers = body.namedChildren.filter { it.type == "enum_assignment" }.mapNotNull { it.childByFieldName("name") }
        for (member in plainMembers + assignedMembers) {
            val memberName = member.textIn(request.sourceText).toString()
            val memberNode = declarationNode(
                member, NodeType.Custom("Field"), memberName,
                scopeChain + memberName, scopeChain + memberName,
                mapOf("kind" to JsonPrimitive("enum_member"))
            )
            nodes.add(memberNode)
            addContains(enumNode.id, memberNode.id)
        }
    }

    private fun extractMethod(node: Node, enclosingScope: List<String>, parentId: NodeId, hasBody: Boolean) {
        val nameNode = node.childByFieldName("name") ?: return
        val simpleName = nameNode.textIn(request.sourceText).toString()
        val paramTypes = paramSignature(node.childByFieldName("parameters"))
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        val bodyNode = node.childByFieldName("body")
        val isComponent = bodyNode?.let { containsJsx(it) } ?: false
        val type = if (isComponent) NodeType.Component else NodeType.Method
        val kind = if (hasBody) "method" else "abstract_method"
        val methodNode = declarationNode(node, type, simpleName, enclosingScope + segment, enclosingScope + simpleName, mapOf("kind" to JsonPrimitive(kind)))
        nodes.add(methodNode)
        addContains(parentId, methodNode.id)
        if (bodyNode != null) collectReferences(bodyNode, methodNode.id)
    }

    /** `field_definition` (JS) names its member via the `property` field; `public_field_definition`
     * (TS) uses `name` -- the one place the two grammars genuinely disagree on field naming,
     * handled as a fallback lookup rather than a parallel code path. */
    private fun extractProperty(node: Node, enclosingScope: List<String>, parentId: NodeId) {
        val nameNode = node.childByFieldName("name") ?: node.childByFieldName("property") ?: return
        val simpleName = nameNode.textIn(request.sourceText).toString()
        val propNode = declarationNode(
            node, NodeType.Custom("Field"), simpleName,
            enclosingScope + simpleName, enclosingScope + simpleName,
            mapOf("kind" to JsonPrimitive("property"))
        )
        nodes.add(propNode)
        addContains(parentId, propNode.id)
    }

    private fun extractFunction(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        val simpleName = node.childByFieldName("name")?.textIn(request.sourceText)?.toString() ?: return
        val paramTypes = paramSignature(node.childByFieldName("parameters"))
        val segment = "$simpleName(${paramTypes.joinToString(",")})"
        val bodyNode = node.childByFieldName("body")
        val isComponent = bodyNode?.let { containsJsx(it) } ?: false
        val type = if (isComponent) NodeType.Component else NodeType.Function
        val kind = if (node.type == "generator_function_declaration") "generator_function" else "function"
        val fnNode = declarationNode(node, type, simpleName, enclosingScope + segment, enclosingScope + simpleName, mapOf("kind" to JsonPrimitive(kind)))
        nodes.add(fnNode)
        addContains(parentId(fileId, enclosingScope), fnNode.id)
        if (bodyNode != null) collectReferences(bodyNode, fnNode.id)
    }

    // ---- const/let/var declarators: arrow functions, HOCs, styled-components ----------

    private fun extractVariableDeclarators(node: Node, fileId: NodeId, enclosingScope: List<String>) {
        for (declarator in node.namedChildren.filter { it.type == "variable_declarator" }) {
            extractVariableDeclarator(declarator, fileId, enclosingScope)
        }
    }

    /**
     * The single most common declaration form in modern TS/JS: `const foo = () => {}`. A
     * naive function_declaration-only walk misses this entirely, so this is handled as a
     * first-class case, not a fallback.
     *
     * Also handles the traps the slice notes call out explicitly: a HOC-wrapped component
     * (`export const X = memo(() => {})`) is unwrapped one or more call layers via
     * [unwrapCallExpression] so the inner function is still found and the outer const's name
     * is still used as the declaration's name; a tagged-template value with no function
     * anywhere inside it (`export const Styled = styled.div\`...\``) falls back to a naming
     * convention (leading uppercase => component) since there is no function body to inspect.
     * Destructuring bindings (`const { a, b } = require(...)`) are skipped -- no single name
     * to attach a declaration-site identity to.
     */
    private fun extractVariableDeclarator(declarator: Node, fileId: NodeId, enclosingScope: List<String>) {
        val nameNode = declarator.childByFieldName("name") ?: return
        if (nameNode.type != "identifier") return
        val simpleName = nameNode.textIn(request.sourceText).toString()
        val rawValue = declarator.childByFieldName("value") ?: return
        val scopeChain = enclosingScope + simpleName
        val parent = parentId(fileId, enclosingScope)

        val functionLike = if (rawValue.type in FUNCTION_LIKE_TYPES) rawValue else unwrapCallExpression(rawValue)
        when {
            functionLike != null -> {
                val isComponent = containsJsx(functionLike)
                val type = when {
                    isComponent -> NodeType.Component
                    functionLike.type == "class" -> NodeType.Class
                    else -> NodeType.Function
                }
                val kind = when (functionLike.type) {
                    "class" -> "class_expression"
                    "arrow_function" -> "arrow_function"
                    else -> "function_expression"
                }
                val declNode = declarationNode(declarator, type, simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive(kind)))
                nodes.add(declNode)
                addContains(parent, declNode.id)
                functionLike.childByFieldName("body")?.let { collectReferences(it, declNode.id) }
            }
            rawValue.type == "call_expression" -> {
                val isComponent = simpleName.firstOrNull()?.isUpperCase() == true
                val type = if (isComponent) NodeType.Component else NodeType.Custom("Variable")
                val declNode = declarationNode(declarator, type, simpleName, scopeChain, scopeChain, mapOf("kind" to JsonPrimitive("value")))
                nodes.add(declNode)
                addContains(parent, declNode.id)
            }
            else -> Unit
        }
    }

    /**
     * Looks through call expression(s) wrapping a function/class -- `memo(() => {})`,
     * `forwardRef(function Inner() {})`, even a double call like `connect(mapState)(Foo)` up
     * to a small fixed depth -- for the function/class actually being wrapped. Returns `null`
     * for a call with no such argument (e.g. a tagged template like `styled.div\`...\``, whose
     * `arguments` field is a `template_string`, not an `arguments` node, so it never matches).
     */
    private fun unwrapCallExpression(value: Node, depth: Int = 0): Node? {
        if (depth > 3) return null
        if (value.type in FUNCTION_LIKE_TYPES) return value
        if (value.type != "call_expression") return null
        val args = value.childByFieldName("arguments") ?: return null
        if (args.type != "arguments") return null
        for (arg in args.namedChildren) {
            if (arg.type in FUNCTION_LIKE_TYPES) return arg
            if (arg.type == "call_expression") {
                val inner = unwrapCallExpression(arg, depth + 1)
                if (inner != null) return inner
            }
        }
        return null
    }

    /** Recursively scans for a JSX element/fragment anywhere in the subtree -- the signal
     * that a function or method is a React component, independent of naming convention. */
    private fun containsJsx(node: Node): Boolean {
        if (node.type in JSX_TYPES) return true
        return node.namedChildren.any { containsJsx(it) }
    }

    private fun fileBaseName(): String =
        request.repoRelativePath.substringAfterLast('/').substringBeforeLast('.')

    // ---- shared helpers ------------------------------------------------------------------

    private fun paramSignature(parameters: Node?): List<String> {
        if (parameters == null) return emptyList()
        return parameters.namedChildren.map { paramSegment(it) }
    }

    private fun paramSegment(param: Node): String = when (param.type) {
        "required_parameter", "optional_parameter" -> {
            val typeText = param.childByFieldName("type")?.namedChildren?.firstOrNull()?.textIn(request.sourceText)?.toString()
            typeText
                ?: param.childByFieldName("pattern")?.textIn(request.sourceText)?.toString()
                ?: param.childByFieldName("name")?.textIn(request.sourceText)?.toString()
                ?: "?"
        }
        "assignment_pattern" -> param.childByFieldName("left")?.textIn(request.sourceText)?.toString() ?: "?"
        "rest_pattern" -> "..." + (param.namedChildren.firstOrNull()?.textIn(request.sourceText)?.toString() ?: "")
        else -> param.textIn(request.sourceText).toString()
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
     * A `call_expression`'s `function` field is either a plain `identifier` (`foo()`) or a
     * `member_expression`, whose own `property` field is the identifier actually being
     * invoked -- the `object` field (the receiver) is never inspected. Any other callable
     * shape (a parenthesized expression, an IIFE's own function expression, an optional
     * chain, ...) returns `null` rather than guess.
     */
    private fun calleeName(callExpression: Node): String? {
        val function = callExpression.childByFieldName("function") ?: return null
        return when (function.type) {
            "identifier" -> function.textIn(request.sourceText).toString()
            "member_expression" -> function.childByFieldName("property")
                ?.takeIf { it.type == "property_identifier" || it.type == "private_property_identifier" }
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
    ): GraphNode = GraphNode(
        id = DeclarationSiteId.of(request.repoRelativePath, idScopeChain),
        type = type,
        label = label,
        properties = withFqn(extraProps, namePrefix = null, nameChain = fqnNameChain),
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

    companion object {
        private val FUNCTION_LIKE_TYPES = setOf("arrow_function", "function_expression", "generator_function", "class")
        private val JSX_TYPES = setOf("jsx_element", "jsx_self_closing_element", "jsx_fragment")
    }
}
