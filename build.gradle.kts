plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    // Maven coordinates only. Kotlin package names stay `io.contextgraph.*` -- Maven Central
    // verifies the *groupId* namespace, and `io.contextgraph` would have required proving
    // ownership of contextgraph.io, which this project does not hold. `io.github.<user>` is
    // verified against the GitHub account instead.
    group = "io.github.erenalpaslan"

    // release.yml passes the git tag through as `-Pversion=0.2.0`, so the tag is the single
    // source of truth for a release and no file is edited to cut one. Gradle applies `-P`
    // before this block runs, so assigning unconditionally would clobber it -- only fill in
    // the local-development fallback when nothing was passed.
    if (version == Project.DEFAULT_VERSION) {
        version = "0.1.0-SNAPSHOT"
    }

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
