package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ObjectXml
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetObjectXmlTool(service: FormsService) {
    addTool(
        name = "get_object_xml",
        description = "The raw XML fragment of one named object, sliced from the converted file — " +
            "the escape hatch for every property the structured tools don't surface. objectType " +
            "is the Forms2XML element name (Block, Item, Trigger, Canvas, Window, LOV, " +
            "RecordGroup, Alert, VisualAttribute, …). Pass 'owner' (e.g. 'ORDERS' or " +
            "'ORDERS.ORDER_ID') when the name exists at several scopes. Large fragments are " +
            "truncated (flagged in the result).",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "objectType" to stringProp("XML element name, e.g. 'Block', 'Trigger', 'LOV'"),
                "name" to stringProp("The object's Name attribute value"),
                "owner" to stringProp("Owning object path, to disambiguate (optional)"),
            ),
            extraRequired = listOf("objectType", "name"),
        ),
        title = "Get raw object XML",
        outputSchema = outputSchemaOf<ObjectXml>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.getObjectXml(
                    key = service.resolveModule(args.moduleArg()),
                    objectType = args.requireStringArg("objectType"),
                    name = args.requireStringArg("name"),
                    owner = args.stringArg("owner"),
                ),
            )
        }
    }
}
