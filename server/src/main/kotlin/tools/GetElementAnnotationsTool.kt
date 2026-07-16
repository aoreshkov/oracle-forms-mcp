package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ElementAnnotationList
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetElementAnnotationsTool(service: FormsService) {
    addTool(
        name = "get_element_annotations",
        description = "The notes, tags, summaries, classifications, and relations stored about one " +
            "element (from earlier annotate_element / relate_elements calls). Each entry is flagged " +
            "'staleAgainstSource' when it predates the module's current source. Served even for a " +
            "STALE module so prior knowledge is never hidden.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "elementKind" to enumPropOf<ElementKind>("The kind of element"),
                "name" to stringProp("Element name"),
                "ownerPath" to stringProp("Owner scope to disambiguate (optional)"),
            ),
            extraRequired = listOf("elementKind", "name"),
        ),
        title = "Get element annotations",
        outputSchema = outputSchemaOf<ElementAnnotationList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.getElementAnnotations(
                    key = service.resolveModule(args.moduleArg()),
                    elementKind = args.requireEnumArg<ElementKind>("elementKind"),
                    name = args.requireStringArg("name"),
                    ownerPath = args.stringArg("ownerPath"),
                ),
            )
        }
    }
}
