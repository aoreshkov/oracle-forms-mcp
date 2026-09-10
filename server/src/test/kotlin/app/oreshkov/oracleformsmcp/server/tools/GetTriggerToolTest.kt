package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.server.FakeClientConnection
import app.oreshkov.oracleformsmcp.server.FakeScanner
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.fakeService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `get_trigger` as a client sees it, over the real parser and the subclassed fixture pair.
 *
 * `FormsService` tests cover the inheritance logic; what is guarded here is the adapter — that
 * `resolve` reaches the service as a boolean and that the inheritance fields survive into
 * `structuredContent`, which is the payload a client validates against the tool's `outputSchema`.
 */
class GetTriggerToolTest {

    private val temp: Path = Files.createTempDirectory("get-trigger-tool-test")
    private val pickerKey = ModuleKey.of("picker", ModuleType.FORM)
    private val toolbarKey = ModuleKey.of("toolbar", ModuleType.FORM)

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun fixture(name: String): String {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        val target = temp.resolve(name)
        resource.use { Files.copy(it, target) }
        return target.toString()
    }

    private val service: FormsService = fakeService(
        scanner = FakeScanner(
            listOf(
                ScannedModule(key = pickerKey, preConvertedPath = fixture("picker_fmb.xml")),
                ScannedModule(key = toolbarKey, preConvertedPath = fixture("toolbar_fmb.xml")),
            ),
        ),
        cacheRoot = temp.resolve("cache"),
        parser = FormsModuleParser(),
    )

    private val connection = FakeClientConnection()

    private val handler = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    ) {
        registerGetTriggerTool(service)
    }.tools.getValue("get_trigger").handler

    private suspend fun getTrigger(resolve: Boolean?): CallToolResult = connection.handler(
        CallToolRequest(
            CallToolRequestParams(
                name = "get_trigger",
                arguments = buildJsonObject {
                    put("module", JsonPrimitive("picker"))
                    put("name", JsonPrimitive("WHEN-BUTTON-PRESSED"))
                    put("ownerPath", JsonPrimitive("BAR_LIST.SELECT"))
                    if (resolve != null) put("resolve", JsonPrimitive(resolve))
                },
            ),
        ),
    )

    private fun CallToolResult.field(name: String): String? =
        structuredContent?.get(name)?.jsonPrimitive?.contentOrNull()

    private fun JsonPrimitive.contentOrNull(): String? = if (isString || content != "null") content else null

    @Test
    fun theInheritedPointerReachesStructuredContent() = runBlocking {
        service.fetchModule(pickerKey)

        val result = getTrigger(resolve = null)
        assertFalse(result.isError == true, "${result.content}")
        assertEquals("INHERITED", result.field("bodySource"))
        assertEquals("", result.field("text"))
        val inherited = result.structuredContent!!.getValue("inherited") as JsonObject
        assertEquals("TOOLBAR", inherited.getValue("module").jsonPrimitive.content)
        assertEquals("BAR.SELECT", inherited.getValue("ownerPath").jsonPrimitive.content)
        assertTrue(result.field("hint")!!.contains("fetch_module(module=\"TOOLBAR.fmb\")"))
    }

    @Test
    fun theResolveArgumentReachesTheService() = runBlocking {
        service.fetchModule(pickerKey)
        service.fetchModule(toolbarKey)

        assertEquals("INHERITED", getTrigger(resolve = false).field("bodySource"))

        val resolved = getTrigger(resolve = true)
        assertEquals("RESOLVED", resolved.field("bodySource"))
        assertTrue(resolved.field("text")!!.contains("Do_Key('HELP')"))
    }
}
