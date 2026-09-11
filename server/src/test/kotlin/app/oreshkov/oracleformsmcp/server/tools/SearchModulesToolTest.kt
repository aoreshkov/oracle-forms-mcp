package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.FakeClientConnection
import app.oreshkov.oracleformsmcp.server.FormsService
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `search_modules` as a client sees it: the declared arguments, the `structuredContent` shape, and
 * the errors. The `FormsService` tests cover the scan itself — what is unique here is the adapter,
 * where a schema that does not match the call it makes would silently drop `ignoreCase` or the
 * cursor and leave the tool looking merely unhelpful.
 */
class SearchModulesToolTest {

    private val temp: Path = Files.createTempDirectory("search-modules-tool-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val entry = ModuleKey.of("entry", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        binaryConversion = false,
    )

    private val server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    ) {
        registerSearchModulesTool(service)
    }

    init {
        listOf("entry_fmb.xml", "picker_fmb.xml").forEach { name ->
            val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
            resource.use { Files.copy(it, formsDir.resolve(name)) }
        }
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private suspend fun call(arguments: JsonObject): CallToolResult = FakeClientConnection().run {
        server.tools.getValue("search_modules").handler(
            this,
            CallToolRequest(CallToolRequestParams(name = "search_modules", arguments = arguments)),
        )
    }

    private suspend fun search(arguments: JsonObject): JsonObject {
        val result = call(arguments)
        assertFalse(result.isError == true, "search_modules reported an error: ${result.content}")
        return assertNotNull(result.structuredContent, "search_modules returned no structuredContent")
    }

    @Test
    fun theToolDeclaresEveryQueryFilterAndPagingArgument() {
        val schema = server.tools.getValue("search_modules").tool.inputSchema

        assertEquals(
            setOf("query", "regex", "ignoreCase", "scope", "modulePattern", "maxResults", "cursor"),
            assertNotNull(schema.properties).keys,
        )
        assertEquals(listOf("query"), schema.required)
    }

    @Test
    fun aSearchReachesTheServiceAndComesBackAsStructuredContent() = runBlocking {
        service.fetchModule(entry)

        val result = search(buildJsonObject { put("query", JsonPrimitive("call_form")) })

        val hit = result["hits"]!!.jsonArray.single().jsonObject
        assertEquals(
            "ENTRY.fmb",
            hit["moduleSpec"]!!.jsonPrimitive.content,
            "a hit must name its module the way every other tool takes it",
        )
        assertTrue(hit["uri"]!!.jsonPrimitive.content.startsWith("oracleforms://ENTRY.fmb/plsql/"))
        assertEquals(1, result["scannedModules"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, result["skippedNotCached"]!!.jsonPrimitive.content.toInt(), "PICKER was never fetched")
        assertTrue(assertNotNull(result["hint"]).jsonPrimitive.content.contains("fetch_module"))
    }

    /** `ignoreCase` has to arrive as a boolean argument, or the default silently decides every call. */
    @Test
    fun ignoreCaseReachesTheService() = runBlocking {
        service.fetchModule(entry)

        val insensitive = search(buildJsonObject { put("query", JsonPrimitive("CALL_FORM")) })
        assertEquals(1, insensitive["hits"]!!.jsonArray.size)

        val sensitive = search(
            buildJsonObject {
                put("query", JsonPrimitive("CALL_FORM"))
                put("ignoreCase", JsonPrimitive(false))
            },
        )
        assertEquals(null, sensitive["hits"], "an empty list is omitted, as in every other result")
    }

    @Test
    fun theCursorRoundTripsThroughTheTool() = runBlocking {
        service.fetchModule(entry)
        val first = search(
            buildJsonObject {
                put("query", JsonPrimitive(":GLOBAL"))
                put("maxResults", JsonPrimitive(1))
            },
        )
        val cursor = assertNotNull(first["nextCursor"]).jsonPrimitive.content

        val second = search(
            buildJsonObject {
                put("query", JsonPrimitive(":GLOBAL"))
                put("maxResults", JsonPrimitive(1))
                put("cursor", JsonPrimitive(cursor))
            },
        )

        assertEquals(1, second["hits"]!!.jsonArray.size)
        assertTrue(
            second["hits"]!!.jsonArray.single().jsonObject["line"]!!.jsonPrimitive.content.toInt() >
                first["hits"]!!.jsonArray.single().jsonObject["line"]!!.jsonPrimitive.content.toInt(),
            "the second page must resume after the first",
        )
    }

    /** A cursor the model made up is an argument error it can recover from, not a crash. */
    @Test
    fun aBadCursorComesBackAsAnActionableToolError() = runBlocking {
        val result = call(
            buildJsonObject {
                put("query", JsonPrimitive(":GLOBAL"))
                put("cursor", JsonPrimitive("made-up"))
            },
        )

        assertEquals(true, result.isError)
        assertTrue(result.content.toString().contains("nextCursor"), result.content.toString())
    }

    @Test
    fun aMissingQueryComesBackAsAnActionableToolError() = runBlocking {
        val result = call(buildJsonObject { put("scope", JsonPrimitive("xml")) })

        assertEquals(true, result.isError)
        assertTrue(result.content.toString().contains("query"), result.content.toString())
    }
}
