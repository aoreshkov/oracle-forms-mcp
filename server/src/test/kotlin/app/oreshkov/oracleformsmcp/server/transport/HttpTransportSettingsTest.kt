package app.oreshkov.oracleformsmcp.server.transport

import java.net.Inet6Address
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure decisions behind [runHttpServer]: what it binds, who is allowed in, and what it reports. */
class HttpTransportSettingsTest {

    private val localhostHosts = listOf("localhost", "127.0.0.1", "[::1]")
    private val localhostOrigins = listOf("http://localhost", "http://127.0.0.1", "http://[::1]")

    @Test
    fun `no extra entries keeps the SDK default`() {
        assertEquals(AllowLists(hosts = null, origins = null), allowLists(emptyList(), emptyList()))
    }

    @Test
    fun `extra entries are added to localhost, not substituted for it`() {
        assertEquals(
            AllowLists(localhostHosts + "forms.example", localhostOrigins + "https://forms.example"),
            allowLists(listOf("forms.example"), listOf("https://forms.example")),
        )
    }

    /** The SDK skips the Origin check for custom hosts with `null` origins; see [allowLists]. */
    @Test
    fun `extra hosts alone keep the Origin check on localhost`() {
        assertEquals(AllowLists(localhostHosts + "forms.example", localhostOrigins), allowLists(listOf("forms.example"), emptyList()))
    }

    /** The SDK parses Origin entries as URLs; a bare `localhost` stopped the server at startup. */
    @Test
    fun `extra origins alone keep the localhost origins as URLs`() {
        val origins = allowLists(emptyList(), listOf("https://forms.example")).origins.orEmpty()
        assertEquals(localhostOrigins + "https://forms.example", origins)
        assertTrue(origins.all { allowedOriginProblem(it) == null }, "$origins")
    }

    @Test
    fun `an allowed host is a Host header value`() {
        listOf("forms.example", "FORMS.example", "192.168.1.10", "forms.example:3000", "[::1]", "[fe80::1%257]:3000")
            .forEach { assertNull(allowedHostProblem(it), it) }
        assertContains(allowedHostProblem("https://forms.example:3000/mcp").orEmpty(), "e.g. forms.example:3000")
        assertContains(allowedHostProblem("fe80::1").orEmpty(), "[fe80::1]")
        listOf("", "forms example", "user@forms.example", "forms.example:http", "[::1", ":3000")
            .forEach { assertNotNull(allowedHostProblem(it), "accepted '$it'") }
    }

    @Test
    fun `an allowed origin is what a browser sends`() {
        listOf("https://forms.example", "http://forms.example:8080", "http://[::1]:3000", "https://forms.example/")
            .forEach { assertNull(allowedOriginProblem(it), it) }
        assertContains(allowedOriginProblem("forms.example").orEmpty(), "https://forms.example")
        listOf("https://", "https://forms.example/mcp", "https://u@forms.example", "https://forms.example?x", "null", "a b://c")
            .forEach { assertNotNull(allowedOriginProblem(it), "accepted '$it'") }
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

    /** Unbracketed, the advice was a value the SDK refuses at startup. */
    @Test
    fun `the advice for an IPv6 address is a value --allowed-host accepts`() {
        val warnings = bindWarnings(bindAddress("fe80::1"), 3000, emptyList())
        val advised = "[fe80:0:0:0:0:0:0:1]"
        assertContains(warnings.last(), "--allowed-host $advised")
        assertNull(allowedHostProblem(advised))
        assertEquals(1, bindWarnings(bindAddress("fe80::1"), 3000, listOf(advised)).size)
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
