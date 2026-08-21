package io.contextgraph.treesitter

import io.contextgraph.core.ArtifactId
import io.contextgraph.treesitter.grammars.JavaLanguageSupport
import io.github.treesitter.ktreesitter.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

/**
 * Pass 1's half of receiver-type resolution: does the Java walker read the declared type of a
 * call's receiver off the declarations already in the file?
 *
 * This exists because name-only resolution has a measured ceiling -- `getId` is declared on 1001
 * types in Keycloak, so every call to one resolves to nothing rather than to something wrong.
 * The receiver's declared type is the cheapest input that breaks the tie, and these are the four
 * places a single file can supply it from.
 */
class JavaReceiverTypeTest : FunSpec({

    fun referencesIn(source: String): Map<String, String?> {
        val parser = Parser(JavaLanguageSupport.language())
        val tree = parser.parse(source)
        val extraction = JavaLanguageSupport.extract(
            SymbolExtractionRequest(
                tree = tree,
                source = source,
                sourceText = SourceText(source),
                repoRelativePath = "src/Caller.java",
                artifactId = ArtifactId("src/Caller.java"),
                extractorId = "tree-sitter",
                extractedAt = Instant.parse("2026-08-21T00:00:00Z")
            )
        )
        return extraction.references.associate { it.referenceName to it.receiverType }
    }

    test("a parameter's declared type is the receiver type of calls on it") {
        val refs = referencesIn(
            """
            class Caller {
                void run(UserSessionModel session) {
                    session.getId();
                }
            }
            """.trimIndent()
        )
        refs["getId"] shouldBe "UserSessionModel"
    }

    test("a local variable's declared type is used, with generics and arrays erased") {
        val refs = referencesIn(
            """
            class Caller {
                void run() {
                    java.util.List<UserModel> users = load();
                    users.stream();
                    RealmModel[] realms = all();
                    realms.clone();
                }
            }
            """.trimIndent()
        )
        // `java.util.List<UserModel>` erases to the simple name resolution matches on.
        refs["stream"] shouldBe "List"
        refs["clone"] shouldBe "RealmModel"
    }

    test("a field's declared type reaches calls in every method of the type, via this or bare name") {
        val refs = referencesIn(
            """
            class Caller {
                private KeycloakSession session;
                void a() { session.getContext(); }
                void b() { this.session.getProvider(); }
            }
            """.trimIndent()
        )
        refs["getContext"] shouldBe "KeycloakSession"
        refs["getProvider"] shouldBe "KeycloakSession"
    }

    test("a bare upper-case receiver is taken as a static call on that type; `this` is the enclosing type") {
        val refs = referencesIn(
            """
            class Caller {
                void run() {
                    ProviderConfigurationBuilder.create();
                    this.helper();
                }
                void helper() {}
            }
            """.trimIndent()
        )
        refs["create"] shouldBe "ProviderConfigurationBuilder"
        refs["helper"] shouldBe "Caller"
    }

    test("a receiver the file cannot answer for is left null rather than guessed") {
        val refs = referencesIn(
            """
            class Caller {
                void run() {
                    builder().property().name("x");
                    unknownLocal.doThing();
                }
            }
            """.trimIndent()
        )
        // A chained call's receiver is another call's return value -- inferring it needs the
        // cross-file symbol table pass 2 is still building, so pass 1 declines to guess.
        refs["name"] shouldBe null
        refs["property"] shouldBe null
        // Lower-case and undeclared: not a type name, not in scope, so no hint.
        refs["doThing"] shouldBe null
    }

    test("a chained call records the name of the call that produced its receiver") {
        val parser = Parser(JavaLanguageSupport.language())
        val source = """
            class Caller {
                void run(AuthenticationSessionModel authSession) {
                    authSession.getParentSession().getId();
                }
            }
        """.trimIndent()
        val extraction = JavaLanguageSupport.extract(
            SymbolExtractionRequest(
                tree = parser.parse(source),
                source = source,
                sourceText = SourceText(source),
                repoRelativePath = "src/Caller.java",
                artifactId = ArtifactId("src/Caller.java"),
                extractorId = "tree-sitter",
                extractedAt = Instant.parse("2026-08-21T00:00:00Z")
            )
        )
        val byName = extraction.references.associateBy { it.referenceName }

        // The outer call has no declared receiver type -- only the name of the call it chains off,
        // which pass 2 turns into a type by reading that method's declared return type.
        byName.getValue("getId").receiverType shouldBe null
        byName.getValue("getId").receiverCall shouldBe "getParentSession"
        // The inner call's own receiver is a parameter, so it is typed directly.
        byName.getValue("getParentSession").receiverType shouldBe "AuthenticationSessionModel"
        byName.getValue("getParentSession").receiverCall shouldBe null
    }

    test("a method's declared return type is recorded on its node, erased to a simple name") {
        val parser = Parser(JavaLanguageSupport.language())
        val source = """
            class Caller {
                java.util.List<UserModel> load() { return null; }
                void nothing() {}
            }
        """.trimIndent()
        val extraction = JavaLanguageSupport.extract(
            SymbolExtractionRequest(
                tree = parser.parse(source),
                source = source,
                sourceText = SourceText(source),
                repoRelativePath = "src/Caller.java",
                artifactId = ArtifactId("src/Caller.java"),
                extractorId = "tree-sitter",
                extractedAt = Instant.parse("2026-08-21T00:00:00Z")
            )
        )
        val returnsOf = extraction.nodes.associate { n ->
            n.label to (n.properties["returns"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        returnsOf["load"] shouldBe "List"
        returnsOf["nothing"] shouldBe "void"
    }

    test("a type records what it extends and implements, for both class and interface syntax") {
        // The golden fixture carries no inheritance, so this is the only thing holding the
        // supertype walk -- and pass 2 depends on it to decide that two differing return types
        // agree, and to emit Implements edges at all.
        val parser = Parser(JavaLanguageSupport.language())
        val source = """
            class UserSessionAdapter extends BaseAdapter implements UserSessionModel, Serializable {
            }
            interface UserSessionUpdater extends UserSessionModel, Closeable {
            }
        """.trimIndent()
        val extraction = JavaLanguageSupport.extract(
            SymbolExtractionRequest(
                tree = parser.parse(source),
                source = source,
                sourceText = SourceText(source),
                repoRelativePath = "src/Caller.java",
                artifactId = ArtifactId("src/Caller.java"),
                extractorId = "tree-sitter",
                extractedAt = Instant.parse("2026-08-21T00:00:00Z")
            )
        )
        fun supertypesOf(label: String) = extraction.nodes.first { it.label == label }
            .properties["supertypes"]
            .let { it as kotlinx.serialization.json.JsonArray }
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }

        // A class carries its superclass and interfaces in two differently-shaped fields.
        supertypesOf("UserSessionAdapter") shouldBe listOf("BaseAdapter", "UserSessionModel", "Serializable")
        // An interface's `extends_interfaces` is an unnamed child, not a labelled field.
        supertypesOf("UserSessionUpdater") shouldBe listOf("UserSessionModel", "Closeable")
    }
})
