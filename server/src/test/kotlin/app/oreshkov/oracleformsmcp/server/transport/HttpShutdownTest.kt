package app.oreshkov.oracleformsmcp.server.transport

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * SIGTERM must drain, not cut.
 *
 * The HTTP transport is how the GHCR image is run, and a container is always killed by signal —
 * so the interesting moment is a tool call that is already mid-flight when the hook fires. Ktor's
 * default `stop()` budget is 500 ms for both grace and timeout, short enough that such a call
 * comes back as a truncated body instead of a result; `stopGracefully` is what replaces it. This
 * exercises that function itself rather than asserting the constants, so the test fails if the
 * budget is ever tuned back below what one ordinary call needs.
 */
class HttpShutdownTest {

    /** Comfortably inside the 3 s grace, comfortably outside Ktor's 500 ms default. */
    private val handlerWork = 1_000L

    @Test
    fun `a call already in flight finishes while the server is shutting down`() = runBlocking {
        val handlerEntered = CompletableDeferred<Unit>()
        val engine = embeddedServer(CIO, port = 0) {
            routing {
                get("/slow") {
                    handlerEntered.complete(Unit)
                    delay(handlerWork)
                    call.respondText("drained")
                }
            }
        }
        engine.start(wait = false)

        try {
            val port = engine.engine.resolvedConnectors().first().port
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/slow"))
                .timeout(JavaDuration.ofSeconds(30))
                .GET()
                .build()
            val inFlight = HttpClient.newHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())

            // Stop only once the handler is demonstrably running: stopping before it is dispatched
            // would prove nothing about draining, only that the connector closed.
            handlerEntered.await()
            engine.stopGracefully()

            val response = inFlight.get()
            assertEquals(200, response.statusCode(), "in-flight call was cut, not drained")
            assertEquals("drained", response.body())
        } finally {
            // No-op when the graceful stop above already ran; matters when an assertion threw.
            engine.stop(0, 0)
        }
    }
}
