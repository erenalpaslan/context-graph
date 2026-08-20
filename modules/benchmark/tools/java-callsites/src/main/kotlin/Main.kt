import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.utils.SourceRoot
import java.io.File
import kotlin.system.exitProcess

/**
 * Derives ground truth for set-valued benchmark questions on a Java codebase: every call site of a
 * given method, resolved by type rather than by name.
 *
 * This exists because the TypeScript equivalent showed that text search is already exact on a
 * codebase with distinctive names -- across 19 excalidraw symbols, grep missed nothing and was
 * perfectly precise on 12 of them, so no graph could have demonstrated an advantage there however
 * good it was. Java is the opposite case and the one the claim deserves to be tested on: in this
 * corpus `getId` alone appears on 10,721 lines, because hundreds of unrelated types declare it.
 * "Where is *this type's* getId called" is a question a text search cannot answer even
 * approximately, and a call graph either answers it or has no claim to make.
 *
 * Resolution is source-only: no build, no dependency jars. Calls into third-party libraries
 * therefore fail to resolve and are skipped, which costs nothing here -- the questions are about
 * calls between the project's own types, and those are exactly what resolves.
 *
 * Usage:
 *   java-callsites <repoRoot> <minRefs> <maxRefs> <sourceRoot>...
 *
 * Emits JSON on stdout: one entry per (declaring type, method) whose resolved call-site count
 * falls in range, with the full set of call sites as `path:line` relative to repoRoot.
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println("usage: java-callsites <repoRoot> <minRefs> <maxRefs> <sourceRoot>...")
        exitProcess(2)
    }
    val repoRoot = File(args[0]).canonicalFile
    val minRefs = args[1].toInt()
    val maxRefs = args[2].toInt()
    val sourceRoots = args.drop(3).map { File(repoRoot, it).canonicalFile }.filter { it.isDirectory }

    if (sourceRoots.isEmpty()) {
        System.err.println("no source roots exist under $repoRoot")
        exitProcess(1)
    }
    System.err.println("source roots: ${sourceRoots.size}")

    val typeSolver = CombinedTypeSolver().apply {
        add(ReflectionTypeSolver())
        sourceRoots.forEach { add(JavaParserTypeSolver(it)) }
    }
    val configuration = ParserConfiguration()
        .setSymbolResolver(JavaSymbolSolver(typeSolver))
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)

    // Keyed by "qualifiedType#method(arity)" so overloads and same-named methods on unrelated
    // types never collapse into one another -- the collapse a name-based search cannot avoid.
    val callSites = HashMap<String, MutableSet<String>>()
    var parsed = 0
    var resolved = 0
    var unresolved = 0

    for (root in sourceRoots) {
        val sourceRoot = SourceRoot(root.toPath(), configuration)
        val results = try {
            sourceRoot.tryToParseParallelized()
        } catch (e: Exception) {
            System.err.println("parse failed under $root: ${e.message}")
            continue
        }
        for (result in results) {
            val unit = result.result.orElse(null) ?: continue
            val storage = unit.storage.orElse(null) ?: continue
            val path = repoRoot.toPath().relativize(storage.path).toString().replace(File.separatorChar, '/')
            if (path.contains("/test/") || path.contains("/tests/")) continue
            parsed++

            unit.findAll(MethodCallExpr::class.java).forEach { call ->
                val declaration = try {
                    call.resolve()
                } catch (_: Throwable) {
                    // Unresolvable calls are overwhelmingly into third-party jars this run
                    // deliberately does not have. Counted, not hidden: a run where almost nothing
                    // resolves would produce a confidently empty ground truth, which is worse than
                    // no ground truth at all.
                    unresolved++
                    return@forEach
                }
                resolved++
                val owner = try {
                    declaration.declaringType().qualifiedName
                } catch (_: Throwable) {
                    return@forEach
                }
                if (!owner.startsWith("org.keycloak")) return@forEach
                // The *name token's* line, not the call expression's. A MethodCallExpr begins at
                // the start of its whole receiver chain, so in a fluent builder
                // (`Builder.create().property().name("x")`) every call in the chain reports the
                // chain's first line. That silently produced ground truth nobody could match:
                // three questions scored exactly 0.00 against answers that were pointing at the
                // right code.
                val line = call.name.begin.map { it.line }.orElse(-1)
                if (line < 0) return@forEach
                val key = "$owner#${declaration.name}/${declaration.numberOfParams}"
                callSites.getOrPut(key) { LinkedHashSet() }.add("$path:$line")
            }
        }
        System.err.println("parsed=$parsed resolved=$resolved unresolved=$unresolved after $root")
    }

    val selected = callSites
        .filterValues { it.size in minRefs..maxRefs }
        .entries
        .sortedByDescending { it.value.size }

    val json = buildString {
        append("{\n")
        append("  \"parsedFiles\": $parsed,\n")
        append("  \"resolvedCalls\": $resolved,\n")
        append("  \"unresolvedCalls\": $unresolved,\n")
        append("  \"candidates\": [\n")
        selected.forEachIndexed { index, (key, sites) ->
            val (owner, rest) = key.split("#", limit = 2)
            val (method, arity) = rest.split("/", limit = 2)
            append("    {\"declaringType\": \"$owner\", \"method\": \"$method\", \"arity\": $arity, ")
            append("\"referenceCount\": ${sites.size}, \"references\": [")
            append(sites.sorted().joinToString(", ") { "\"$it\"" })
            append("]}")
            if (index != selected.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n}\n")
    }
    print(json)
}
