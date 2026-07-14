package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.ModuleConverters
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.prompts.registerExplainModulePrompt
import app.oreshkov.oracleformsmcp.server.resources.addModuleIndexResource
import app.oreshkov.oracleformsmcp.server.resources.registerModuleIndexTemplate
import app.oreshkov.oracleformsmcp.server.tools.registerFetchModuleTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetBlockTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetModuleOverviewTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetObjectXmlTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetProgramUnitTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetTriggerTool
import app.oreshkov.oracleformsmcp.server.tools.registerListBlocksTool
import app.oreshkov.oracleformsmcp.server.tools.registerListModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerListProgramUnitsTool
import app.oreshkov.oracleformsmcp.server.tools.registerListTriggersTool
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
import kotlinx.coroutines.runBlocking

const val SERVER_NAME: String = "oracle-forms-mcp"

/** Runtime configuration shared by both transports, populated from the CLI flags in `Main`. */
data class ServerConfig(
    val formsDir: Path,
    val cacheDir: Path = OnDiskModuleCache.defaultCacheRoot(),
    val oracleHome: String? = System.getenv("ORACLE_HOME"),
    val conversionTimeout: Duration = 120.seconds,
)

/**
 * A configured MCP [server] plus the core collaborators it was built from. The [service] and
 * [cache] are exposed for embedders and tests; [close] stops the log forwarder.
 */
class McpServerHandle(
    val server: Server,
    val service: FormsService,
    val cache: ModuleCache,
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
        val service = FormsService(
            scanner = FormsDirectoryScannerImpl(config.formsDir),
            converter = ModuleConverters.forEnvironment(
                oracleHome = config.oracleHome,
                formsDir = config.formsDir,
                timeout = config.conversionTimeout,
            ),
            parser = FormsModuleParser(),
            cache = cache,
            formsDir = config.formsDir,
            oracleConversion = !config.oracleHome.isNullOrBlank(),
        )

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
            instructions = "Serves the content of Oracle Forms modules (.fmb forms, .mmb menus, " +
                ".pll PL/SQL libraries, .olb object libraries) from a configured directory. " +
                "Call list_modules to discover modules and their status, then fetch_module to " +
                "convert and index one; the other tools read the cached index (blocks, items, " +
                "triggers, program units, PL/SQL source, search, raw object XML). A module " +
                "reported as STALE changed on disk — call fetch_module again to re-index it.",
        ) {
            registerListModulesTool(service)
            registerFetchModuleTool(service) { key ->
                // Newly indexed modules appear in resources/list without a restart.
                addModuleIndexResource(service, key)
            }
            registerGetModuleOverviewTool(service)
            registerListBlocksTool(service)
            registerGetBlockTool(service)
            registerListTriggersTool(service)
            registerGetTriggerTool(service)
            registerListProgramUnitsTool(service)
            registerGetProgramUnitTool(service)
            registerSearchSourceTool(service)
            registerGetObjectXmlTool(service)
            registerExplainModulePrompt(service)
            // Direct addressing of any cached index; the per-module resources below stay for
            // discoverability via resources/list.
            registerModuleIndexTemplate(service)
        }
        // One index resource per already-cached module (startup snapshot).
        runBlocking { cache.list() }.forEach { server.addModuleIndexResource(service, it) }

        val logForwarderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        attachMcpLogForwarder(server, logForwarderScope)

        return McpServerHandle(
            server = server,
            service = service,
            cache = cache,
            logForwarderScope = logForwarderScope,
        )
    }
}
