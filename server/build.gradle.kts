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

// Single source of truth for the MCP `Implementation` version: bake `project.version`
// (from gradle.properties) into a classpath resource read at startup, so the advertised
// server version can never drift from the build. See `ServerVersion` in the server sources.
val generateVersionResource by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/version/mcp-version.properties")
    property("version", providers.provider { project.version.toString() })
}
tasks.processResources {
    from(generateVersionResource) { into("META-INF/oracle-forms-mcp") }
}

// Let the version test assert the resource matches the build's project.version.
tasks.test {
    systemProperty("oracle-forms-mcp.expectedVersion", project.version.toString())
}

application {
    mainClass = "app.oreshkov.oracleformsmcp.server.MainKt"
}
