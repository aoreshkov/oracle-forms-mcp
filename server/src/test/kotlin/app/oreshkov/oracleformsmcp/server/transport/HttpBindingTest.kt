package app.oreshkov.oracleformsmcp.server.transport

import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * The HTTP transport has no authentication of its own, so where it listens *is* who may call it.
 *
 * The Host/Origin allowlist stops a browser being turned against the server by DNS rebinding, but
 * not a client on the network that simply sends `Host: 127.0.0.1` — so the only thing keeping such
 * a client out is a listener it cannot reach. These tests prove reachability with real sockets
 * against an address of this machine that is not loopback, not by reading back the configuration.
 */
class HttpBindingTest {

    private fun mcpServer() = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities()),
    )

    /**
     * An address of this machine that the probe demonstrably reaches, or `null` when there is none.
     *
     * "Not reached" only means something when the same probe *does* reach a listener that is
     * there: a firewall that silently drops packets, or a VPN or virtual adapter with no route,
     * would otherwise make the loopback-only assertion pass whatever the engine bound. So each
     * candidate is checked first against a plain socket on the wildcard address.
     */
    private fun probeableLanAddress(): InetAddress? {
        val candidates = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        return ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")).use { control ->
            candidates.firstOrNull { connects(it, control.localPort) }
        }
    }

    /**
     * Whether a TCP connection is accepted. A refusal can arrive as a timeout — Windows retries a
     * refused connect for about 2 s before reporting it — or as any other socket error (no route).
     * [probeableLanAddress] is what keeps a `false` here from being the probe being blind.
     */
    private fun connects(address: InetAddress, port: Int): Boolean =
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(address, port), 5_000)
                true
            } catch (_: SocketException) {
                false
            } catch (_: SocketTimeoutException) {
                false
            }
        }

    private fun <T> running(engine: EmbeddedServer<*, *>, block: (port: Int) -> T): T {
        engine.start(wait = false)
        try {
            val port = runBlocking { engine.engine.resolvedConnectors().first().port }
            return block(port)
        } finally {
            engine.stop(0, 0)
        }
    }

    @Test
    fun `by default the listener is reachable on loopback and nowhere else`() {
        val lan = probeableLanAddress()
        assumeTrue("no non-loopback IPv4 address of this machine is reachable to probe", lan != null)

        running(httpEngine(mcpServer(), port = 0)) { port ->
            assertTrue(connects(InetAddress.getByName("127.0.0.1"), port), "not reachable on loopback")
            assertFalse(connects(lan!!, port), "reachable on $lan — the listener is not loopback-only")
        }
    }

    /** The other half: without it, a host argument that never reaches the engine would pass above. */
    @Test
    fun `an explicit wildcard host is reachable from the network`() {
        val lan = probeableLanAddress()
        assumeTrue("no non-loopback IPv4 address of this machine is reachable to probe", lan != null)

        running(httpEngine(mcpServer(), port = 0, host = "0.0.0.0")) { port ->
            assertTrue(connects(lan!!, port), "not reachable on $lan although bound to 0.0.0.0")
        }
    }
}
