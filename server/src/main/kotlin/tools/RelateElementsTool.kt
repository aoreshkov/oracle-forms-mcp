package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.RelationCreated
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerRelateElementsTool(service: FormsService) {
    addTool(
        name = "relate_elements",
        description = "Record a directed relationship between two elements of the same fetched " +
            "module — e.g. a trigger 'calls' a program unit, an LOV 'feeds' an item, a unit is " +
            "'deprecated-by' another. Stored in active voice (from → to). Both elements must " +
            "exist. Use fromOwner/toOwner to disambiguate scopes.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "fromKind" to enumPropOf<ElementKind>("Kind of the source element"),
                "fromName" to stringProp("Source element name"),
                "fromOwner" to stringProp("Source owner scope to disambiguate (optional)"),
                "toKind" to enumPropOf<ElementKind>("Kind of the target element"),
                "toName" to stringProp("Target element name"),
                "toOwner" to stringProp("Target owner scope to disambiguate (optional)"),
                "relType" to stringProp("Relationship in active voice, e.g. 'calls', 'feeds', 'references', 'deprecated-by'"),
                "note" to stringProp("Optional free-text detail about the relationship"),
                "author" to enumPropOf<Author>("Who is asserting this (default: ai)"),
            ),
            extraRequired = listOf("fromKind", "fromName", "toKind", "toName", "relType"),
        ),
        title = "Relate two elements",
        outputSchema = outputSchemaOf<RelationCreated>(),
        toolAnnotations = ANNOTATION_WRITE,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.relate(
                    key = service.resolveModule(args.moduleArg()),
                    fromKind = args.requireEnumArg<ElementKind>("fromKind"),
                    fromName = args.requireStringArg("fromName"),
                    fromOwner = args.stringArg("fromOwner"),
                    toKind = args.requireEnumArg<ElementKind>("toKind"),
                    toName = args.requireStringArg("toName"),
                    toOwner = args.stringArg("toOwner"),
                    relType = args.requireStringArg("relType"),
                    note = args.stringArg("note"),
                    author = args.enumArgOf<Author>("author") ?: Author.AI,
                ),
            )
        }
    }
}
