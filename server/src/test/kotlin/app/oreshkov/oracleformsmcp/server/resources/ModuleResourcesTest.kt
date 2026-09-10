package app.oreshkov.oracleformsmcp.server.resources

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.server.fakeService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.utils.PathSegmentTemplateMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class ModuleResourcesTest {

    private fun serverWithResources(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
            ),
        ),
    )

    /**
     * Since MCP SDK 0.15 a duplicate `addResource` throws instead of silently replacing, and tool
     * handlers run concurrently — so two first-time fetches of one module can both pass the
     * "already registered?" check. Losing that race must not fail the fetch.
     */
    @Test
    fun concurrentRegistrationOfOneModuleKeepsExactlyOneResource() = runBlocking {
        val server = serverWithResources()
        val service = fakeService()
        val key = ModuleKey.of("orders", ModuleType.FORM)

        List(8) { async(Dispatchers.Default) { server.addModuleIndexResource(service, key) } }.awaitAll()

        assertEquals(listOf(moduleIndexUri(key)), server.resources.keys.toList())
    }

    /**
     * `resources/list` has no cursor in the Kotlin SDK and the client issues it unprompted, so one
     * registration per cached module made a warm cache over a real forms directory overflow the
     * client before the model called anything. The registered set must stay bounded however many
     * modules are fetched, and the most recent fetches are the ones that survive.
     */
    @Test
    fun resourcesListStaysBoundedHoweverManyModulesAreFetched() = runBlocking {
        val server = serverWithResources()
        val indexResources = ModuleIndexResources(fakeService(), limit = 5)
        val keys = (0 until 60).map { ModuleKey.of("MOD%02d".format(it), ModuleType.FORM) }

        keys.forEach { indexResources.register(server, it) }

        assertEquals(5, server.resources.size)
        assertEquals(keys.takeLast(5).map(::moduleIndexUri), indexResources.registeredUris())
        assertEquals(indexResources.registeredUris().toSet(), server.resources.keys.toSet())
    }

    /** Re-fetching a still-registered module refreshes its recency instead of registering twice. */
    @Test
    fun refetchingAModuleRefreshesRecencyWithoutDuplicating() = runBlocking {
        val server = serverWithResources()
        val indexResources = ModuleIndexResources(fakeService(), limit = 2)
        val (a, b, c) = listOf("A", "B", "C").map { ModuleKey.of(it, ModuleType.FORM) }

        listOf(a, b, a, c).forEach { indexResources.register(server, it) }

        // B was the oldest by the time C arrived, because re-registering A moved A to the front.
        assertEquals(listOf(a, c).map(::moduleIndexUri), indexResources.registeredUris())
        assertEquals(2, server.resources.size)
    }

    @Test
    fun indexTemplateIsRegisteredWithMetadata() {
        val server = serverWithResources().apply { registerModuleIndexTemplate(fakeService()) }
        val template = assertNotNull(
            server.resourceTemplates.find { it.uriTemplate == MODULE_INDEX_URI_TEMPLATE },
            "module index template not registered",
        )
        assertNotNull(template.description)
        assertEquals("application/json", template.mimeType)
    }

    /**
     * Regression canary: a custom matcher would only be needed if a dependency shadowed
     * `kotlinx.collections.immutable` and broke the SDK default with a NoSuchMethodError. This
     * project has no such dependency, so the SDK default must work — if this test ever throws
     * NoSuchMethodError, a new dependency reintroduced the classpath shadow.
     */
    @Test
    fun sdkDefaultMatcherExtractsTheModuleSegment() {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        val matcher = PathSegmentTemplateMatcher.factory.create(
            ResourceTemplate(uriTemplate = MODULE_INDEX_URI_TEMPLATE, name = "t"),
        )
        val match = assertNotNull(matcher.match(moduleIndexUri(key)))
        assertEquals(mapOf("module" to "ORDERS.fmb"), match.variables)
    }

    /**
     * Templates, not one resource per sidecar. A form yields a sidecar per trigger, program unit
     * and menu command — hundreds per module — and `resources/list` has no cursor in the SDK, so
     * registering them individually would rebuild the overflow the bounded index set removed.
     */
    @Test
    fun sourceFilesAreReachableByTemplateWithoutListingOneResourceEach() {
        val server = serverWithResources().apply { registerSourceTemplates(fakeService()) }

        assertEquals(
            listOf(MODULE_CONVERTED_URI_TEMPLATE, MODULE_PLSQL_URI_TEMPLATE),
            server.resourceTemplates.map { it.uriTemplate },
        )
        assertEquals(0, server.resources.size, "source files must add nothing to resources/list")
        server.resourceTemplates.forEach { assertNotNull(it.description) }
    }

    /**
     * The source templates add a two- and a four-segment shape to the same scheme. The default
     * matcher is RFC 6570 Level 1 — one variable per segment — so the four-segment one is only
     * safe because every part of a sidecar path is a single segment; this is the canary for that.
     */
    @Test
    fun sdkDefaultMatcherExtractsTheSourceSegments() {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        val converted = PathSegmentTemplateMatcher.factory.create(
            ResourceTemplate(uriTemplate = MODULE_CONVERTED_URI_TEMPLATE, name = "t"),
        )
        assertEquals(
            mapOf("module" to "ORDERS.fmb"),
            assertNotNull(converted.match(moduleConvertedUri(key))).variables,
        )

        val plsql = PathSegmentTemplateMatcher.factory.create(
            ResourceTemplate(uriTemplate = MODULE_PLSQL_URI_TEMPLATE, name = "t"),
        )
        val uri = modulePlsqlUri(key, "triggers", "ORDERS.KEY-COMMIT.sql")
        assertEquals(
            mapOf("module" to "ORDERS.fmb", "category" to "triggers", "name" to "ORDERS.KEY-COMMIT.sql"),
            assertNotNull(plsql.match(uri)).variables,
        )
        // The shapes stay distinct: neither template answers for the other's URIs.
        assertNull(converted.match(uri))
        assertNull(plsql.match(moduleConvertedUri(key)))
        assertNull(plsql.match(moduleIndexUri(key)))
    }

    /**
     * URI segments reach handlers percent-decoded and may contain `/` or `..`, so what the
     * handlers accept is stated positively rather than by blocklist.
     */
    @Test
    fun onlyPlainSegmentsAreAcceptedInASourcePath() {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        assertEquals("plsql/triggers/A.sql", sourceRefPath(key, modulePlsqlUri(key, "triggers", "A.sql"), "c/o.xml"))
        assertNull(sourceRefPath(key, "oracleforms://ORDERS.fmb/plsql/../../etc/passwd", "c/o.xml"))
        assertNull(sourceRefPath(key, "oracleforms://OTHER.fmb/converted", "c/o.xml"), "another module's URI")
        assertNull(sourceRefPath(key, "plsql/triggers/A.sql", "c/o.xml"), "not a URI at all")
        assertNull(sourceUri(key, "plsql/triggers/../../escape.sql"))
        assertNull(sourceUri(key, "somewhere/else.txt"), "an unknown ref shape gets no URI")
    }

    @Test
    fun sdkDefaultMatcherRejectsUrisWithDifferentShape() {
        val matcher = PathSegmentTemplateMatcher.factory.create(
            ResourceTemplate(uriTemplate = MODULE_INDEX_URI_TEMPLATE, name = "t"),
        )
        assertNull(matcher.match("oracleforms://ORDERS.fmb"), "missing /index")
        assertNull(matcher.match("oracleforms://ORDERS.fmb/xml"), "wrong literal")
        assertNull(matcher.match("otherscheme://ORDERS.fmb/index"), "wrong scheme")
    }
}
