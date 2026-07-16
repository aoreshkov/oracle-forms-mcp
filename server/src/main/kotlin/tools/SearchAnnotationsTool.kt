package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.AnnotationSearchResults
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerSearchAnnotationsTool(service: FormsService) {
    addTool(
        name = "search_annotations",
        description = "Search the meta-information stored for one module. Filter notes by free " +
            "text, by annotation 'kind' (note/tag/summary/classification), or by exact 'tag' " +
            "label; matching relations are returned when only a text query is given. Text search " +
            "is case-insensitive over bodies, relation types, and element names.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "text" to stringProp("Case-insensitive substring to match (optional)"),
                "kind" to enumPropOf<AnnotationKind>("Restrict to one annotation kind (optional)"),
                "tag" to stringProp("Exact tag label to match, among TAG annotations (optional)"),
            ),
        ),
        title = "Search annotations",
        outputSchema = outputSchemaOf<AnnotationSearchResults>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.searchAnnotations(
                    key = service.resolveModule(args.moduleArg()),
                    text = args.stringArg("text"),
                    kind = args.enumArgOf<AnnotationKind>("kind"),
                    tag = args.stringArg("tag"),
                ),
            )
        }
    }
}
