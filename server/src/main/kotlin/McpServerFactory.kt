package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.ModuleConverters
import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.prompts.registerExplainModulePrompt
import app.oreshkov.oracleformsmcp.server.resources.ModuleIndexResources
import app.oreshkov.oracleformsmcp.server.resources.registerModuleAnnotationsTemplate
import app.oreshkov.oracleformsmcp.server.resources.registerModuleIndexTemplate
import app.oreshkov.oracleformsmcp.server.resources.registerSourceTemplates
import app.oreshkov.oracleformsmcp.server.tools.registerAnnotateElementTool
import app.oreshkov.oracleformsmcp.server.tools.registerFetchModuleTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetBlockTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetElementAnnotationsTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetModuleOverviewTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetObjectXmlTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetProgramUnitTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetTriggerTool
import app.oreshkov.oracleformsmcp.server.tools.registerListBlocksTool
import app.oreshkov.oracleformsmcp.server.tools.registerListModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerListProgramUnitsTool
import app.oreshkov.oracleformsmcp.server.tools.registerListTriggersTool
import app.oreshkov.oracleformsmcp.server.tools.registerReadSourceTool
import app.oreshkov.oracleformsmcp.server.tools.registerRelateElementsTool
import app.oreshkov.oracleformsmcp.server.tools.registerRemoveAnnotationTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchAnnotationsTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.Closeable
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

const val SERVER_NAME: String = "oracle-forms-mcp"

/**
 * What the server tells a client about itself, sent once at initialization.
 *
 * Three things belong here that no single tool description can carry, because each is about
 * choosing *between* the tools — or about not reaching past them:
 *
 * 1. **The traversal order.** Discovery is a filtered list, not a directory walk, and everything
 *    else reads a module that was fetched first.
 * 2. **The staleness hazard of reading the files directly.** The converted XML and the extracted
 *    PL/SQL live in a cache this server owns and fingerprints; text read straight off disk can
 *    describe a form that no longer exists, which is the drift `STALE` exists to report. The habit
 *    a model brings — grep, sed, a directory walk — has a call here for every case, and the
 *    mapping is stated so the cheaper-looking path is not also the wrong one.
 * 3. **That an empty PL/SQL body is not a fact.** It is the one result a reader cannot sanity-check
 *    from the outside, so `bodySource` is named up front rather than left to be discovered.
 *
 * Kept as a named constant so it can be asserted on (`ServerInstructionsTest`): a client sees it
 * once, and a quiet edit that dropped a hazard would be invisible in every other test.
 */
internal val SERVER_INSTRUCTIONS: String =
    "Serves the content of Oracle Forms modules (.fmb forms, .mmb menus, .pll PL/SQL libraries, " +
        ".olb object libraries) from a configured directory. " +
        "Start with list_modules (filtered and paged) to find a module, then fetch_module to " +
        "convert and index it; the other tools then read the cached index — blocks, items, " +
        "triggers, program units, PL/SQL bodies, search — with get_object_xml as the escape hatch " +
        "for raw attributes. search_source searches one module; search_modules searches every " +
        "fetched module at once, which is how a call between forms, a shared :GLOBAL variable or a " +
        "subclassed block is traced, and it reports the modules it could not reach rather than " +
        "leaving them silently out of the answer. " +
        "Read through these tools rather than through the files. A module reported as STALE is one " +
        "to call fetch_module on again: its staleReason says whether it changed on disk " +
        "(SOURCE_CHANGED) or was indexed by an older build of this server (INDEX_OUTDATED), whose " +
        "answers can be wrong in ways that read as plausible. That is exactly the hazard " +
        "of opening the converted XML yourself: what is on disk may describe a form that is no " +
        "longer the one being served, and the paths in a result are cache-relative, not host " +
        "paths. Where a habit reaches for a shell there is a call: an item's properties and " +
        "prompts are get_block, not grep; one object's raw attributes are get_object_xml, not sed; " +
        "the lines around a hit are read_source; and a question that spans modules is " +
        "search_modules, not a directory walk. " +
        "An empty PL/SQL body is never a fact on its own: bodySource tells an own body from an " +
        "inherited one (the code lives in the module the 'inherited' pointer names, and the hint " +
        "names the call that reaches it), from a resolved one, and from a genuinely empty one. " +
        "You can also record durable meta-information about elements with annotate_element " +
        "(notes/tags/summaries/classifications) and relate_elements (cross-references); it " +
        "persists across sessions and re-indexing and is surfaced inline by the read tools " +
        "and via get_element_annotations / search_annotations."

