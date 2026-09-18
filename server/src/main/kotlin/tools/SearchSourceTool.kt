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
            "file:line hits with a snippet. Matching ignores case by default, as in " +
            "search_modules (which searches every cached module at once). Every page also reports " +
            "'total' (all hits in the module) and 'files' (hits per file across the whole result), " +
            "so one call says whether and roughly where a name appears. When 'truncated' is true " +
            "the 'hint' names the call for the next page ('offset' = 'nextOffset'); a claim that " +
            "something is absent needs every page. A result with no hits reports " +
            "'filesSearched' and a 'hint' naming where this scope could not look — a pattern that " +
            "matches nothing shows the pattern is absent, not the fact, and Forms writes many " +
            "values with no PL/SQL naming them at all.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "query" to stringProp("Substring (default) or regex to search for"),
                "regex" to boolProp("Treat 'query' as a regular expression (default false)"),
                "ignoreCase" to boolProp("Fold case when matching (default true)"),
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
                    ignoreCase = args.booleanArg("ignoreCase") ?: true,
                ),
            )
        }
    }
}
