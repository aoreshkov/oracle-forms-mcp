package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.server.FakeClientConnection
import app.oreshkov.oracleformsmcp.server.FakeScanner
import app.oreshkov.oracleformsmcp.server.fakeService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `list_modules` as a client sees it: argument parsing and the `structuredContent` shape, which
 * the `FormsService` unit tests do not exercise. The filters and the cursor are the whole point of
 * the tool now, so a schema/adapter mismatch would silently reinstate the unbounded response.
 */
class ListModulesToolTest {

    private val temp: Path = Files.createTempDirectory("list-modules-tool-test")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun serverFor(count: Int): Server {
        val modules = (0 until count).map { i ->
            val name = "MOD%03d".format(i)
            ScannedModule(
                key = ModuleKey.of(name, ModuleType.FORM),
                preConvertedPath = temp.resolve("${name.lowercase()}_fmb.xml").toString(),
            )
        }
        val service = fakeService(scanner = FakeScanner(modules), cacheRoot = temp.resolve("cache"))
        return Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        ) {
            registerListModulesTool(service)
        }
    }

    private suspend fun Server.callListModules(arguments: JsonObject): JsonObject {
        val result = FakeClientConnection().run {
            tools.getValue("list_modules").handler(
                this,
                CallToolRequest(CallToolRequestParams(name = "list_modules", arguments = arguments)),
            )
        }
        assertFalse(result.isError == true, "list_modules reported an error: ${result.content}")
        return assertNotNull(result.structuredContent, "list_modules returned no structuredContent")
    }

    @Test
    fun theToolDeclaresEveryFilterAndPagingArgument() {
        val schema = assertNotNull(serverFor(1).tools.getValue("list_modules").tool.inputSchema.properties)

        assertEquals(
            setOf("pattern", "regex", "type", "status", "limit", "cursor"),
            schema.keys,
        )
        assertEquals(null, serverFor(1).tools.getValue("list_modules").tool.inputSchema.required)
    }

    @Test
    fun filterAndPagingArgumentsReachTheService() = runBlocking {
        val server = serverFor(30)

        val page = server.callListModules(
            buildJsonObject {
                put("pattern", JsonPrimitive("MOD00"))
                put("type", JsonPrimitive("form"))
                put("limit", JsonPrimitive(5))
            },
        )

        assertEquals(5, page["returned"]?.jsonPrimitive?.intOrNull)
        assertEquals(10, page["total"]?.jsonPrimitive?.intOrNull, "MOD000..MOD009 match 'MOD00'")
        assertTrue(page["truncated"]!!.jsonPrimitive.content.toBoolean())

        val next = server.callListModules(
            buildJsonObject {
                put("pattern", JsonPrimitive("MOD00"))
                put("limit", JsonPrimitive(5))
                put("cursor", JsonPrimitive(assertNotNull(page["nextCursor"]?.jsonPrimitive?.contentOrNull)))
            },
        )

        assertEquals(
            listOf("MOD005", "MOD006", "MOD007", "MOD008", "MOD009"),
            next["modules"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

    /** A cursor the model made up is an argument error it can recover from, not a crash. */
    @Test
    fun aBadCursorComesBackAsAnActionableToolError() = runBlocking {
        val server = serverFor(3)

        val result = FakeClientConnection().run {
            server.tools.getValue("list_modules").handler(
                this,
                CallToolRequest(
                    CallToolRequestParams(
                        name = "list_modules",
                        arguments = buildJsonObject { put("cursor", JsonPrimitive("made-up")) },
                    )
                ),
            )
        }

        assertEquals(true, result.isError)
        assertTrue(result.content.toString().contains("nextCursor"), result.content.toString())
    }
}
