package app.oreshkov.oracleformsmcp.server.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure decisions [runHttpServer] makes before it binds: who is allowed in, and what it reports. */
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
    fun `only addresses that stay on this machine count as loopback`() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("::1"))
        assertFalse(isLoopbackHost("0.0.0.0"), "the wildcard includes every interface")
        assertFalse(isLoopbackHost("::"))
        assertFalse(isLoopbackHost("192.168.1.10"))
    }

    @Test
    fun `the logged endpoint is an address a client can connect to`() {
        assertEquals("http://127.0.0.1:3000/mcp", endpointUrl("127.0.0.1", 3000))
        assertEquals("http://127.0.0.1:3000/mcp", endpointUrl("0.0.0.0", 3000))
        assertEquals("http://[::1]:3000/mcp", endpointUrl("::1", 3000))
        assertEquals("http://192.168.1.10:8080/mcp", endpointUrl("192.168.1.10", 8080))
    }
}
