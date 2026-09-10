package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ProgramUnitList
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListProgramUnitsTool(service: FormsService) {
    addTool(
        name = "list_program_units",
        description = "List a fetched module's PL/SQL program units (procedures, functions, " +
            "package specs and bodies) with their line counts. For .pll libraries this is the " +
            "library's whole content. Fetch a body with get_program_unit. 'total' counts every " +
            "unit; 'truncated' means the rows were cut at the response cap — reach the rest by " +
            "name with get_program_unit or search_source.",
        inputSchema = moduleSchema(),
        title = "List program units",
        outputSchema = outputSchemaOf<ProgramUnitList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            toolResult(service.listProgramUnits(service.resolveModule(request.args().moduleArg())))
        }
    }
}
