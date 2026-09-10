package app.oreshkov.oracleformsmcp.server.resources

import app.oreshkov.oracleformsmcp.model.ModuleKey

/*
 * The `oracleforms://` URIs that make a SourceRef addressable, and the mapping back to the
 * cache-relative path it names.
 *
 * A SourceRef is deliberately layout-independent — it never carries an absolute path, because the
 * cache root is not a client's business and means nothing at all under the container image or the
 * HTTP transport. The cost was that a line range resolved to nothing a caller could open. These
 * URIs are the resolution: same layout independence, but addressable, by a resource read or by
 * `read_source`.
 *
 * Kept apart from ModuleResources so both it and FormsService can build URIs without depending on
 * each other.
 */

/** URI template matching every converted-text-form URI; see [moduleConvertedUri]. */
public const val MODULE_CONVERTED_URI_TEMPLATE: String = "oracleforms://{module}/converted"

/** URI template matching every PL/SQL sidecar URI; see [modulePlsqlUri]. */
public const val MODULE_PLSQL_URI_TEMPLATE: String = "oracleforms://{module}/plsql/{category}/{name}"

/** Cache subdirectory names a [app.oreshkov.oracleformsmcp.model.SourceRef] path can start with. */
internal const val CONVERTED_PREFIX: String = "converted/"
internal const val PLSQL_PREFIX: String = "plsql/"

/**
 * One path segment of a source URI. Segments reach handlers percent-decoded and
 * attacker-controlled, so what is accepted is stated positively: no separators, no dot-segments,
 * no control characters. Containment is still re-checked when the path is resolved — this is the
 * outer of the two guards, not the only one.
 */
private val SAFE_SEGMENT = Regex("""[A-Za-z0-9_$#][A-Za-z0-9_$#.~\-]*""")

/**
 * The module's converted text form (`*_fmb.xml`, or the `.pld` dump of a library).
 *
 * There is exactly one per module, so the URI names no file: which file it is belongs to the
 * cache's layout, which is what these URIs exist to hide.
 */
public fun moduleConvertedUri(key: ModuleKey): String = "oracleforms://$key/converted"

/** One extracted PL/SQL sidecar, e.g. `plsql/triggers/ORDERS.KEY-COMMIT.sql`. */
public fun modulePlsqlUri(key: ModuleKey, category: String, name: String): String =
    "oracleforms://$key/plsql/$category/$name"

/**
 * The URI addressing [refFile], a cache-relative `SourceRef` path, or `null` when the path is not
 * one of the shapes the resources cover — better to omit the field than to mint a URI that reads
 * as addressable and answers nothing.
 */
public fun sourceUri(key: ModuleKey, refFile: String): String? {
    val path = refFile.replace('\\', '/')
    if (path.startsWith(CONVERTED_PREFIX)) return moduleConvertedUri(key)
    if (!path.startsWith(PLSQL_PREFIX)) return null
    val (category, name) = path.removePrefix(PLSQL_PREFIX).split('/').takeIf { it.size == 2 }
        ?: return null
    if (!SAFE_SEGMENT.matches(category) || !SAFE_SEGMENT.matches(name)) return null
    return modulePlsqlUri(key, category, name)
}

/**
 * The cache-relative path a source URI names, or `null` when [uri] is not one.
 *
 * [convertedFile] is the module's own converted path from its index: the `converted` URI carries
 * no file name, so the index is what turns it back into one.
 */
public fun sourceRefPath(key: ModuleKey, uri: String, convertedFile: String): String? {
    val rest = uri.removePrefix("oracleforms://").takeIf { it != uri } ?: return null
    val segments = rest.split('/')
    if (!segments.firstOrNull().equals(key.toString(), ignoreCase = true)) return null
    return when {
        segments.size == 2 && segments[1] == "converted" -> convertedFile
        segments.size == 4 && segments[1] == "plsql" &&
            SAFE_SEGMENT.matches(segments[2]) && SAFE_SEGMENT.matches(segments[3]) ->
            "$PLSQL_PREFIX${segments[2]}/${segments[3]}"
        else -> null
    }
}

/** True when [segment] is safe to use as a path segment; the guard the resource handlers apply. */
internal fun isSafeSegment(segment: String): Boolean = SAFE_SEGMENT.matches(segment)

/**
 * MIME type for a cache-relative path. Converted forms are XML except for the `.pld` text dump a
 * PL/SQL library converts to; extracted PL/SQL is served as plain text, which every client renders.
 */
public fun sourceMimeType(refFile: String): String =
    if (refFile.endsWith(".xml", ignoreCase = true)) "application/xml" else "text/plain"
