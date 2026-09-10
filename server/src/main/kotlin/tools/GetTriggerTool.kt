package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.TriggerSource
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetTriggerTool(service: FormsService) {
    addTool(
        name = "get_trigger",
        description = "The decoded PL/SQL body of one trigger. Pass 'ownerPath' (or 'block'/'item') " +
            "when the same trigger name exists at several scopes — e.g. a KEY-NEXT-ITEM at form, " +
            "block and item level. When the trigger is subclassed from another module its body is " +
            "empty here and the code that runs lives in the parent: the result then says " +
            "bodySource='inherited' and carries 'inherited' (the parent module and the path to " +
            "the trigger there) plus a hint naming the exact call. Never read an empty 'text' as " +
            "'this trigger does nothing' without checking bodySource.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "name" to stringProp("Trigger name, e.g. 'WHEN-VALIDATE-ITEM'"),
                "ownerPath" to stringProp(
                    "Exact scope, to disambiguate (optional): 'BLOCK', 'BLOCK.ITEM', or ':FORM' " +
                        "for the form-level trigger",
                ),
                "block" to stringProp("Owning block, to disambiguate (optional)"),
                "item" to stringProp("Owning item, to disambiguate (optional)"),
                "resolve" to boolProp(
                    "Follow the subclassing pointer and return the inherited body (default false). " +
                        "Only reads modules that are already cached — it never converts one — so " +
                        "fetch_module the parent first if the hint says it is not cached.",
                ),
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
                    resolve = args.booleanArg("resolve") ?: false,
                ),
            )
        }
    }
}
