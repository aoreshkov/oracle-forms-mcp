package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One module discovered in the forms directory. [binaryPath] is the `.fmb`/`.mmb`/`.pll`/`.olb`
 * when present; [preConvertedPath] the `*_fmb.xml`/`.pld` sibling when present. At least one is
 * non-null. The fingerprint source is the binary when `ORACLE_HOME` conversion is available,
 * otherwise the pre-converted file (whichever the pipeline actually consumes).
 */
@Serializable
@SerialName("ScannedModule")
public data class ScannedModule(
    val key: ModuleKey,
    val binaryPath: String? = null,
    val preConvertedPath: String? = null,
) {
    init {
        require(binaryPath != null || preConvertedPath != null) {
            "ScannedModule for $key needs a binary or a pre-converted file"
        }
    }
}

/** Cache state of a module as reported by `list_modules`. */
@Serializable
@SerialName("ModuleStatus")
public enum class ModuleStatus {
    /** Never fetched (no cache entry). */
    NOT_CACHED,

    /** Cached and the fingerprint still matches the file on disk. */
    CACHED,

    /** Cached, but the source file changed since indexing — re-fetch to heal. */
    STALE,

    /** A cache entry exists but its source file is gone from the forms directory. */
    SOURCE_MISSING,
}
