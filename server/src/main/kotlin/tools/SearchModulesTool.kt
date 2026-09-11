package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.ModuleSearchResults
import app.oreshkov.oracleformsmcp.server.DEFAULT_SEARCH_HITS
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.MAX_SEARCH_HITS
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerSearchModulesTool(service: FormsService) {
    addTool(
        name = "search_modules",
        description = "Search every cached module at once — the cross-module counterpart of " +
            "search_source, for the questions one module cannot answer: which forms call a given " +
            "form, where a :GLOBAL variable is written and read, which modules subclass a shared " +
            "block (those pointers live in the converted XML, so use scope 'xml' and query " +
            "'ParentFilename=\"toolbar.fmb\"'). Returns module + file:line + snippet, each with " +
            "the 'uri' read_source takes back. Only modules already fetched are searched: " +
            "'skippedNotCached' and 'skippedStale' count the ones that were not, 'cachedModules' " +
            "and 'scannedModules' say how far the scan reached, and 'hint' names the call that " +
            "widens it. When 'truncated' is true, call again with the same arguments and 'cursor' " +
            "set to the returned 'nextCursor'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringProp("Substring (default) or regex to search for"))
                put(
                    "regex",
                    boolProp("Treat 'query' — not 'modulePattern' — as a regular expression (default false)"),
                )
                put(
                    "ignoreCase",
                    boolProp(
                        "Match case-insensitively (default true; Forms code writes the same name " +
                            "as ORDERS, orders and Call_Form('orders'))",
                    ),
                )
                put("scope", stringProp("Where to search: plsql (default), xml, or all"))
                put(
                    "modulePattern",
                    stringProp("Only search cached modules whose name contains this (case-insensitive)"),
                )
                put("maxResults", intProp("Hits per page, 1-$MAX_SEARCH_HITS (default $DEFAULT_SEARCH_HITS)"))
                put("cursor", stringProp("Opaque continuation token — pass a previous call's 'nextCursor' verbatim"))
            },
            required = listOf("query"),
        ),
        title = "Search cached modules",
        outputSchema = outputSchemaOf<ModuleSearchResults>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.searchModules(
                    query = args.requireStringArg("query"),
                    regex = args.booleanArg("regex") ?: false,
                    ignoreCase = args.booleanArg("ignoreCase") ?: true,
                    scope = args.stringArg("scope"),
                    modulePattern = args.stringArg("modulePattern"),
                    maxResults = args.intArg("maxResults") ?: DEFAULT_SEARCH_HITS,
                    cursor = args.stringArg("cursor"),
                ),
            )
        }
    }
}
