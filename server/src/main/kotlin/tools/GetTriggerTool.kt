package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.TriggerSource
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetTriggerTool(service: FormsService) {
    addTool(
        name = "get_trigger",
        description = "The decoded PL/SQL body of one trigger. Pass 'ownerPath' (or 'block'/'item') " +
            "when the same trigger name exists at several scopes — e.g. a KEY-NEXT-ITEM at form, " +
            "block and item level.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "name" to stringProp("Trigger name, e.g. 'WHEN-VALIDATE-ITEM'"),
                "ownerPath" to stringProp(
                    "Exact scope, to disambiguate (optional): 'BLOCK', 'BLOCK.ITEM', or ':FORM' " +
                        "for the form-level trigger",
                ),
                "block" to stringProp("Owning block, to disambiguate (optional)"),
                "item" to stringProp("Owning item, to disambiguate (optional)"),
            ),
            extraRequired = listOf("name"),
        ),
        title = "Get trigger PL/SQL",
        outputSchema = outputSchemaOf<TriggerSource>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.getTrigger(
                    key = service.resolveModule(args.moduleArg()),
                    name = args.requireStringArg("name"),
                    block = args.stringArg("block"),
                    item = args.stringArg("item"),
                    ownerPath = args.stringArg("ownerPath"),
                ),
            )
        }
    }
}
