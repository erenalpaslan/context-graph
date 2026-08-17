package io.contextgraph.treesitter

import io.contextgraph.core.GraphNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The property key every declaration node's fully-qualified name is stored under. One
 * constant, not a string literal repeated in every language's grammar file.
 */
const val FQN_PROPERTY = "fqn"

/**
 * Builds an `fqn` value: [namePrefix] (the language's package/namespace/module, or `null`
 * if it has none) joined with [nameChain] (the plain declaration-name path from the
 * outermost enclosing declaration down to this one, e.g. `["UserService", "save"]`) with
 * `.`. Parameter types are deliberately EXCLUDED -- unlike [DeclarationSiteId], which must
 * disambiguate overloads, fqn is a *grouping* key: overloads of one method, or an
 * extension/partial declaration split across files (ObjC header/impl, Swift extensions,
 * Kotlin extension functions, TS declaration merging), are meant to share one fqn so a
 * later grouping pass (slice 11) can find every sibling from a single string.
 */
fun buildFqn(namePrefix: String?, nameChain: List<String>): String =
    (listOfNotNull(namePrefix) + nameChain).joinToString(".")

/**
 * Attaches [FQN_PROPERTY] to [extraProps]. Every language's `extract()` must route a
 * declaration node's properties through this -- not build the map by hand -- so a node
 * can never be emitted with a missing or differently-spelled fqn key. See [buildFqn] for
 * the format.
 */
fun withFqn(
    extraProps: Map<String, JsonElement>,
    namePrefix: String?,
    nameChain: List<String>
): Map<String, JsonElement> =
    extraProps + mapOf(FQN_PROPERTY to JsonPrimitive(buildFqn(namePrefix, nameChain)))

/**
 * Guards the contract above: every node [DeclarationSiteId.of] produced (i.e. every node
 * whose ID is not exactly [DeclarationSiteId.file]'s -- the only ID shape without `#`)
 * must carry a non-blank [FQN_PROPERTY]. Call this from a language's own snapshot test
 * (see `JavaGrammarSnapshotTest`) so a language that forgets fqn on some declaration kind
 * fails its own test immediately, rather than silently breaking slice 11's grouping five
 * rounds later.
 */
fun requireFqnOnDeclarations(nodes: List<GraphNode>) {
    val missing = nodes
        .filter { '#' in it.id.value }
        .filter { node -> ((node.properties[FQN_PROPERTY] as? JsonPrimitive)?.content).isNullOrBlank() }
        .map { it.id.value }
    check(missing.isEmpty()) {
        "Declaration node(s) missing a non-blank '$FQN_PROPERTY' property: $missing"
    }
}
