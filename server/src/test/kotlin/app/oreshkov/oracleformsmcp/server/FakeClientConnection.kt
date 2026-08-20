package app.oreshkov.oracleformsmcp.server

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal [ClientConnection] so tests can invoke a registered tool's handler directly —
 * `server.tools.getValue(name).handler(connection, request)` — without standing up a transport
 * and a real session.
 *
 * Only the outbound paths the tools actually use are implemented: [notification] (progress frames
 * from `fetch_module`) is recorded, everything else fails loudly rather than pretending to
 * succeed, so a tool that starts sampling or eliciting will surface here instead of silently
 * no-op'ing. [sent] is thread-safe: handlers run concurrently (MCP SDK 0.15+).
 */
internal class FakeClientConnection(override val sessionId: String = "test-session") : ClientConnection {

    /** Every notification the tool emitted, in order. */
    val sent: MutableList<ServerNotification> = CopyOnWriteArrayList()

    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) {
        sent += notification
    }

    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) {
        sent += notification
    }

    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) {
        sent += notification
    }

    override suspend fun sendResourceListChanged() = unsupported("sendResourceListChanged")
    override suspend fun sendToolListChanged() = unsupported("sendToolListChanged")
    override suspend fun sendPromptListChanged() = unsupported("sendPromptListChanged")

    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult =
        unsupported("ping")

    override suspend fun createMessage(
        request: CreateMessageRequest,
        options: RequestOptions?,
    ): CreateMessageResult = unsupported("createMessage")

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult =
        unsupported("listRoots")

    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult = unsupported("createElicitation")

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult = unsupported("createElicitation")

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult =
        unsupported("createElicitation")

    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) =
        unsupported("sendElicitationComplete")

    private fun unsupported(name: String): Nothing =
        error("FakeClientConnection.$name is not implemented — no tool should be calling it")
}
