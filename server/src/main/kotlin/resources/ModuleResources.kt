package app.oreshkov.oracleformsmcp.server.resources

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

/** Stable, parseable URI for a cached module's index. */
fun moduleIndexUri(key: ModuleKey): String = "oracleforms://$key/index"

/** URI template matching every module index URI; see [moduleIndexUri]. */
const val MODULE_INDEX_URI_TEMPLATE: String = "oracleforms://{module}/index"

/** URI template matching every module annotations URI; see [registerModuleAnnotationsTemplate]. */
const val MODULE_ANNOTATIONS_URI_TEMPLATE: String = "oracleforms://{module}/annotations"

/** Module segment of a resource URI: `NAME.ext` — never path separators or dot-segments. */
private val MODULE_SEGMENT = Regex("""[A-Za-z0-9_$#][A-Za-z0-9_$#\-]*\.(fmb|mmb|pll|olb)""", RegexOption.IGNORE_CASE)

/**
 * Registers the `oracleforms://{module}/index` resource template so clients can address any
 * cached module's index directly, without first discovering it via `resources/list`. Exact-URI
 * resources registered by [addModuleIndexResource] take priority; the template answers reads for
 * cached modules that lack a static registration and gives unfetched modules a "call fetch_module
 * first" error instead of a bare resource-not-found.
 */
fun Server.registerModuleIndexTemplate(service: FormsService) {
    addResourceTemplate(
        uriTemplate = MODULE_INDEX_URI_TEMPLATE,
        name = "Forms module index",
        description = "Parsed index of a fetched Forms module: blocks with items, triggers, " +
            "program units, LOVs, record groups, windows, canvases, and the object refs backing " +
            "get_object_xml. The module must have been fetched with fetch_module first. " +
            "The segment is 'NAME.ext', e.g. 'ORDERS.fmb'.",
        mimeType = "application/json",
    ) { request, variables ->
        // Template variables are attacker-controlled URI segments and end up in cache paths.
        val segment = variables.getValue("module")
        require(MODULE_SEGMENT.matches(segment)) { "Invalid module segment in resource URI: '$segment'" }
        val key = ModuleKey.parse(segment)
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = toolJson.encodeToString(service.index(key)),
                    uri = request.uri,
                    mimeType = "application/json",
                )
            )
        )
    }
}

/**
 * Registers the `oracleforms://{module}/annotations` resource template: reading it returns the
 * AI/user-supplied notes, tags, summaries, classifications, and relations stored about a module's
 * elements (see [FormsService.moduleAnnotations]), each drift-flagged against the current source.
 * Kept separate from the index resource so the derived index and the asserted annotations never mix.
 */
fun Server.registerModuleAnnotationsTemplate(service: FormsService) {
    addResourceTemplate(
        uriTemplate = MODULE_ANNOTATIONS_URI_TEMPLATE,
        name = "Forms module annotations",
        description = "AI/user-supplied notes, tags, summaries, classifications, and relations " +
            "stored about a fetched module's elements. The segment is 'NAME.ext', e.g. 'ORDERS.fmb'.",
        mimeType = "application/json",
    ) { request, variables ->
        // Template variables are attacker-controlled URI segments and end up in store paths.
        val segment = variables.getValue("module")
        require(MODULE_SEGMENT.matches(segment)) { "Invalid module segment in resource URI: '$segment'" }
        val key = ModuleKey.parse(segment)
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = toolJson.encodeToString(service.moduleAnnotations(key)),
                    uri = request.uri,
                    mimeType = "application/json",
                )
            )
        )
    }
}

/**
 * Exposes one MCP resource per cached module: reading `oracleforms://NAME.ext/index` returns the
 * [app.oreshkov.oracleformsmcp.model.ModuleIndex] JSON. Registered at startup for already-cached
 * modules and again after each successful `fetch_module`, so `resources/list` stays current
 * without a restart (the server emits `listChanged` notifications on registration).
 */
fun Server.addModuleIndexResource(service: FormsService, key: ModuleKey) {
    val uri = moduleIndexUri(key)
    if (uri in resources) return // warm re-fetch: already registered, don't re-notify
    addResource(
        uri = uri,
        name = "$key index",
        description = "Parsed index of $key: blocks with items, triggers, program units, LOVs, " +
            "record groups, windows, canvases, and object refs.",
        mimeType = "application/json",
    ) { request ->
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = toolJson.encodeToString(service.index(key)),
                    uri = request.uri,
                    mimeType = "application/json",
                )
            )
        )
    }
}
