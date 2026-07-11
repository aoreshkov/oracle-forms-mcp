plugins {
    id("jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kermit)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Classpath fixtures under /fixtures/** come from the canonical demo dir at the repo root.
tasks.processTestResources {
    from(rootDir.resolve("sample-forms")) { into("fixtures") }
}

application {
    mainClass = "app.oreshkov.oracleformsmcp.server.MainKt"
}
