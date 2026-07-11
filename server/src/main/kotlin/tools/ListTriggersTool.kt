package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.TriggerList
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListTriggersTool(service: FormsService) {
    addTool(
        name = "list_triggers",
        description = "List a fetched module's triggers with their level (form/block/item/menu), " +
            "owning block/item, a one-line PL/SQL preview, and line count. Filter by block, item, " +
            "or level; fetch a body with get_trigger.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "block" to stringProp("Only triggers of this block (optional)"),
                "item" to stringProp("Only triggers of this item (optional; combine with 'block')"),
                "level" to stringProp("Only this level: form, block, item, menu, or all (default all)"),
            ),
        ),
        title = "List triggers",
        outputSchema = outputSchemaOf<TriggerList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.listTriggers(
                    key = service.resolveModule(args.moduleArg()),
                    block = args.stringArg("block"),
                    item = args.stringArg("item"),
                    level = args.stringArg("level"),
                ),
            )
        }
    }
}
