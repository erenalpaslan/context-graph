import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.maven.publish)
}

application {
    mainClass.set("io.contextgraph.cli.MainKt")
    // Without this the launcher is named after the Gradle project directory, so `installDist`
    // produced `build/install/cli/bin/cli` while every document and every example calls the
    // tool `contextgraph`.
    applicationName = "contextgraph"
}

// The published artifact is a single self-contained jar carrying every dependency *and* the
// tree-sitter natives for both supported platforms, because both of its consumers -- the
// Homebrew formula and the composite GitHub Action -- want one file to download and run with
// `java -jar`, not a classpath to assemble.
tasks.shadowJar {
    archiveBaseName.set("contextgraph-cli")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

mavenPublishing {
    // `cli` is the Gradle project name (it comes from the directory), which would be a
    // hopelessly generic artifactId on a public repository.
    coordinates(group.toString(), "contextgraph-cli", version.toString())

    // Central Portal is the publishing route for namespaces verified after the OSSRH
    // shutdown. Signing is not optional there -- every artifact must carry a detached GPG
    // signature -- and the plugin also produces the sources/javadoc jars Central requires.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // Signing only when a key is actually configured (release.yml supplies it as
    // ORG_GRADLE_PROJECT_signingInMemoryKey). Unconditional signing makes even
    // `publishToMavenLocal` fail with "no configured signatory" on any machine without the
    // release key, which is every developer machine -- and Central rejects unsigned uploads
    // anyway, so the release path cannot silently skip it.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // Central requires a javadoc jar to be present; it does not require it to have content,
    // and this is a CLI whose interface is its command line, not its classes. An empty one
    // keeps Dokka out of the release path.
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))

    pom {
        name.set("ContextGraph CLI")
        description.set(
            "Knowledge graph for code and docs: indexes a repository into a queryable graph " +
                "and serves it to agents over MCP."
        )
        url.set("https://github.com/erenalpaslan/context-graph")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/erenalpaslan/context-graph/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("erenalpaslan")
                name.set("Eren Alpaslan")
                url.set("https://github.com/erenalpaslan")
            }
        }
        scm {
            url.set("https://github.com/erenalpaslan/context-graph")
            connection.set("scm:git:https://github.com/erenalpaslan/context-graph.git")
            developerConnection.set("scm:git:ssh://git@github.com/erenalpaslan/context-graph.git")
        }

        // The POM generated from the `java` component advertises runtime dependencies on the
        // sibling modules (io.github.erenalpaslan:core, :ingest, ...), which are deliberately
        // NOT published -- publishing them would commit their APIs, which this project
        // explicitly does not want to do, so anyone resolving that POM from Central would fail
        // on the first missing module. What consumers actually fetch is the shaded jar, which
        // already contains every one of them; the honest dependency list for this coordinate
        // is therefore empty.
        //
        // Node names arrive either as a QName or as a plain String depending on how the node
        // was built, and a cast that assumes one silently matches nothing when it gets the
        // other -- leaving the dependencies in place with no build failure. Both forms are
        // handled, and `withXml` then asserts the block actually went, because a stripping
        // step that quietly no-ops is worse than none: it would look done in review.
        withXml {
            val root = asNode()
            fun localName(node: groovy.util.Node): String {
                val name = node.name()
                return (name as? groovy.namespace.QName)?.localPart
                    ?: name.toString().substringAfterLast('}')
            }

            val dependencyBlocks = root.children()
                .filterIsInstance<groovy.util.Node>()
                .filter { localName(it) == "dependencies" }
            dependencyBlocks.forEach { root.remove(it) }

            check(root.children().filterIsInstance<groovy.util.Node>().none { localName(it) == "dependencies" }) {
                "Failed to strip <dependencies> from the published POM; it would advertise " +
                    "sibling modules that are deliberately not published."
            }
        }
    }
}

// Gradle module metadata would undo the POM surgery above: its `runtimeElements` variant
// lists the same nine unpublished sibling coordinates, and a Gradle consumer prefers module
// metadata over the POM (the generated POM says so itself, in the `published-with-gradle-metadata`
// marker). Disabling it makes the stripped POM authoritative for Gradle and Maven alike.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:ingest"))
    implementation(project(":modules:extractors"))
    implementation(project(":modules:graph"))
    implementation(project(":modules:storage-sqlite"))
    implementation(project(":modules:query"))
    implementation(project(":modules:mcp-server"))
    implementation(project(":modules:report"))
    implementation(project(":modules:visualization"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    // describe-modules (slice 15): DescribeModulesCommand constructs LiteLlmModuleDescriber()
    // directly, whose default constructor argument type (HttpClientEngine, from ktor-client-cio)
    // must be resolvable on this module's own compile classpath -- ingest's dependency on it is
    // `implementation`-scoped (deliberately not leaked as part of ingest's public API) so it does
    // not carry through transitively.
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.logback)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