/** Runtime configuration shared by both transports, populated from the CLI flags in `Main`. */
data class ServerConfig(
    val formsDir: Path,
    val cacheDir: Path = OnDiskModuleCache.defaultCacheRoot(),
    val annotationsDir: Path = OnDiskAnnotationStore.defaultRoot(),
    val oracleHome: String? = System.getenv("ORACLE_HOME"),
    val conversionTimeout: Duration = 120.seconds,
    /**
     * Site-supplied converter run instead of `frmf2xml`, from `--convert-command` — a whole command
     * line with its arguments, split into argv by the converter and spawned without a shell.
     * Operator configuration only — never reachable from a tool argument. Takes precedence over
     * [oracleHome] when set.
     */
    val convertCommand: String? = null,
    /**
     * One flat directory holding every module's converted XML (and `.pld`) text form, from
     * `--converted-dir`. It is the directory the converter *writes into* — it runs with this as its
     * working directory — not somewhere files are moved afterwards. Operator configuration only.
     * `null` keeps each text form inside its module's own cache entry under [cacheDir].
     */
    val convertedDir: Path? = null,
)

/**
 * A configured MCP [server] plus the core collaborators it was built from. The [service] and
 * [cache] are exposed for embedders and tests; [close] stops the log forwarder.
 */
class McpServerHandle(
    val server: Server,
    val service: FormsService,
    val cache: ModuleCache,
    val annotationStore: AnnotationStore,
    private val logForwarderScope: CoroutineScope,
) : Closeable {
    override fun close() {
        logForwarderScope.cancel()
        routeKermitToSlf4j() // drop the forwarder writer for the closed server
    }
}

/**
 * Composition root: constructs the `core` implementations (scanner, converter, parser, cache),
 * builds the MCP [Server], and registers every tool/resource/prompt. Both transports build the
 * server through here so the feature set is identical everywhere.
 */
object McpServerFactory {

    fun create(config: ServerConfig): McpServerHandle {
        routeKermitToSlf4j()
        val cache = OnDiskModuleCache(config.cacheDir)
        val annotationStore = OnDiskAnnotationStore(config.annotationsDir)
        val service = FormsService(
            scanner = FormsDirectoryScannerImpl(config.formsDir),
            converter = ModuleConverters.forEnvironment(
                oracleHome = config.oracleHome,
                formsDir = config.formsDir,
                timeout = config.conversionTimeout,
                convertCommand = config.convertCommand,
            ),
            parser = FormsModuleParser(),
            cache = cache,
            annotationStore = annotationStore,
            formsDir = config.formsDir,
            // Either configured converter can read binaries; only copy-mode cannot.
            binaryConversion = !config.convertCommand.isNullOrBlank() || !config.oracleHome.isNullOrBlank(),
            convertedDir = config.convertedDir,
        )

        // Bounds how many per-module index resources `resources/list` ever carries; see
        // ModuleIndexResources for why an unbounded set is a client-side overflow.
        val indexResources = ModuleIndexResources(service)

        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = ServerVersion.value),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(listChanged = true, subscribe = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                    // Presence (any non-null value) advertises notifications/message support;
                    // the SDK then handles logging/setLevel per session.
                    logging = EmptyJsonObject,
                ),
            ),
            instructions = SERVER_INSTRUCTIONS,
        ) {
            registerListModulesTool(service)
            registerFetchModuleTool(service) { key ->
                // Newly indexed modules appear in resources/list without a restart, oldest
                // registration evicted once the bound is reached.
                indexResources.register(this, key)
            }
            registerGetModuleOverviewTool(service)
            registerListBlocksTool(service)
            registerGetBlockTool(service)
            registerListTriggersTool(service)
            registerGetTriggerTool(service)
            registerListProgramUnitsTool(service)
            registerGetProgramUnitTool(service)
            registerSearchSourceTool(service)
            registerSearchModulesTool(service)
            registerReadSourceTool(service)
            registerGetObjectXmlTool(service)
            // Annotation layer: the model writes durable meta-information about elements, and the
            // read tools above surface it inline (see the DTO `annotations` fields).
            registerAnnotateElementTool(service)
            registerRelateElementsTool(service)
            registerGetElementAnnotationsTool(service)
            registerSearchAnnotationsTool(service)
            registerRemoveAnnotationTool(service)
            registerExplainModulePrompt(service)
            // Direct addressing of *any* cached index, however large the cache. This — not the
            // bounded static registrations above — is what makes every cached module reachable,
            // which is why no startup snapshot of the cache is registered: on a real forms
            // directory that snapshot was `resources/list`'s overflow.
            registerModuleIndexTemplate(service)
            registerModuleAnnotationsTemplate(service)
            // ...and of the files behind every SourceRef, so a line range resolves to something
            // a client can open rather than to a path only this server knows how to find.
            registerSourceTemplates(service)
        }

        val logForwarderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        attachMcpLogForwarder(server, logForwarderScope)

        return McpServerHandle(
            server = server,
            service = service,
            cache = cache,
            annotationStore = annotationStore,
            logForwarderScope = logForwarderScope,
        )
    }
}
