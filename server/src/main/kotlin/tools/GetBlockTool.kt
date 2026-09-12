package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetBlockTool(service: FormsService) {
    addTool(
        name = "get_block",
        description = "One block: base table, its trigger names, and every item. Each item row " +
            "carries its name, type, property class (which is usually where an item's role is " +
            "defined — an LOV button is only an LOV button because of the class it inherits), " +
            "prompt, item-trigger names, and its 'inherited' pointer when it is subclassed. " +
            "verbosity=detailed adds data type, column, canvas, and the visible/required/LOV " +
            "properties Forms records only where they are overridden. A subclassed block (or " +
            "item) carries an 'inherited' pointer to the module that defines it — what is listed " +
            "here is then only this module's overrides, and the result's hint names the call that " +
            "reaches the full definition. 'propertyClass' is reported only for classes this " +
            "module declares (they are listed by get_module_overview); one an item inherits from " +
            "a parent module is left unset rather than guessed.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "block" to stringProp("Block name, e.g. 'ORDERS'"),
                "verbosity" to verbosityProp(
                    "returns each item's name, type, property class, prompt and trigger names",
                ),
            ),
            extraRequired = listOf("block"),
        ),
        title = "Get block detail",
        outputSchema = outputSchemaOf<BlockDetail>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            val result = service.getBlock(
                key = service.resolveModule(args.moduleArg()),
                blockName = args.requireStringArg("block"),
                detailed = args.detailedArg(),
            )
            toolResult(result, result.source)
        }
    }
}
