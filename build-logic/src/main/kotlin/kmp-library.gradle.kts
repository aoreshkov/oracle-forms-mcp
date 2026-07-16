@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // Bundled in the Kotlin Gradle plugin (already on the build-logic classpath), so it applies
    // version-less like the multiplatform plugin — avoids loading Kotlin twice with an explicit
    // version at the subproject level. Every kmp-library module returns `@Serializable` DTOs.
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

// `libs` type-safe accessor is unavailable in precompiled script plugins on Gradle 9;
// resolve the catalog at configuration time instead.
val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm()
    jvmToolchain(21)

    // Published-library policy: every public declaration must carry an explicit visibility and
    // return type. Pairs with the ABI validation below so the surface is intentional, not inferred.
    explicitApi()

    // KGP-native ABI validation (the standalone binary-compatibility-validator plugin is
    // discontinued and folded into the Kotlin Gradle plugin). Experimental in Kotlin 2.4.x, hence
    // the file-level opt-in. Wires `checkKotlinAbi` into `check` and keeps the reference dump in
    // `api/core.api` (task `updateKotlinAbi` refreshes it).
    abiValidation {
    }

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
            }
        }
    }
}
