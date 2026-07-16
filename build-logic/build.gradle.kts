plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins apply other plugins by id WITHOUT a version, so those
// plugins' gradle-plugin artifacts must be on this build's classpath.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    // ABI validation is built into the Kotlin Gradle plugin (see kmp-library's `abiValidation`),
    // so no separate binary-compatibility-validator artifact is needed on the classpath.
}
