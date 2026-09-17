package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetBlockTool(service: FormsService) {
    addTool(
        name = "get_block",
        description = "One block: base table, its DML properties, its trigger names, and every item. " +
            "Each item row carries its name, type, property class (which is usually where an item's " +
            "role is defined — an LOV button is only an LOV button because of the class it " +
            "inherits), prompt, item-trigger names, and its 'inherited' pointer when it is " +
            "subclassed. verbosity=detailed adds data type, column, canvas, the visible/required/LOV " +
            "properties, each item's own 'dml' (database item, insert/update allowed, enabled, " +
            "initial value, …) exactly as the item writes them, and 'effectiveDml': the same " +
            "properties with the item's property class applied. Use 'effectiveDml' to answer what an " +
            "insert or update writes — on a classed item a property the item does not write comes " +
            "from its class, which is often defined in another module; an item appears there only " +
            "when its class resolved, and the hint names the fetch_module call that resolves the rest. " +
            "'truncated' means items or 'effectiveDml' were cut to fit one response; the hint says which. " +
            "columns=true adds the base table's columns as Forms recorded them, the columns no item " +
            "supplies, and which of those are mandatory (an insert fails unless a trigger assigns " +
            "them). A subclassed block carries an 'inherited' pointer to the module that defines " +
            "it — what is listed here is then only this module's overrides.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "block" to stringProp("Block name, e.g. 'ORDERS'"),
                "verbosity" to verbosityProp(
                    "returns each item's name, type, property class, prompt and trigger names",
                ),
                "columns" to boolProp(
                    "Add the block's data-source columns and the columns no item supplies (default false)",
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
                columns = args.booleanArg("columns") ?: false,
            )
            toolResult(result, result.source)
        }
    }
}
