package app.oreshkov.oracleformsmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The SDK's DNS-rebinding protection default: only these Host values are admitted. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/** Keep-alive cadence for idle SSE streams; see the note in [runHttpServer]. */
private val SSE_HEARTBEAT_PERIOD = 30.seconds

/**
 * How long a terminating server keeps serving calls that are already in flight.
 *
 * Ktor's own default is 500 ms for both, which is short enough to read as "cut the connection":
 * a tool call that is mid-response loses it, and the client sees a truncated body rather than a
 * result (`HttpShutdownTest` fails on that default, which is what makes it a test of these values
 * and not of Ktor).
 *
 * **Treat the grace as time actually spent, not as a ceiling that a quiet server skips.** CIO
 * waits on connections, not on handlers, so it frequently waits the period out in full even
 * though the last call finished in milliseconds — a keep-alive connection is still open, and a
 * Streamable HTTP client holds one open by design. (Measured here at exactly the grace when the
 * test runs alone; less when it runs inside the suite, so the wait is real but not invariant.)
 * That is why this is sized to the call being protected rather than set generously: a cached read
 * is bounded by `MAX_RESULT_CHARS` (60 KB) and completes in milliseconds, so seconds of grace is
 * already a wide margin, and every second beyond that is one a restart may simply pay.
 *
 * A running **conversion** is the deliberate exception: it may hold its call for up to
 * `--conversion-timeout` (120 s by default), and waiting that out would turn every restart into a
 * two-minute hang — for nothing, since a container runtime sends SIGKILL long before then
 * (Docker's default grace is 10 s, which is what [SHUTDOWN_TIMEOUT] matches). Such a call is cut,
 * and that is safe to do: `OnDiskModuleCache.putIndex` writes-then-renames, so a conversion killed
 * partway leaves no half-written entry behind — the module simply stays unfetched.
 */
private val SHUTDOWN_GRACE: Duration = 3.seconds
private val SHUTDOWN_TIMEOUT: Duration = 10.seconds

/**
 * Stops the engine on the project's shutdown budget rather than Ktor's 500 ms default.
 *
 * Extracted so the values above are exercised by the same call the shutdown hook makes
 * (`HttpShutdownTest`) instead of being asserted as bare numbers.
 */
internal fun EmbeddedServer<*, *>.stopGracefully() {
    stop(SHUTDOWN_GRACE.inWholeMilliseconds, SHUTDOWN_TIMEOUT.inWholeMilliseconds)
}

/**
 * Runs [server] over the MCP Streamable HTTP transport on [port] and blocks until shutdown.
 *
 * With no [allowedHosts]/[allowedOrigins] the SDK's DNS-rebinding protection stays at its
 * (secure) default, which only admits requests whose Host/Origin resolve to localhost —
 * connect via `http://127.0.0.1:port/mcp`. Passing extra hosts *appends* to the localhost
 * defaults (the SDK would otherwise replace them); comparison is hostname-only, so entries
 * need no port. The SDK also caps POST bodies (4 MiB default), left at its default here.
 *
 * The SDK still offers an `eventStore` for SSE resumability; it is deliberately not used —
 * spec revision 2026-07-28 removes stream resumability (`Last-Event-ID`) from Streamable HTTP,
 * so adopting it now would buy a feature on its way out.
 *
 * On SIGTERM the engine drains rather than cuts; see [SHUTDOWN_GRACE]. The hook is installed
 * **before** `start`, because `addShutdownHook` registers itself off the `ApplicationStarting`
 * event and a hook added afterwards would never arm.
 */
fun runHttpServer(
    server: Server,
    port: Int,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
) {
    val log = Logger.withTag("HttpTransport")
    log.i { "MCP Streamable HTTP endpoint on http://127.0.0.1:$port/mcp" }
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    val engine = embeddedServer(CIO, port = port) {
        mcpStreamableHttp(
            allowedHosts = (LOCALHOST_HOSTS + allowedHosts).takeIf { allowedHosts.isNotEmpty() },
            allowedOrigins = (LOCALHOST_HOSTS + allowedOrigins).takeIf { allowedOrigins.isNotEmpty() },
            // A conversion can hold the stream open for --conversion-timeout (120 s by default)
            // with only three progress frames in between, which idle-timeout proxies and clients
            // read as a dead connection. Ktor's own default period is also 30 s — stated here so
            // the value is greppable next to that timeout.
            sseHeartbeatConfig = { period = SSE_HEARTBEAT_PERIOD },
        ) { server }
    }
    engine.addShutdownHook {
        log.i { "Shutting down: draining in-flight calls for up to $SHUTDOWN_GRACE" }
        engine.stopGracefully()
    }
    engine.start(wait = true)
}
