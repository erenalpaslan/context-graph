dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:graph"))
    implementation(project(":modules:storage-sqlite"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jgrapht.core)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
