package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.AnnotationRemoved
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

fun Server.registerRemoveAnnotationTool(service: FormsService) {
    addTool(
        name = "remove_annotation",
        description = "Delete one stored annotation or relation by its id (as returned by " +
            "annotate_element / relate_elements or shown in get_element_annotations). Reports " +
            "removed=false when no entry had that id.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "id" to stringProp("The id of the annotation or relation to remove"),
            ),
            extraRequired = listOf("id"),
        ),
        title = "Remove an annotation",
        outputSchema = outputSchemaOf<AnnotationRemoved>(),
        // Deletes a stored entry: a write, and destructive (irreversible for that id).
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.removeAnnotation(
                    key = service.resolveModule(args.moduleArg()),
                    id = args.requireStringArg("id"),
                ),
            )
        }
    }
}
