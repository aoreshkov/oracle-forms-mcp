package app.oreshkov.oracleformsmcp.server.transport

import java.net.Inet6Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure decisions behind [runHttpServer]: what it binds, who is allowed in, and what it reports. */
class HttpTransportSettingsTest {

    private val localhost = listOf("localhost", "127.0.0.1", "[::1]")

    @Test
    fun `no extra entries keeps the SDK default`() {
        assertNull(allowList(localhost, emptyList()))
    }

    @Test
    fun `extra entries are added to localhost, not substituted for it`() {
        assertEquals(localhost + "forms.example", allowList(localhost, listOf("forms.example")))
    }

    @Test
    fun `loopback binds warn about nothing`() {
        assertTrue(bindWarnings(bindAddress("127.0.0.1"), 3000, emptyList()).isEmpty())
        assertTrue(bindWarnings(bindAddress("localhost"), 3000, emptyList()).isEmpty())
        assertTrue(bindWarnings(bindAddress("::1"), 3000, emptyList()).isEmpty())
    }

    @Test
    fun `a wildcard bind warns that it is exposed, with the port it actually uses`() {
        val warnings = bindWarnings(bindAddress("0.0.0.0"), 8080, emptyList())
        assertEquals(1, warnings.size, "a wildcard is reached through localhost too, so the Host check admits it")
        assertTrue("no authentication" in warnings.single())
        assertTrue("-p 127.0.0.1:8080:8080" in warnings.single(), warnings.single())
    }

    @Test
    fun `a specific address the Host check would reject says which option admits it`() {
        val warnings = bindWarnings(bindAddress("192.168.1.10"), 3000, emptyList())
        assertEquals(2, warnings.size)
        assertTrue("--allowed-host 192.168.1.10" in warnings.last(), warnings.last())
        assertEquals(1, bindWarnings(bindAddress("192.168.1.10"), 3000, listOf("192.168.1.10")).size)
    }

    @Test
    fun `a name that resolves to nothing is reported against the option`() {
        val e = assertFailsWith<IllegalArgumentException> { bindAddress("no-such-host.invalid") }
        assertTrue("--host" in e.message.orEmpty())
    }

    @Test
    fun `the logged endpoint is an address a client can connect to`() {
        assertEquals("http://127.0.0.1:3000/mcp", endpointUrl(bindAddress("127.0.0.1"), 3000))
        assertEquals("http://127.0.0.1:3000/mcp", endpointUrl(bindAddress("0.0.0.0"), 3000))
        assertEquals("http://192.168.1.10:8080/mcp", endpointUrl(bindAddress("192.168.1.10"), 8080))
        assertEquals("http://[0:0:0:0:0:0:0:1]:3000/mcp", endpointUrl(bindAddress("::1"), 3000))
        val scoped = Inet6Address.getByAddress(null, bindAddress("fe80::1").address, 7)
        assertEquals("http://[fe80:0:0:0:0:0:0:1%257]:3000/mcp", endpointUrl(scoped, 3000), "RFC 6874 zone ID")
    }
}
