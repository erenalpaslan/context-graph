dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
