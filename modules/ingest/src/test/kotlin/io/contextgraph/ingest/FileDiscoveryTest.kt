package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Regression coverage for the root-level exclude-matching defect: a repo-relative path with no
 * directory separator in it (e.g. a `build/` directory sitting directly under the repo root) was
 * silently indexed because `glob:**&#47;build` never matches a bare `build` — the JDK glob matcher
 * requires a real separator after a `**&#47;` prefix. These tests exercise the real [FileDiscovery]
 * output, not the matcher internals, so a regression here means real indexing runs pick up
 * `.git/`, `build/`, etc. at the repo root again.
 */
class FileDiscoveryTest : FunSpec({

    fun tempRepo(): Path = Files.createTempDirectory("file-discovery-test")

    fun discoverRelativePaths(root: Path, config: ContextGraphConfig = ContextGraphConfig()): List<String> =
        runBlocking {
            FileDiscovery(config).discover(root).toList()
                .map { root.relativize(it).toString().replace('\\', '/') }
        }

    test("a root-level excluded directory (build/) is not indexed") {
        val root = tempRepo()
        root.resolve("build").createDirectories()
        root.resolve("build/output.txt").writeText("generated")
        root.resolve("src").createDirectories()
        root.resolve("src/Main.kt").writeText("fun main() {}")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("src/Main.kt")
    }

    test("a nested excluded directory (sub/build/) is not indexed") {
        val root = tempRepo()
        root.resolve("sub/build").createDirectories()
        root.resolve("sub/build/output.txt").writeText("generated")
        root.resolve("sub/Keep.kt").writeText("class Keep")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("sub/Keep.kt")
    }

    test("a root-level dotdir (.git/) is not indexed") {
        val root = tempRepo()
        root.resolve(".git").createDirectories()
        root.resolve(".git/config").writeText("[core]")
        root.resolve("README.md").writeText("# demo")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("README.md")
    }

    test("a pattern that legitimately starts with **/ still excludes both root-level and nested occurrences") {
        val root = tempRepo()
        root.resolve("app.log").writeText("root log")
        root.resolve("sub").createDirectories()
        root.resolve("sub/app.log").writeText("nested log")
        root.resolve("Main.kt").writeText("fun main() {}")

        val config = ContextGraphConfig(excludePatterns = listOf("**/*.log"))
        val found = discoverRelativePaths(root, config)

        found shouldContainExactlyInAnyOrder listOf("Main.kt")
    }

    test("root-level and nested build directories are both pruned while unrelated files are kept") {
        val root = tempRepo()
        root.resolve("build").createDirectories()
        root.resolve("build/a.class").writeText("x")
        root.resolve("sub/build").createDirectories()
        root.resolve("sub/build/b.class").writeText("x")
        root.resolve("sub/Keep.kt").writeText("class Keep")
        root.resolve("Top.kt").writeText("class Top")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("sub/Keep.kt", "Top.kt")
    }

    test("a bare .git FILE (git worktree gitlink, not a directory) is not indexed") {
        val root = tempRepo()
        // In a git worktree, `.git` is a plain file containing a pointer to the real git dir
        // (e.g. "gitdir: /path/to/main/.git/worktrees/name"), not a directory. It never reaches
        // preVisitDirectory, so it must still be caught when tested as a file.
        root.resolve(".git").writeText("gitdir: /some/other/repo/.git/worktrees/example\n")
        root.resolve("README.md").writeText("# demo")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("README.md")
    }

    test("source files are indexed however they are named, including 'password' and 'secret'") {
        val root = tempRepo()
        root.resolve("src").createDirectories()
        // Real Keycloak filenames. Matching `.*password.*` against a bare filename dropped 227 of
        // its 8,145 Java files -- silently, at discovery, so nothing downstream could report a gap.
        root.resolve("src/Argon2PasswordHashProvider.java").writeText("class Argon2PasswordHashProvider {}")
        root.resolve("src/ClientCredentialsGrantType.java").writeText("class ClientCredentialsGrantType {}")
        root.resolve("src/ClientIdAndSecretAuthenticator.java").writeText("class ClientIdAndSecretAuthenticator {}")
        root.resolve("src/PasswordPolicy.kt").writeText("class PasswordPolicy")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf(
            "src/Argon2PasswordHashProvider.java",
            "src/ClientCredentialsGrantType.java",
            "src/ClientIdAndSecretAuthenticator.java",
            "src/PasswordPolicy.kt"
        )
    }

    test("META-INF/services provider-configuration files are indexed whatever interface they name") {
        val root = tempRepo()
        val services = root.resolve("services/src/main/resources/META-INF/services")
        services.createDirectories()
        // Real Keycloak resources. A ServiceLoader provider-configuration file is NAMED after the
        // fully-qualified interface it registers implementations for, so its name routinely
        // contains words like "password" or "credential" that describe a Java type, never the
        // file's contents. It also has no extension: `substringAfterLast(".")` on
        // `org.keycloak.credential.hash.PasswordHashProviderFactory` yields
        // "passwordhashproviderfactory", which is not a code extension, so the name-substring rule
        // used to drop the file at discovery -- while its sibling AuthenticatorFactory, whose name
        // happens to contain no trigger word, was indexed. That asymmetry is the bug: whether a
        // service registration reaches the graph depended on the spelling of the interface.
        services.resolve("org.keycloak.credential.hash.PasswordHashProviderFactory")
            .writeText("org.keycloak.credential.hash.Pbkdf2PasswordHashProviderFactory")
        services.resolve("org.keycloak.authentication.AuthenticatorFactory")
            .writeText("org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf(
            "services/src/main/resources/META-INF/services/org.keycloak.credential.hash.PasswordHashProviderFactory",
            "services/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory"
        )
    }

    test("META-INF/services registrations are indexed even when the interface's simple name collides with a credential extension") {
        val root = tempRepo()
        val services = root.resolve("META-INF/services")
        services.createDirectories()
        // Real JDK/std-lib-shaped interfaces. Each one's simple name -- the text
        // `substringAfterLast(".")` reads off the FQN filename -- happens to spell a word in
        // `sensitiveExtensions` once lower-cased ("Key" -> "key", "Cert" -> "cert"). Before this
        // fix, that spelling coincidence alone dropped the file, exactly as
        // `PasswordHashProviderFactory` once did for the unrelated reason pinned above -- the same
        // bug, narrowed to a different set of interface names rather than eliminated.
        services.resolve("java.security.Key").writeText("com.example.MyKeyImpl")
        services.resolve("org.example.spi.Cert").writeText("com.example.MyCertImpl")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf(
            "META-INF/services/java.security.Key",
            "META-INF/services/org.example.spi.Cert"
        )
    }

    test("actual credential files are still excluded, whatever the exemption above allows") {
        val root = tempRepo()
        root.resolve(".env").writeText("API_KEY=live")
        root.resolve("server.pem").writeText("-----BEGIN PRIVATE KEY-----")
        root.resolve("tls.key").writeText("-----BEGIN PRIVATE KEY-----")
        // Not source code, so the name-substring rule still applies to it.
        root.resolve("passwords.txt").writeText("hunter2")
        // The META-INF/services exemption is about the name-substring rule only: a real credential
        // parked in that directory still carries a credential extension, and that rule is checked
        // first and unconditionally, so the exemption opens no hole.
        root.resolve("META-INF/services").createDirectories()
        root.resolve("META-INF/services/server.pem").writeText("-----BEGIN PRIVATE KEY-----")
        root.resolve("Main.kt").writeText("fun main() {}")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("Main.kt")
    }
})
