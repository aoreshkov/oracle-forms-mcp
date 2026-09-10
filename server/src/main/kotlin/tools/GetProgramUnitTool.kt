package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ProgramUnitSource
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetProgramUnitTool(service: FormsService) {
    addTool(
        name = "get_program_unit",
        description = "The PL/SQL body of one program unit. Pass 'unitType' when a package's " +
            "spec and body share the name (PACKAGE_SPEC vs PACKAGE_BODY). A subclassed unit " +
            "returns bodySource='inherited' with a pointer to the module that defines it, rather " +
            "than an empty body that reads as 'no code'.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "name" to stringProp("Program unit name, e.g. 'CALC_TOTAL' or 'PKG_ORDERS'"),
                "unitType" to stringProp(
                    "Disambiguates same-named units: PROCEDURE, FUNCTION, PACKAGE_SPEC, or PACKAGE_BODY (optional)",
                ),
                "resolve" to boolProp(
                    "Follow the subclassing pointer and return the inherited body (default false). " +
                        "Only reads modules that are already cached — it never converts one.",
                ),
            ),
            extraRequired = listOf("name"),
        ),
        title = "Get program unit PL/SQL",
        outputSchema = outputSchemaOf<ProgramUnitSource>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            val result = service.getProgramUnit(
                key = service.resolveModule(args.moduleArg()),
                name = args.requireStringArg("name"),
                unitType = args.stringArg("unitType"),
                resolve = args.booleanArg("resolve") ?: false,
            )
            toolResult(result, result.source)
        }
    }
}
