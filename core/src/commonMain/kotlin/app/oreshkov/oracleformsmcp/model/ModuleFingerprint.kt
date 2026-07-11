package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identity of the source file an index was built from. Forms files are mutable (unlike Maven
 * coordinates), so every cached [ModuleIndex] carries the fingerprint of the file it consumed;
 * a mismatch marks the entry stale.
 *
 * Staleness checks compare [sizeBytes]+[lastModifiedMillis] first (cheap) and confirm with
 * [sha256] so a bare `touch` doesn't force a reconvert.
 */
@Serializable
@SerialName("ModuleFingerprint")
public data class ModuleFingerprint(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val sha256: String,
)
