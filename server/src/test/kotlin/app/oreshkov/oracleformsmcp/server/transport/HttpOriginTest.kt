package app.oreshkov.oracleformsmcp.server.transport

import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The Origin check, against the engine [runHttpServer] runs rather than the lists handed to it.
 *
 * Both bugs this pins lived between our allowlists and the SDK's reading of them, so a test of
 * [allowLists] alone passed through both: a schemeless localhost Origin entry that stopped the
 * server at startup whenever `--allowed-origin` was given, and `--allowed-host` without
 * `--allowed-origin` switching the Origin check off. The spec requires that check (a present,
 * invalid `Origin` gets `403`), so each case sends a real `initialize` and reads the status.
 */
class HttpOriginTest {

    private fun mcpServer() = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities()),
    )

    private val client = HttpClient.newHttpClient()

    private fun <T> running(engine: EmbeddedServer<*, *>, block: (port: Int) -> T): T {
        // Starting is half the test: the --allowed-origin crash happened here.
        engine.start(wait = false)
        try {
            val port = runBlocking { engine.engine.resolvedConnectors().first().port }
            return block(port)
        } finally {
            engine.stop(0, 0)
        }
    }

    /** The status of an `initialize` POST sent with [origin], or with no Origin header when `null`. */
    private fun initializeStatus(port: Int, origin: String?): Int {
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
            """"capabilities":{},"clientInfo":{"name":"origin-test","version":"0"}}}"""
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .timeout(JavaDuration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { if (origin != null) header("Origin", origin) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // Only the status matters; a 200 may be an SSE stream, which is not read to its end.
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `by default only a localhost Origin is admitted`() {
        running(httpEngine(mcpServer(), port = 0)) { port ->
            assertEquals(200, initializeStatus(port, "http://localhost:6274"))
            assertEquals(403, initializeStatus(port, "http://evil.example"))
        }
    }

    @Test
    fun `an allowed origin starts the server and is admitted beside localhost`() {
        running(httpEngine(mcpServer(), port = 0, allowedOrigins = listOf("https://forms.example"))) { port ->
            assertEquals(200, initializeStatus(port, "https://forms.example"))
            assertEquals(200, initializeStatus(port, "http://localhost:6274"))
            assertEquals(403, initializeStatus(port, "http://evil.example"))
        }
    }

    @Test
    fun `an allowed host alone still rejects a hostile Origin`() {
        running(httpEngine(mcpServer(), port = 0, allowedHosts = listOf("forms.example"))) { port ->
            assertEquals(403, initializeStatus(port, "http://evil.example"))
            assertEquals(200, initializeStatus(port, "http://127.0.0.1:$port"))
        }
    }

    /** Only a browser sends Origin, and a client that is not one cannot be rebound. */
    @Test
    fun `a request with no Origin is admitted`() {
        running(httpEngine(mcpServer(), port = 0, allowedHosts = listOf("forms.example"))) { port ->
            assertEquals(200, initializeStatus(port, origin = null))
        }
    }
}
