package app.oreshkov.oracleformsmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Where the HTTP transport listens unless told otherwise: loopback only, per the MCP guidance for
 * local servers. A container needs `--host 0.0.0.0` instead, since its loopback is its own.
 */
internal const val DEFAULT_HTTP_HOST: String = "127.0.0.1"

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
 * Runs [server] over the MCP Streamable HTTP transport on [host]:[port] and blocks until shutdown.
 *
 * The transport has **no authentication**, so [host] decides who may call every tool — including
 * `fetch_module`, which runs the configured converter, and the annotation writes. It defaults to
 * loopback ([DEFAULT_HTTP_HOST]); any other address is logged as a warning at startup, because the
 * Host/Origin check below stops a browser being used against the server (DNS rebinding) and nothing
 * else — a client on the network can send `Host: 127.0.0.1` itself.
 *
 * With no [allowedHosts]/[allowedOrigins] the SDK's DNS-rebinding protection stays at its
 * (secure) default, which only admits requests whose Host/Origin resolve to localhost —
 * connect via `http://127.0.0.1:port/mcp`. Passing extra hosts *appends* to the localhost
 * defaults (see [allowList]); comparison is hostname-only, so entries need no port. The SDK also
 * caps POST bodies (4 MiB default), left at its default here.
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
    host: String = DEFAULT_HTTP_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
) {
    val log = Logger.withTag("HttpTransport")
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    if (!isLoopbackHost(host)) {
        log.w {
            "HTTP transport bound to $host, which is not a loopback address, and it has no " +
                "authentication: any client that can reach this port can call every tool, including " +
                "fetch_module (which runs the configured converter) and the annotation writes. The " +
                "Host/Origin check only stops DNS rebinding from a browser. Bind to $DEFAULT_HTTP_HOST " +
                "unless something in front restricts who can connect — a container port published " +
                "on the host's loopback only (-p 127.0.0.1:3000:3000), a firewall, or an " +
                "authenticating proxy."
        }
    }
    val engine = httpEngine(server, port, host, allowedHosts, allowedOrigins)
    // The connector is only known once it is bound — the port especially, which may be 0 — so the
    // endpoint is logged from what the engine resolved, not from the arguments. Ktor's own
    // "Responding at" line is read the same way, off the application's scope.
    engine.monitor.subscribe(ApplicationStarted) { application ->
        application.launch {
            engine.engine.resolvedConnectors().forEach {
                log.i { "MCP Streamable HTTP endpoint on ${endpointUrl(it.host, it.port)} (bound to ${it.host}:${it.port})" }
            }
        }
    }
    engine.addShutdownHook {
        log.i { "Shutting down: draining in-flight calls for up to $SHUTDOWN_GRACE" }
        engine.stopGracefully()
    }
    engine.start(wait = true)
}

/** The engine [runHttpServer] runs, extracted so the address it listens on can be tested. */
internal fun httpEngine(
    server: Server,
    port: Int,
    host: String = DEFAULT_HTTP_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
): EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = host) {
    mcpStreamableHttp(
        allowedHosts = allowList(LOCALHOST_HOSTS, allowedHosts),
        allowedOrigins = allowList(LOCALHOST_HOSTS, allowedOrigins),
        // A conversion can hold the stream open for --conversion-timeout (120 s by default)
        // with only three progress frames in between, which idle-timeout proxies and clients
        // read as a dead connection. Ktor's own default period is also 30 s — stated here so
        // the value is greppable next to that timeout.
        sseHeartbeatConfig = { period = SSE_HEARTBEAT_PERIOD },
    ) { server }
}

/**
 * The Host (or Origin) allowlist handed to the SDK: `null` when nothing [extra] was configured,
 * which keeps the SDK's own localhost-only default, and otherwise [defaults] *plus* [extra] —
 * the SDK replaces its default with whatever list it is given, so passing [extra] alone would
 * stop `localhost` itself from connecting the moment one extra host is allowed.
 */
internal fun allowList(defaults: List<String>, extra: List<String>): List<String>? =
    if (extra.isEmpty()) null else defaults + extra

/**
 * Whether [host] reaches only this machine. A name that resolves to several addresses counts only
 * when every one of them is loopback, and one that does not resolve at all does not count — the
 * warning it would suppress is the safe side to err on.
 */
internal fun isLoopbackHost(host: String): Boolean =
    runCatching { InetAddress.getAllByName(host).all { it.isLoopbackAddress } }.getOrDefault(false)

/**
 * The URL to give a client for a listener on [host]:[port]. A wildcard bind (`0.0.0.0`, `::`) is
 * no address to connect *to*, so it is shown as loopback, which it includes; an IPv6 literal is
 * bracketed as a URL requires.
 */
internal fun endpointUrl(host: String, port: Int): String {
    val wildcard = runCatching { InetAddress.getByName(host).isAnyLocalAddress }.getOrDefault(false)
    val urlHost = when {
        wildcard -> DEFAULT_HTTP_HOST
        ':' in host && !host.startsWith("[") -> "[$host]"
        else -> host
    }
    return "http://$urlHost:$port/mcp"
}
