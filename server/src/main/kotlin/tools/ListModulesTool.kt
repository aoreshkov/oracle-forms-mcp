package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListModulesTool(service: FormsService) {
    addTool(
        name = "list_modules",
        description = "List every Oracle Forms module in the configured directory (.fmb, .mmb, " +
            ".pll, .olb) with its cache status: NOT_CACHED (call fetch_module first), CACHED " +
            "(ready to read), STALE (changed on disk — re-fetch), or SOURCE_MISSING (cached but " +
            "the file is gone). Also reports whether a pre-converted XML/pld sibling exists.",
        inputSchema = emptySchema(),
        title = "List Forms modules",
        outputSchema = outputSchemaOf<ModuleList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { _ ->
        guarded {
            toolResult(service.listModules())
        }
    }
}
