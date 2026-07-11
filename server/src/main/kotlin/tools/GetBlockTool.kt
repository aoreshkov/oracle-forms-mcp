package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetBlockTool(service: FormsService) {
    addTool(
        name = "get_block",
        description = "Full detail of one block: base table, every item (with item type, data " +
            "type, column, canvas, prompt, and item-trigger names), and the block's trigger names.",
        inputSchema = moduleSchema(
            extraProps = mapOf("block" to stringProp("Block name, e.g. 'ORDERS'")),
            extraRequired = listOf("block"),
        ),
        title = "Get block detail",
        outputSchema = outputSchemaOf<BlockDetail>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.getBlock(
                    service.resolveModule(args.moduleArg()),
                    args.requireStringArg("block"),
                ),
            )
        }
    }
}
