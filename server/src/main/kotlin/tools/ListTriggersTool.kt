package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.TriggerList
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListTriggersTool(service: FormsService) {
    addTool(
        name = "list_triggers",
        description = "List a fetched module's triggers with their level (form/block/item/menu), " +
            "owning block/item, and line count. Filter by block, item, or level. 'concise' (default) " +
            "omits the one-line PL/SQL preview to save tokens when triaging a large trigger set; " +
            "'detailed' includes it. Fetch a full body with get_trigger.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "block" to stringProp("Only triggers of this block (optional)"),
                "item" to stringProp("Only triggers of this item (optional; combine with 'block')"),
                "level" to stringProp("Only this level: form, block, item, menu, or all (default all)"),
                "verbosity" to verbosityProp("omits each trigger's one-line PL/SQL preview"),
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
                    detailed = args.detailedArg(),
                ),
            )
        }
    }
}
