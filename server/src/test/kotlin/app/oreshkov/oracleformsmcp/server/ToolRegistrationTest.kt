package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.server.tools.registerAnnotateElementTool
import app.oreshkov.oracleformsmcp.server.tools.registerFetchModuleTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetBlockTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetElementAnnotationsTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetModuleOverviewTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetObjectXmlTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetProgramUnitTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetTriggerTool
import app.oreshkov.oracleformsmcp.server.tools.registerListBlocksTool
import app.oreshkov.oracleformsmcp.server.tools.registerListModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerListProgramUnitsTool
import app.oreshkov.oracleformsmcp.server.tools.registerListTriggersTool
import app.oreshkov.oracleformsmcp.server.tools.registerReadSourceTool
import app.oreshkov.oracleformsmcp.server.tools.registerRelateElementsTool
import app.oreshkov.oracleformsmcp.server.tools.registerRemoveAnnotationTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchAnnotationsTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Every registered tool must carry the metadata the MCP spec encourages: a display title,
 * behavior annotations, and an output schema matching the DTO it serializes.
 */
class ToolRegistrationTest {

    private val readOnlyLocal = setOf(
        "list_modules", "get_module_overview", "list_blocks", "get_block", "list_triggers",
        "get_trigger", "list_program_units", "get_program_unit", "search_source", "search_modules",
        "read_source", "get_object_xml", "get_element_annotations", "search_annotations",
    )

    private val writeTools = setOf(
        "fetch_module", "annotate_element", "relate_elements", "remove_annotation",
    )

    private fun serverWithAllTools(): Server {
        val service = fakeService()
        return Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        ) {
            registerListModulesTool(service)
            registerFetchModuleTool(service)
            registerGetModuleOverviewTool(service)
            registerListBlocksTool(service)
            registerGetBlockTool(service)
            registerListTriggersTool(service)
            registerGetTriggerTool(service)
            registerListProgramUnitsTool(service)
            registerGetProgramUnitTool(service)
            registerSearchSourceTool(service)
            registerSearchModulesTool(service)
            registerReadSourceTool(service)
            registerGetObjectXmlTool(service)
            registerAnnotateElementTool(service)
            registerRelateElementsTool(service)
            registerGetElementAnnotationsTool(service)
            registerSearchAnnotationsTool(service)
            registerRemoveAnnotationTool(service)
        }
    }

    private fun tools(): Map<String, Tool> = serverWithAllTools().tools.mapValues { it.value.tool }

    @Test
    fun everyToolIsRegistered() {
        assertEquals(readOnlyLocal + writeTools, tools().keys)
    }

    @Test
    fun everyToolDeclaresTitleAnnotationsAndOutputSchema() {
        tools().forEach { (name, tool) ->
            assertNotNull(tool.title, "$name: missing title")
            assertNotNull(tool.annotations?.readOnlyHint, "$name: missing readOnlyHint")
            assertNotNull(tool.annotations?.openWorldHint, "$name: missing openWorldHint")
            assertNotNull(tool.outputSchema?.properties, "$name: missing outputSchema")
        }
    }

    @Test
    fun readToolsAreLocalReadOnly() {
        val tools = tools()
        readOnlyLocal.forEach { name ->
            val annotations = assertNotNull(tools.getValue(name).annotations)
            assertEquals(true, annotations.readOnlyHint, name)
            assertEquals(false, annotations.destructiveHint, name)
            assertEquals(true, annotations.idempotentHint, name)
            assertEquals(false, annotations.openWorldHint, name)
        }
    }

    /**
     * `anthropic/maxResultSizeChars` makes Claude Code's limit for a tool the character ceiling the
     * server enforces. It is declared exactly where the server caps the result below it: on an
     * uncapped tool the key would lower the client's limit rather than raise it.
     */
    @Test
    fun onlyCappedToolsDeclareTheirResultSizeCeiling() {
        val declared = tools().filterValues { it.meta?.get("anthropic/maxResultSizeChars") != null }
        assertEquals(setOf("read_source", "get_object_xml"), declared.keys)
        declared.forEach { (name, tool) ->
            assertEquals(
                MAX_RESULT_CHARS,
                tool.meta?.get("anthropic/maxResultSizeChars")?.jsonPrimitive?.int,
                name,
            )
        }
    }

    /**
     * Two facts a caller acts on *before* it has ever seen a result, which is why they belong in
     * the description and not only in the payload.
     *
     * The block-level DML properties are the first: `whereClause` and `orderByClause` restrict what
     * a block queries without a line of PL/SQL, and each has been the crux of a review finding —
     * found by reading the returned JSON, because the description covered only item-level
     * `effectiveDml`. The second is what a body's line numbers count from: they get copied into
     * documents, and `ORDERS.fmb:75` sends whoever opens the form to a line that does not exist.
     */
    @Test
    fun descriptionsNameTheBlockDmlPropertiesAndWhatBodyLinesCountFrom() {
        val getBlock = assertNotNull(tools().getValue("get_block").description)
        listOf("whereClause", "orderByClause", "keyMode", "lockMode").forEach { field ->
            assertTrue(field in getBlock, "get_block's description no longer names dml.$field")
        }
        listOf("get_trigger", "get_program_unit").forEach { name ->
            val description = assertNotNull(tools().getValue(name).description)
            assertTrue(
                "lines count from the first line of the body" in description,
                "$name's description no longer says what its line numbers count from",
            )
        }
    }

    @Test
    fun fetchModuleIsAnnotatedAsAdditiveIdempotentAndLocal() {
        val annotations = assertNotNull(tools().getValue("fetch_module").annotations)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun annotationWritesAreAdditiveAndLocal() {
        listOf("annotate_element", "relate_elements").forEach { name ->
            val annotations = assertNotNull(tools().getValue(name).annotations)
            assertEquals(false, annotations.readOnlyHint, name)
            assertEquals(false, annotations.destructiveHint, name)
            assertEquals(false, annotations.idempotentHint, name)
            assertEquals(false, annotations.openWorldHint, name)
        }
    }

    /**
     * Every tool is registered through `addCheckedTool`, so none silently drops an argument it does
     * not have — the failure that let `get_trigger(level=...)` be ignored while the error asked for
     * the very scope `level` had named. The check runs before the handler, so the fake service is
     * never reached; a tool registered with the SDK's bare `addTool` would reach it and not fail
     * this way.
     */
    @Test
    fun everyToolRejectsAnArgumentItDoesNotHave() = runBlocking {
        val server = serverWithAllTools()
        server.tools.forEach { (name, registered) ->
            val result = registered.handler(
                FakeClientConnection(),
                CallToolRequest(
                    CallToolRequestParams(name = name, arguments = buildJsonObject { put("notAnArgument", "x") }),
                ),
            )
            assertEquals(true, result.isError, "$name accepted an argument it does not declare")
            val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
            assertTrue("unknown argument 'notAnArgument'" in text, "$name: $text")
        }
    }

    /** An example call is the only form of `input_examples` MCP can carry; write tools need one. */
    @Test
    fun writeToolsCarryAnExampleCall() {
        listOf("annotate_element", "relate_elements", "remove_annotation").forEach { name ->
            val description = assertNotNull(tools().getValue(name).description)
            assertTrue("Example: $name(" in description, "$name's description has no example call")
        }
    }

    @Test
    fun removeAnnotationIsDestructiveIdempotentAndLocal() {
        val annotations = assertNotNull(tools().getValue("remove_annotation").annotations)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(true, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }
}
