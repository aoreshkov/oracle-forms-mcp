package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.AnnotationCreated
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * Behavior hints shared by the annotation-mutating tools: writes a local store, additive rather
 * than destructive, not idempotent (each call adds a distinct entry), closed domain.
 */
internal val ANNOTATION_WRITE: ToolAnnotations = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = false,
    idempotentHint = false,
    openWorldHint = false,
)

fun Server.registerAnnotateElementTool(service: FormsService) {
    addTool(
        name = "annotate_element",
        description = "Persist a note, summary, tag, or classification about one element of a " +
            "fetched module — durable meta-information that later sessions see when they read the " +
            "same element. The element must exist (call fetch_module first). Use ownerPath to " +
            "disambiguate same-named elements at different scopes.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "elementKind" to enumPropOf<ElementKind>("The kind of element to annotate"),
                "name" to stringProp("Element name, e.g. 'WHEN-VALIDATE-ITEM' or 'PKG_ORDERS'"),
                "ownerPath" to stringProp(
                    "Owner scope to disambiguate (optional): a trigger's 'BLOCK', 'BLOCK.ITEM', " +
                        "or ':FORM' (form level); an item's owning block; a menu item's owning " +
                        "menu; 'PACKAGE_SPEC'/'PACKAGE_BODY' for a package program unit",
                ),
                "kind" to enumPropOf<AnnotationKind>("What the annotation asserts"),
                "body" to stringProp("The note/summary text, tag label, or classification value"),
                "author" to enumPropOf<Author>("Who is asserting this (default: ai)"),
            ),
            extraRequired = listOf("elementKind", "name", "kind", "body"),
        ),
        title = "Annotate an element",
        outputSchema = outputSchemaOf<AnnotationCreated>(),
        toolAnnotations = ANNOTATION_WRITE,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.annotate(
                    key = service.resolveModule(args.moduleArg()),
                    elementKind = args.requireEnumArg<ElementKind>("elementKind"),
                    name = args.requireStringArg("name"),
                    ownerPath = args.stringArg("ownerPath"),
                    kind = args.requireEnumArg<AnnotationKind>("kind"),
                    body = args.requireStringArg("body"),
                    author = args.enumArgOf<Author>("author") ?: Author.AI,
                ),
            )
        }
    }
}
