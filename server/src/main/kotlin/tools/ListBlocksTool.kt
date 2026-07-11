package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.BlockList
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListBlocksTool(service: FormsService) {
    addTool(
        name = "list_blocks",
        description = "List the data blocks of a fetched form module with their base table " +
            "(query data source), item count, and trigger count. Use get_block for a block's " +
            "full item list.",
        inputSchema = moduleSchema(),
        title = "List blocks",
        outputSchema = outputSchemaOf<BlockList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            toolResult(service.listBlocks(service.resolveModule(request.args().moduleArg())))
        }
    }
}
