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
    implementation(libs.cli.kit)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cli.kit.testing)
}

// Classpath fixtures under /fixtures/** come from the canonical demo dir at the repo root; the
// MCPB manifest is copied in too, so a test can compare its tool list with the registered tools.
tasks.processTestResources {
    from(rootDir.resolve("sample-forms")) { into("fixtures") }
    from(layout.projectDirectory.file("mcpb/manifest.template.json")) { into("mcpb") }
}

// Single source of truth for the MCP `Implementation` version: bake `project.version`
// (from gradle.properties) into a classpath resource read at startup, so the advertised
// server version can never drift from the build. See `ServerVersion` in the server sources.
val generateVersionResource = tasks.register<WriteProperties>("generateVersionResource") {
    destinationFile = layout.buildDirectory.file("generated/version/mcp-version.properties")
    property("version", providers.provider { project.version.toString() })
}
tasks.processResources {
    from(generateVersionResource) { into("META-INF/oracle-forms-mcp") }
}

// Let the version test assert the resource matches the build's project.version.
tasks.test {
    systemProperty("oracle-forms-mcp.expectedVersion", project.version.toString())
    // `-Pgolden.update=true` rewrites the golden files under src/test/resources/golden instead of
    // comparing against them. Read the diff before committing it.
    providers.gradleProperty("golden.update").orNull?.let { systemProperty("golden.update", it) }
}

application {
    mainClass = "app.oreshkov.oracleformsmcp.server.MainKt"
    // cli-kit calls native code (FFM) on Windows only: to enable escape sequences at a console, and
    // to tell a pipe whose reader left from a failed write. Granted here, it covers every channel
    // that runs the start script (installDist, the release zip, MCPB, the GHCR image). Without it
    // the JDK warns on stderr; under JEP 472's future deny default the kit carries on without it.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The start scripts choose their Java from OFMCP_JAVA_HOME first, then JAVA_HOME, then PATH — the
// MCPB bundle's Java setting is passed that way (see startscripts/ for why not as JAVA_HOME). Gradle
// offers no hook into its templates short of copying them whole, so a fragment is spliced in ahead
// of the line that resolves JAVA_HOME; if a Gradle upgrade rewords that line, the build fails here
// rather than shipping scripts that silently ignore the setting.
tasks.startScripts {
    val unixFragment = layout.projectDirectory.file("startscripts/ofmcp-java-home.sh")
    val windowsFragment = layout.projectDirectory.file("startscripts/ofmcp-java-home.bat")
    inputs.files(unixFragment, windowsFragment)
    doLast {
        fun splice(script: File, anchor: String, fragment: File) {
            val text = script.readText()
            check(anchor in text) { "$script no longer contains \"$anchor\"; move the OFMCP_JAVA_HOME splice" }
            val eol = if ("\r\n" in text) "\r\n" else "\n"
            val lines = fragment.readText().replace("\r\n", "\n").replace("\n", eol)
            script.writeText(text.replaceFirst(anchor, lines + anchor))
        }
        val task = this as CreateStartScripts
        val bin = task.outputDir!!
        splice(bin.resolve(task.applicationName!!), "# Determine the Java command", unixFragment.asFile)
        splice(bin.resolve("${task.applicationName}.bat"), "@rem Find java.exe", windowsFragment.asFile)
    }
}

// ---------------------------------------------------------------------------------------------
// MCPB bundle (one-click desktop install)
//
// A `.mcpb` is a zip with `manifest.json` at its root. The manifest's version comes from
// `project.version` via an `@version@` token in `mcpb/manifest.template.json`, so the bundle can
// never disagree with the build and the release process still has only TWO hand-bumped files
// (gradle.properties + server.json) — see `.claude/skills/release`.
//
// The bundle carries the plain `installDist` tree and therefore needs a JRE 25+ on the user's
// machine: the one its Java installation setting names (OFMCP_JAVA_HOME), else JAVA_HOME, else
// PATH. MCPB's `compatibility.runtimes` can only express Node/Python, so that prerequisite is
// documented in the manifest text and the README, not enforced by the format.
// ---------------------------------------------------------------------------------------------
val generateMcpbManifest = tasks.register<Copy>("generateMcpbManifest") {
    description = "Generates the MCPB manifest.json with the build's version baked in."
    val buildVersion = project.version.toString()
    inputs.property("version", buildVersion)

    from(layout.projectDirectory.file("mcpb/manifest.template.json")) { rename { "manifest.json" } }
    into(layout.buildDirectory.dir("mcpb-manifest"))
    filter { line -> line.replace("@version@", buildVersion) }
}

tasks.register<Zip>("packageMcpb") {
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
