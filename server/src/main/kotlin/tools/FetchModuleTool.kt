package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.FetchModuleSummary
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.server.FetchProgress
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

fun Server.registerFetchModuleTool(
    service: FormsService,
    onFetched: suspend (ModuleKey) -> Unit = {},
) {
    addTool(
        name = "fetch_module",
        description = "Convert an Oracle Forms module to its text form and index it, warming the " +
            "local cache. With ORACLE_HOME set the binary is converted via frmf2xml/frmcmp; " +
            "otherwise a pre-converted file next to the module is copied. Idempotent — a cached " +
            "module whose source is unchanged returns immediately. Call this once per module " +
            "(and again when list_modules reports STALE) before using the other tools. Returns " +
            "a summary (block/item/trigger/program-unit counts, attached libraries).",
        inputSchema = moduleSchema(),
        title = "Fetch and index a module",
        outputSchema = outputSchemaOf<FetchModuleSummary>(),
        // Writes the local cache (additive, repeat-safe); runs only local conversions.
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        guarded {
            val key = service.resolveModule(request.args().moduleArg())
            // Clients opt into notifications/progress by sending a progressToken in _meta.
            val progressToken = request.params.meta?.progressToken
            val summary = service.fetchModule(key) { progress ->
                progressToken?.let { sendFetchProgress(it, progress) }
            }
            onFetched(key)
            toolResult(summary)
        }
    }
}

/** Best-effort: a dropped progress frame must never fail the fetch itself. */
private suspend fun ClientConnection.sendFetchProgress(token: ProgressToken, progress: FetchProgress) {
    runCatching {
        notification(
            ProgressNotification(
                ProgressNotificationParams(
                    progressToken = token,
                    progress = progress.step.toDouble(),
                    total = progress.totalSteps.toDouble(),
                    message = progress.message,
                )
            )
        )
    }
}
