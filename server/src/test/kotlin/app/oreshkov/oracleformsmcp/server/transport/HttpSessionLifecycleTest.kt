package app.oreshkov.oracleformsmcp.server.transport

import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Stateful Streamable HTTP sessions are not forever (MCP SDK 0.16+), and these pin the contract
 * [runHttpServer] documents for them against the engine it runs.
 *
 * The property everything else leans on is the third test: a session is idle only while none of its
 * requests is in flight, so a `fetch_module` conversion holding its call for the whole
 * `--conversion-timeout` cannot have its session expired underneath it. The timeout is shrunk to
 * [idleTimeout] through `httpEngine`'s test-only parameter; the SDK sweeps every `idleTimeout` (at
 * most 5 s), so [pastExpiry] — two sweeps and a margin — is past it for certain.
 */
class HttpSessionLifecycleTest {

    private val idleTimeout = 200.milliseconds
    private val pastExpiry = 1_000L

    /** Several times [idleTimeout], so the session would have expired had the call not held it. */
    private val slowCallWork = 1_000L

    private fun mcpServer() = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).apply {
        addTool(name = "slow", description = "Outlives the idle timeout.") {
            delay(slowCallWork)
            CallToolResult(content = listOf(TextContent("done")))
        }
    }

    private val client = HttpClient.newHttpClient()

    private fun <T> running(engine: EmbeddedServer<*, *>, block: (port: Int) -> T): T {
        engine.start(wait = false)
        try {
            val port = runBlocking { engine.engine.resolvedConnectors().first().port }
            return block(port)
        } finally {
            engine.stop(0, 0)
        }
    }

    private fun post(port: Int, body: String, sessionId: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .timeout(JavaDuration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (sessionId != null) {
                    header("Mcp-Session-Id", sessionId)
                    header("MCP-Protocol-Version", PROTOCOL_VERSION)
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** A completed handshake (`initialize` + `notifications/initialized`); returns the session id. */
    private fun initialize(port: Int): String {
        val response = post(port, INITIALIZE)
        assertEquals(200, response.statusCode(), response.body())
        val sessionId = assertNotNull(response.headers().firstValue("Mcp-Session-Id").orElse(null))
        assertEquals(202, post(port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", sessionId).statusCode())
        return sessionId
    }

    private fun ping(port: Int, sessionId: String): Int =
        post(port, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", sessionId).statusCode()

    @Test
    fun `a GET without a session id is rejected`() {
        running(httpEngine(mcpServer(), port = 0)) { port ->
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
                .timeout(JavaDuration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build()
            assertEquals(400, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
        }
    }

    @Test
    fun `an idle session answers 404 and a fresh initialize starts a new one`() {
        running(httpEngine(mcpServer(), port = 0, sessionIdleTimeout = idleTimeout)) { port ->
            val expired = initialize(port)
            // The session works before it idles, so the 404 below is expiry and not a bad request.
            assertEquals(200, ping(port, expired))

            Thread.sleep(pastExpiry)
            assertEquals(404, ping(port, expired), "an idle session outlived its timeout")

            val renewed = initialize(port)
            assertNotEquals(expired, renewed)
            assertEquals(200, ping(port, renewed))
        }
    }

    @Test
    fun `a call that outlives the idle timeout completes and keeps its session`() {
        running(httpEngine(mcpServer(), port = 0, sessionIdleTimeout = idleTimeout)) { port ->
            val sessionId = initialize(port)
            val call = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"slow","arguments":{}}}"""

            val response = post(port, call, sessionId)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("\"done\"" in response.body(), "the call was cut: ${response.body()}")
            assertEquals(200, ping(port, sessionId), "the session expired while a call held it")
        }
    }

    @Test
    fun `a new session beyond the ceiling is refused with 503`() {
        running(httpEngine(mcpServer(), port = 0, maxSessions = 1)) { port ->
            initialize(port)
            assertEquals(503, post(port, INITIALIZE).statusCode())
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$PROTOCOL_VERSION",""" +
            """"capabilities":{},"clientInfo":{"name":"session-test","version":"0"}}}"""
    }
}
