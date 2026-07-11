package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.server.tools.registerFetchModuleTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetBlockTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetModuleOverviewTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetObjectXmlTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetProgramUnitTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetTriggerTool
import app.oreshkov.oracleformsmcp.server.tools.registerListBlocksTool
import app.oreshkov.oracleformsmcp.server.tools.registerListModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerListProgramUnitsTool
import app.oreshkov.oracleformsmcp.server.tools.registerListTriggersTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every registered tool must carry the metadata the MCP spec encourages: a display title,
 * behavior annotations, and an output schema matching the DTO it serializes.
 */
class ToolRegistrationTest {

    private val readOnlyLocal = setOf(
        "list_modules", "get_module_overview", "list_blocks", "get_block", "list_triggers",
        "get_trigger", "list_program_units", "get_program_unit", "search_source", "get_object_xml",
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
            registerGetObjectXmlTool(service)
        }
    }

    private fun tools(): Map<String, Tool> = serverWithAllTools().tools.mapValues { it.value.tool }

    @Test
    fun allElevenToolsAreRegistered() {
        assertEquals(readOnlyLocal + "fetch_module", tools().keys)
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
            assertEquals(false, annotations.openWorldHint, name)
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
}
