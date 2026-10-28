package app.oreshkov.oracleformsmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Where the HTTP transport listens unless told otherwise: loopback only, per the MCP guidance for
 * local servers. A container needs `--host 0.0.0.0` instead, since its loopback is its own.
 */
internal const val DEFAULT_HTTP_HOST: String = "127.0.0.1"

/** The SDK's DNS-rebinding protection default: only these Host values are admitted. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/**
 * The same three as Origin entries. The SDK parses an Origin entry as a URL and refuses one with no
 * scheme at startup, so [LOCALHOST_HOSTS] cannot double as this list. Comparison is by host only,
 * so these also admit `https://` and any port.
 */
private val LOCALHOST_ORIGINS = listOf("http://localhost", "http://127.0.0.1", "http://[::1]")

/** Keep-alive cadence for idle SSE streams; see the note in [runHttpServer]. */
private val SSE_HEARTBEAT_PERIOD = 30.seconds

/**
 * How long a stateful session may go without a request before it is closed; its id then answers
 * `404`, on which a conforming client re-initializes. The SDK's own default since 0.16 — stated here
 * so a change of that default shows up as a diff rather than as different behaviour. Not
 * `Duration.INFINITE`: on a server with no authentication, abandoned sessions would then pile up
 * until [MAX_SESSIONS] refuses everyone. See the note in [runHttpServer] for what counts as idle.
 */
private val SESSION_IDLE_TIMEOUT = 30.minutes

/** Open-session ceiling, counting sessions still initializing; the next `initialize` gets `503`. The SDK default. */
private const val MAX_SESSIONS = 10_000

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
 * Runs [server] over the MCP Streamable HTTP transport on [address]:[port] and blocks until shutdown.
 *
 * [address] is resolved once, by [bindAddress], and bound as its literal, so the loopback decision,
 * the bind and the log all describe the same address — a name the engine resolved again could differ.
 *
 * The transport has **no authentication**, so [address] decides who may call every tool — including
 * `fetch_module`, which runs the configured converter, and the annotation writes. It defaults to
 * loopback ([DEFAULT_HTTP_HOST]); any other address is logged as a warning at startup, because the
 * Host/Origin check below stops a browser being used against the server (DNS rebinding) and nothing
 * else — a client on the network can send `Host: 127.0.0.1` itself.
 *
 * With no [allowedHosts]/[allowedOrigins] the SDK's DNS-rebinding protection stays at its
 * (secure) default, which only admits requests whose Host/Origin resolve to localhost —
 * connect via `http://127.0.0.1:port/mcp`. Extra entries of either kind *append* to the localhost
 * defaults, and the Origin check stays on whichever kind was given (see [allowLists]); comparison
 * is hostname-only, so entries need no port. A request with no `Origin` at all is admitted: only a
 * browser sends one, and a client that is not a browser cannot be rebound. The SDK also caps POST
 * bodies (4 MiB default), left at its default here.
 *
 * The SDK still offers an `eventStore` for SSE resumability; it is deliberately not used —
 * spec revision 2026-07-28 removes stream resumability (`Last-Event-ID`) from Streamable HTTP,
 * so adopting it now would buy a feature on its way out.
 *
 * Sessions are stateful and **expire** ([SESSION_IDLE_TIMEOUT], at most [MAX_SESSIONS] open). A
 * session is idle only while none of its POST/DELETE requests is in flight, so a `fetch_module`
 * conversion that holds its call for the whole `--conversion-timeout` is never expired mid-call.
 * An open `GET` stream does **not** keep it alive — the engine may never notice that a listener has
 * gone — so the SSE heartbeat keeps proxies from cutting the stream but not the session from
 * expiring: a client that only listens for `list_changed` loses its session after the timeout and
 * gets `404` on its next request, which the spec answers with a fresh `initialize`. A `GET` with no
 * `Mcp-Session-Id` is `400`. Nothing here keys state on the session id (all of it is per module or
 * per element), so a re-initialized client loses only the SDK's per-session `logging/setLevel`.
 *
 * On SIGTERM the engine drains rather than cuts; see [SHUTDOWN_GRACE]. The hook is installed
 * **before** `start`, because `addShutdownHook` registers itself off the `ApplicationStarting`
 * event and a hook added afterwards would never arm.
 */
