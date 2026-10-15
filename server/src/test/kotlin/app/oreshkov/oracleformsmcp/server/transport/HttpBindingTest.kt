package app.oreshkov.oracleformsmcp.server.transport

import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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

    /** An address of this machine that a LAN client could connect to, or `null` when it has none. */
    private fun nonLoopbackAddress(): InetAddress? =
        NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    /**
     * Whether a TCP connection is accepted. A refusal can arrive as a timeout: Windows retries a
     * refused connect for about 2 s before reporting it. A timeout is also what a firewall that
     * drops packets would produce, which is why the wildcard test exists — it shows that a real
     * listener on the same address *is* reached, so "not reached" here is not the probe being blind.
     */
    private fun connects(address: InetAddress, port: Int): Boolean =
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(address, port), 5_000)
                true
            } catch (_: ConnectException) {
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
        val lan = nonLoopbackAddress()
        assumeTrue("this machine has no non-loopback IPv4 address to probe", lan != null)

        running(httpEngine(mcpServer(), port = 0)) { port ->
            assertTrue(connects(InetAddress.getByName("127.0.0.1"), port), "not reachable on loopback")
            assertFalse(connects(lan!!, port), "reachable on $lan — the listener is not loopback-only")
        }
    }

    /** The other half: without it, a host argument that never reaches the engine would pass above. */
    @Test
    fun `an explicit wildcard host is reachable from the network`() {
        val lan = nonLoopbackAddress()
        assumeTrue("this machine has no non-loopback IPv4 address to probe", lan != null)

        running(httpEngine(mcpServer(), port = 0, host = "0.0.0.0")) { port ->
            assertTrue(connects(lan!!, port), "not reachable on $lan although bound to 0.0.0.0")
        }
    }
}
