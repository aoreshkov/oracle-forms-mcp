package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.SourceText
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerReadSourceTool(service: FormsService) {
    addTool(
        name = "read_source",
        description = "A line range of one of a module's cached files — the converted XML, or an " +
            "extracted PL/SQL sidecar. Every result that points at a file carries a 'source' with " +
            "the 'uri' and 'file' to pass here, so a line range can be read instead of guessed at. " +
            "Use it to see what surrounds a search hit, or the rest of a fragment that came back " +
            "truncated. Pass either 'uri' or 'file'; the response is capped and says so, and " +
            "'totalLines' tells you where the file ends.",
        inputSchema = moduleSchema(
            extraProps = mapOf(
                "uri" to stringProp(
                    "Resource URI of the file, as returned in 'source.uri', e.g. " +
                        "'oracleforms://ORDERS.fmb/converted'",
                ),
                "file" to stringProp(
                    "Cache-relative path instead of 'uri', as returned in 'source.file', e.g. " +
                        "'plsql/triggers/ORDERS.KEY-COMMIT.sql'",
                ),
                "startLine" to intProp("First line to return, 1-based inclusive (default 1)"),
                "endLine" to intProp("Last line to return, inclusive (default: end of file)"),
                "maxLines" to intProp("Cap on lines returned (default 200, max 2000)"),
            ),
        ),
        title = "Read cached source",
        outputSchema = outputSchemaOf<SourceText>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            val result = service.readSource(
                key = service.resolveModule(args.moduleArg()),
                target = args.stringArg("uri") ?: args.stringArg("file")
                    ?: throw IllegalArgumentException(
                        "Pass 'uri' (a result's source.uri) or 'file' (its source.file) to say " +
                            "which file to read.",
                    ),
                startLine = args.intArg("startLine"),
                endLine = args.intArg("endLine"),
                maxLines = args.intArg("maxLines"),
            )
            toolResult(result, result.source)
        }
    }
}
