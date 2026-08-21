plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    // JavaParser resolves a call to a *declaration* using type information, which is the whole
    // point here: in Java the same method name is shared by hundreds of unrelated types, so a
    // text search cannot answer "where is this method called" even approximately.
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.2")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("MainKt") }