fun runHttpServer(
    server: Server,
    port: Int,
    address: InetAddress = bindAddress(DEFAULT_HTTP_HOST),
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
) {
    val log = Logger.withTag("HttpTransport")
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    val engine = httpEngine(server, port, address.hostAddress, allowedHosts, allowedOrigins)
    // The port is only known once it is bound (it may be 0), so everything that names the listener
    // is logged from what the engine resolved — after the bind, never before it. Ktor's own
    // "Responding at" line is read the same way, off the application's scope.
    engine.monitor.subscribe(ApplicationStarted) { application ->
        application.launch {
            engine.engine.resolvedConnectors().forEach {
                val bound = "${address.hostAddress}:${it.port}"
                log.i { "MCP Streamable HTTP endpoint on ${endpointUrl(address, it.port)} (bound to $bound)" }
                bindWarnings(address, it.port, allowedHosts).forEach { warning -> log.w { warning } }
            }
        }
    }
    engine.addShutdownHook {
        log.i { "Shutting down: draining in-flight calls for up to $SHUTDOWN_GRACE" }
        engine.stopGracefully()
    }
    engine.start(wait = true)
}

/**
 * The engine [runHttpServer] runs, extracted so the address it listens on can be tested.
 *
 * [sessionIdleTimeout] and [maxSessions] exist only so `HttpSessionLifecycleTest` can shrink them;
 * there is deliberately no command-line option for either.
 */
internal fun httpEngine(
    server: Server,
    port: Int,
    host: String = DEFAULT_HTTP_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
    sessionIdleTimeout: Duration = SESSION_IDLE_TIMEOUT,
    maxSessions: Int = MAX_SESSIONS,
): EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = host) {
    val allow = allowLists(allowedHosts, allowedOrigins)
    mcpStreamableHttp(
        allowedHosts = allow.hosts,
        allowedOrigins = allow.origins,
        // A conversion can hold the stream open for --conversion-timeout (120 s by default)
        // with only three progress frames in between, which idle-timeout proxies and clients
        // read as a dead connection. Ktor's own default period is also 30 s — stated here so
        // the value is greppable next to that timeout.
        sseHeartbeatConfig = { period = SSE_HEARTBEAT_PERIOD },
        sessionIdleTimeout = sessionIdleTimeout,
        maxSessions = maxSessions,
    ) { server }
}

/** The Host and Origin allowlists handed to the SDK; `null` leaves that one at the SDK's default. */
internal data class AllowLists(val hosts: List<String>?, val origins: List<String>?)

/**
 * The allowlists for [extraHosts]/[extraOrigins]: both `null` when there are none, which keeps the
 * SDK's own localhost-only default for both headers, and otherwise **both** given in full, each
 * localhost default *plus* its extras. Both, because of two SDK rules:
 *
 * - it replaces a default with whatever list it is given, so passing the extras alone would stop
 *   `localhost` itself from connecting the moment one extra entry is allowed;
 * - it checks `Origin` only when it is given origins or keeps its own *host* default, so custom
 *   hosts with `null` origins switch the Origin check off. The spec makes that check a MUST
 *   (a present, invalid `Origin` is answered `403`), so it is never left `null` beside custom hosts.
 */
internal fun allowLists(extraHosts: List<String>, extraOrigins: List<String>): AllowLists =
    if (extraHosts.isEmpty() && extraOrigins.isEmpty()) {
        AllowLists(hosts = null, origins = null)
    } else {
        AllowLists(hosts = LOCALHOST_HOSTS + extraHosts, origins = LOCALHOST_ORIGINS + extraOrigins)
    }

/**
 * Why [host] cannot be an `--allowed-host` entry, or `null` when it can.
 *
 * The SDK checks its entries only when the engine starts, and a bad one stops the server with an
 * `IllegalStateException` that names no option; this is the same check made when the command line
 * is parsed. It is stricter than the SDK's, never looser: a Host header's own form, `name[:port]`,
 * with an IPv6 literal in brackets.
 */
