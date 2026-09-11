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
            "drill in with list_blocks, list_triggers, list_program_units, then the get_* tools. " +
            "'truncated' means at least one section held more names than the response cap and was " +
            "cut — drill into that section with its own list tool. verbosity=detailed adds a " +
            "'detail' section with the window and canvas objects behind those two name lists — " +
            "window modality and size, toolbar canvases, and the window each canvas sits on.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "verbosity" to verbosityProp(
                    "returns the section name lists only",
                ),
            ),
        ),
        title = "Module overview",
        outputSchema = outputSchemaOf<ModuleOverview>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.overview(
                    key = service.resolveModule(args.moduleArg()),
                    detailed = args.detailedArg(),
                ),
            )
        }
    }
}
