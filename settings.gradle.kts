rootProject.name = "Oracle Forms MCP"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Provisions the JDK 25 toolchain (and the daemon JVM pinned in gradle/gradle-daemon-jvm.properties)
// on machines that do not have one installed. The settings `plugins {}` block is evaluated before
// the version catalog exists, so this one version cannot live in libs.versions.toml.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":core", ":server")
