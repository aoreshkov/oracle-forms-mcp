package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.server.CopyingConverter
import app.oreshkov.oracleformsmcp.server.FakeClientConnection
import app.oreshkov.oracleformsmcp.server.FakeScanner
import app.oreshkov.oracleformsmcp.server.fakeService
import app.oreshkov.oracleformsmcp.server.resources.addModuleIndexResource
import app.oreshkov.oracleformsmcp.server.resources.moduleIndexUri
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The `fetch_module` tool as a client sees it, driven through the registered handler.
 *
 * Guards the composition that unit tests of `FormsService` and `ModuleResources` miss: the tool
 * fetches *and then* registers a resource, both inside `guarded { }`. Before SDK 0.15 that was
 * safe because handlers ran one at a time; now they do not, and a swallowed
 * `IllegalArgumentException` from a duplicate registration would report a successful fetch as an
 * error.
 */
class FetchModuleToolTest {

    private val temp: Path = Files.createTempDirectory("fetch-module-tool-test")
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun request() = CallToolRequest(
        CallToolRequestParams(
            name = "fetch_module",
            arguments = buildJsonObject { put("module", JsonPrimitive(ordersKey.toString())) },
        )
    )

    @Test
    fun concurrentFetchModuleCallsAllReportSuccess() = runBlocking {
        val source = temp.resolve("orders_fmb.xml").apply { writeText("<Module/>") }
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(listOf(ScannedModule(key = ordersKey, preConvertedPath = source.toString()))),
            converter = converter,
            cacheRoot = temp.resolve("cache"),
        )
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                ),
            ),
        ) {
            registerFetchModuleTool(service) { key -> addModuleIndexResource(service, key) }
        }
        val handler = server.tools.getValue("fetch_module").handler
        val connection = FakeClientConnection()

        val results = List(8) { async(Dispatchers.Default) { connection.handler(request()) } }.awaitAll()

        results.forEachIndexed { i, result ->
            assertFalse(result.isError == true, "call #$i reported an error: ${result.content}")
        }
        assertEquals(1, converter.targetDirs.size, "the module was converted more than once")
        assertEquals(listOf(moduleIndexUri(ordersKey)), server.resources.keys.toList())
    }
}