internal fun allowedHostProblem(host: String): String? = when {
    PLAIN_HOST.matches(host) || BRACKETED_HOST.matches(host) -> null
    "://" in host -> "a Host value has no scheme; give the name alone, e.g. ${host.substringAfter("://").substringBefore('/')}"
    host.count { it == ':' } > 1 && '[' !in host -> "an IPv6 address goes in brackets, as in a Host header: [$host]"
    else -> "expected a host name or address, optionally with a port, e.g. forms.example or 192.168.1.10"
}

private val PLAIN_HOST = Regex("""[^\s:\[\]/@?#]+(:\d+)?""")
private val BRACKETED_HOST = Regex("""\[[^\s\[\]/@?#]+](:\d+)?""")

/**
 * Why [origin] cannot be an `--allowed-origin` entry, or `null` when it can.
 *
 * The SDK parses entries with Ktor's lenient URL parser and stops the server at startup on one it
 * cannot read, a schemeless `forms.example` included. This accepts only what a browser actually
 * sends, `scheme://host[:port]` (a trailing `/` is forgiven), so it is stricter than that parser and
 * everything it accepts also starts the server.
 */
internal fun allowedOriginProblem(origin: String): String? {
    if ("://" !in origin) return "an origin starts with its scheme, e.g. https://$origin"
    val uri = try {
        URI(origin)
    } catch (_: URISyntaxException) {
        null
    }
    val isOrigin = uri != null && uri.host != null && uri.rawUserInfo == null &&
        uri.rawPath.orEmpty() in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null
    return if (isOrigin) null else "expected an origin as a browser sends it, scheme://host[:port], e.g. https://forms.example"
}

/**
 * The address `--host` names, resolved the way the engine would have resolved it (the first
 * address), but once. A name that does not resolve fails here, naming the option, rather than as
 * an `UnresolvedAddressException` from inside the engine.
 */
internal fun bindAddress(host: String): InetAddress =
    try {
        InetAddress.getByName(host)
    } catch (e: UnknownHostException) {
        throw IllegalArgumentException("--host '$host' does not resolve to an address", e)
    }

/**
 * What the operator must be told about a listener on [address]:[port], logged after the bind.
 *
 * Off loopback, the transport's lack of authentication is the point: anyone who can reach the
 * port can call every tool. And a specific non-loopback address that is not also in
 * [allowedHosts] is one the Host check rejects, so the endpoint just logged would not answer a
 * client that used it.
 */
internal fun bindWarnings(address: InetAddress, port: Int, allowedHosts: List<String>): List<String> {
    if (address.isLoopbackAddress) return emptyList()
    val exposed = "HTTP transport listening on ${address.hostAddress}:$port, which is not a loopback " +
        "address, and it has no authentication: any client that can reach this port can call every " +
        "tool, including fetch_module (which runs the configured converter) and the annotation writes. " +
        "The Host/Origin check only stops DNS rebinding from a browser. Bind to $DEFAULT_HTTP_HOST " +
        "unless something in front restricts who can connect — a container port published on the " +
        "host's loopback only (-p 127.0.0.1:$port:$port), a firewall, or an authenticating proxy."
    if (address.isAnyLocalAddress) return listOf(exposed)
    // The Host header's spelling, so the advice below is a value --allowed-host accepts.
    val literal = urlHost(address)
    if (allowedHosts.any { it.equals(literal, ignoreCase = true) }) return listOf(exposed)
    val rejected = "Requests addressed to $literal are rejected by the Host check until it is allowed: " +
        "add --allowed-host $literal for clients to use ${endpointUrl(address, port)}."
    return listOf(exposed, rejected)
}

/**
 * The URL to give a client for a listener on [address]:[port]. A wildcard bind (`0.0.0.0`, `::`)
 * is no address to connect *to*, so it is shown as loopback, which it includes; an IPv6 address is
 * bracketed, with its zone ID's `%` encoded as `%25` (RFC 6874), as a URL requires.
 */
internal fun endpointUrl(address: InetAddress, port: Int): String = "http://${urlHost(address)}:$port/mcp"

/** The host part of [endpointUrl], which is also what a client using that URL sends as `Host`. */
private fun urlHost(address: InetAddress): String = when {
    address.isAnyLocalAddress -> DEFAULT_HTTP_HOST
    address is Inet6Address -> "[${address.hostAddress.replace("%", "%25")}]"
    else -> address.hostAddress
}
