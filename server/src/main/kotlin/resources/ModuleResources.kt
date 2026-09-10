package app.oreshkov.oracleformsmcp.server.resources

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Registers the two templates that make a `SourceRef` openable:
 * `oracleforms://{module}/converted` for the module's converted text form, and
 * `oracleforms://{module}/plsql/{category}/{name}` for one extracted PL/SQL sidecar.
 *
 * Templates rather than per-file resources on purpose. A form produces one sidecar per trigger,
 * program unit and menu command — hundreds per module — and `resources/list` has no cursor in the
 * Kotlin SDK, so registering them individually would re-create exactly the overflow the bounded
 * index registrations were introduced to stop. A template addresses all of them and lists as one
 * entry.
 *
 * Contents are capped, with the cut stated inside the text: a resource read returns bytes and
 * nothing else, so a silently truncated file would be indistinguishable from a short one.
 */
fun Server.registerSourceTemplates(service: FormsService) {
    addResourceTemplate(
        uriTemplate = MODULE_CONVERTED_URI_TEMPLATE,
        name = "Forms module converted text",
        description = "The converted text form of a fetched module — Forms2XML output for a form, " +
            "menu or object library, or the .pld dump of a PL/SQL library. This is the file every " +
            "SourceRef line range points into. Large files are capped; read a range with " +
            "read_source. The segment is 'NAME.ext', e.g. 'ORDERS.fmb'.",
        mimeType = "application/xml",
    ) { request, variables ->
        val key = moduleKeyOf(variables)
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = service.readSourceResource(key, moduleConvertedUri(key)),
                    uri = request.uri,
                    mimeType = sourceMimeType(service.index(key).convertedFile),
                )
            )
        )
    }
    addResourceTemplate(
        uriTemplate = MODULE_PLSQL_URI_TEMPLATE,
        name = "Forms module PL/SQL source",
        description = "One block of PL/SQL extracted from a fetched module during parsing: " +
            "category is 'triggers', 'program-units' or 'menu-items', and name is the sidecar file " +
            "name recorded in a result's 'source.file'. Capped; read a range with read_source.",
        mimeType = "text/plain",
    ) { request, variables ->
        val key = moduleKeyOf(variables)
        // Untrusted, percent-decoded URI segments; validated here and containment-checked again
        // when the path is resolved against the module's cache directory.
        val category = variables.getValue("category")
        val name = variables.getValue("name")
        require(isSafeSegment(category) && isSafeSegment(name)) {
            "Invalid PL/SQL path in resource URI: '$category/$name'"
        }
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = service.readSourceResource(key, modulePlsqlUri(key, category, name)),
                    uri = request.uri,
                    mimeType = "text/plain",
                )
            )
        )
    }
}

/** The module a template match names, rejecting a segment that is not a plain `NAME.ext`. */
private fun moduleKeyOf(variables: Map<String, String>): ModuleKey {
    val segment = variables.getValue("module")
    require(MODULE_SEGMENT.matches(segment)) { "Invalid module segment in resource URI: '$segment'" }
    return ModuleKey.parse(segment)
}

/** How many per-module index resources [ModuleIndexResources] keeps registered at once. */
const val MAX_MODULE_INDEX_RESOURCES: Int = 50

/**
 * Exposes an MCP resource per *recently fetched* module — reading `oracleforms://NAME.ext/index`
 * returns the [app.oreshkov.oracleformsmcp.model.ModuleIndex] JSON — bounded to the
 * [limit] most recent, oldest evicted first.
 *
 * The bound is the point. `resources/list` has no cursor in the Kotlin SDK, and the client issues
 * it on its own before the model does anything: one resource per cached module turned a warm cache
 * over a real forms directory (thousands of modules) into a several-hundred-kilobyte response —
 * the same overflow `list_modules` had, on a call nobody chose to make. Nothing is lost by
 * evicting: [registerModuleIndexTemplate] addresses *every* cached module through the same URI, so
 * an evicted module stays readable, and `list_modules` remains the discovery path. What the static
 * registrations buy is visibility in `resources/list` for the modules this session actually
 * touched, which is exactly what a bounded most-recent set holds.
 *
 * Registration happens from `fetch_module`, and tool handlers run concurrently (MCP SDK 0.15+), so
 * every mutation of the recency set is serialised on [mutex].
 */
class ModuleIndexResources(
    private val service: FormsService,
    private val limit: Int = MAX_MODULE_INDEX_RESOURCES,
) {
    /** Registered index URIs, oldest first — insertion order is the eviction order. */
    private val registered = LinkedHashSet<String>()
    private val mutex = Mutex()

    /** Registers [key]'s index resource on [server], evicting the oldest beyond [limit]. */
    suspend fun register(server: Server, key: ModuleKey) {
        val uri = moduleIndexUri(key)
        mutex.withLock {
            if (registered.remove(uri)) {
                registered += uri // warm re-fetch: refresh recency, the resource is already there
                return
            }
            server.addModuleIndexResource(service, key)
            registered += uri
            while (registered.size > limit) {
                val evicted = registered.first()
                registered -= evicted
                // Unregisters only; an in-flight read already holds its handler, and later reads
                // fall through to the URI template, which serves the identical content.
                server.removeResource(evicted)
            }
        }
    }

    /** The index URIs currently registered, oldest first. Test/diagnostic view of the bound. */
    internal fun registeredUris(): List<String> = registered.toList()
}

/**
 * Registers one module's index resource. Prefer [ModuleIndexResources.register], which keeps the
 * registered set bounded; this is the raw registration it performs.
 */
fun Server.addModuleIndexResource(service: FormsService, key: ModuleKey) {
    val uri = moduleIndexUri(key)
    if (uri in resources) return // already registered, don't re-notify
    try {
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
    } catch (_: IllegalArgumentException) {
        // Since MCP SDK 0.15 a duplicate registration throws instead of silently replacing. The
        // check above is check-then-act and handlers run concurrently, so two callers that bypass
        // ModuleIndexResources' mutex can both reach here; losing that race is not a fetch
        // failure, and the winner registered an identical resource. Deliberately not
        // remove-then-re-add: that would emit a spurious listChanged and briefly unregister a
        // resource being read.
    }
}
