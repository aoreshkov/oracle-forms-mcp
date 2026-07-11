plugins {
    id("kmp-library")
}

// Classpath fixtures under /fixtures/** come from the canonical demo dir at the repo root.
tasks.named<Copy>("jvmTestProcessResources") {
    from(rootDir.resolve("sample-forms")) { into("fixtures") }
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Annotations (`@Serializable`/`@SerialName`) + `KSerializer` live in `-core`.
                // The `-json` encoder is only pulled in where we actually serialize (JVM impls).
                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                // Conversion (external frmf2xml/frmcmp processes), cache IO, and StAX parsing all
                // live here. XML parsing uses the JDK's javax.xml.stream — no extra dependency.
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kermit)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
