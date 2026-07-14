package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.SearchResults
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerSearchSourceTool(service: FormsService) {
    addTool(
        name = "search_source",
        description = "Search a fetched module line by line. Scope 'plsql' (default) searches the " +
            "extracted trigger/program-unit/menu-command PL/SQL (and .pld library source); 'xml' " +
            "searches the raw converted XML (properties, layout); 'all' searches both. Returns " +
            "file:line hits with a snippet. When 'truncated' is true, call again with 'offset' set " +
            "to the returned 'nextOffset' to page through the rest.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "query" to stringProp("Substring (default) or regex to search for"),
                "regex" to boolProp("Treat 'query' as a regular expression (default false)"),
                "scope" to stringProp("Where to search: plsql (default), xml, or all"),
                "maxResults" to intProp("Page size, 1-200 (default 50)"),
                "offset" to intProp("Skip this many matches before the page (default 0; see nextOffset)"),
            ),
            extraRequired = listOf("query"),
        ),
        title = "Search module source",
        outputSchema = outputSchemaOf<SearchResults>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.searchSource(
                    key = service.resolveModule(args.moduleArg()),
                    query = args.requireStringArg("query"),
                    regex = args.booleanArg("regex") ?: false,
                    scope = args.stringArg("scope"),
                    maxResults = args.intArg("maxResults") ?: 50,
                    offset = args.intArg("offset") ?: 0,
                ),
            )
        }
    }
}
