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
    implementation(libs.ktor.server.sse)
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

// ---------------------------------------------------------------------------------------------
// MCPB bundle (one-click desktop install)
//
// A `.mcpb` is a zip with `manifest.json` at its root. The manifest's version comes from
// `project.version` via an `@version@` token in `mcpb/manifest.template.json`, so the bundle can
// never disagree with the build and the release process still has only TWO hand-bumped files
// (gradle.properties + server.json) — see `.claude/skills/release`.
//
// The bundle carries the plain `installDist` tree and therefore needs a JRE 21+ on the user's
// PATH. MCPB's `compatibility.runtimes` can only express Node/Python, so that prerequisite is
// documented in the manifest text and the README, not enforced by the format.
// ---------------------------------------------------------------------------------------------
val generateMcpbManifest by tasks.registering(Copy::class) {
    description = "Generates the MCPB manifest.json with the build's version baked in."
    val buildVersion = project.version.toString()
    inputs.property("version", buildVersion)

    from(layout.projectDirectory.file("mcpb/manifest.template.json")) { rename { "manifest.json" } }
    into(layout.buildDirectory.dir("mcpb-manifest"))
    filter { line -> line.replace("@version@", buildVersion) }
}

val packageMcpb by tasks.registering(Zip::class) {
    description = "Packages the server distribution as an installable .mcpb bundle."
    group = "distribution"

    archiveFileName = "oracle-forms-mcp-${project.version}.mcpb"
    destinationDirectory = layout.buildDirectory.dir("mcpb")
    // The sha256 of this archive goes into server.json at release time; keep it reproducible.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(generateMcpbManifest)
    from(tasks.named("installDist")) {
        into("server")
        // Built on Windows the source tree carries no unix permission bits, so the launcher
        // scripts would land in the zip non-executable and the bundle would fail to start on
        // macOS/Linux. Set them explicitly rather than inheriting from the filesystem.
        eachFile { permissions { unix(if (relativePath.segments.contains("bin")) "0755" else "0644") } }
        dirPermissions { unix("0755") }
    }
    from(rootDir.resolve("assets")) {
        into("assets")
        include("icon.svg", "icon-512.png")
    }
}
