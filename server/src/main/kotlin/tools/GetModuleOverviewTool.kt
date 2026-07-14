package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ModuleOverview
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetModuleOverviewTool(service: FormsService) {
    addTool(
        name = "get_module_overview",
        description = "Overview of a fetched module: Forms version plus the names of every " +
            "section — blocks, program units, attached libraries, LOVs, record groups, windows, " +
            "canvases, alerts, parameters, visual attributes, property classes, editors, menus, " +
            "object-library tabs — and the trigger count. The natural first call after fetch_module; " +
            "drill in with list_blocks, list_triggers, list_program_units, then the get_* tools.",
        inputSchema = moduleSchema(),
        title = "Module overview",
        outputSchema = outputSchemaOf<ModuleOverview>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            toolResult(service.overview(service.resolveModule(request.args().moduleArg())))
        }
    }
}
