package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.server.DEFAULT_MODULE_PAGE
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.MAX_MODULE_PAGE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerListModulesTool(service: FormsService) {
    addTool(
        name = "list_modules",
        description = "List Oracle Forms modules in the configured directory (.fmb, .mmb, .pll, " +
            ".olb) with their cache status: NOT_CACHED (call fetch_module first), CACHED (ready " +
            "to read), STALE (re-fetch), or SOURCE_MISSING (cached but the file is gone). A STALE " +
            "row carries 'staleReason': SOURCE_CHANGED (the module changed on disk) or " +
            "INDEX_OUTDATED (an older build of this server indexed it, so its facts may be " +
            "incomplete; re-fetching only re-parses, it does not re-convert). Also reports " +
            "whether a pre-converted XML/pld sibling exists. A real forms " +
            "directory holds thousands of modules, so the answer is filtered and paged: narrow " +
            "with 'pattern', 'type' and 'status', and when 'truncated' is true call again with " +
            "'cursor' set to the returned 'nextCursor'. 'countsByStatus' always summarises the " +
            "whole name/type-filtered set, so one narrow call still shows the shape of the rest.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("pattern", stringProp("Case-insensitive substring of the module name, e.g. 'ORDER'"))
                put("regex", boolProp("Treat 'pattern' as a regular expression (default false)"))
                put(
                    "type",
                    enumPropOf<ModuleType>(
                        "Only this module kind: form (.fmb), menu (.mmb), library (.pll), object_library (.olb)",
                    ),
                )
                put("status", enumPropOf<ModuleStatus>("Only modules in this cache state"))
                put("limit", intProp("Page size, 1-$MAX_MODULE_PAGE (default $DEFAULT_MODULE_PAGE)"))
                put("cursor", stringProp("Opaque continuation token — pass a previous call's 'nextCursor' verbatim"))
            },
        ),
        title = "List Forms modules",
        outputSchema = outputSchemaOf<ModuleList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.listModules(
                    pattern = args.stringArg("pattern"),
                    regex = args.booleanArg("regex") ?: false,
                    type = args.enumArgOf<ModuleType>("type"),
                    status = args.enumArgOf<ModuleStatus>("status"),
                    limit = args.intArg("limit"),
                    cursor = args.stringArg("cursor"),
                ),
            )
        }
    }
}
