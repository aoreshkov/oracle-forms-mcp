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

class ModuleResourcesTest {

    private fun serverWithResources(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
            ),
        ),
    )

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
