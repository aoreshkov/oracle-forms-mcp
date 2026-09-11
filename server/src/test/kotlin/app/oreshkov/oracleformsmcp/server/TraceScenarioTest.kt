package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.tools.registerFetchModuleTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetBlockTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetModuleOverviewTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetObjectXmlTool
import app.oreshkov.oracleformsmcp.server.tools.registerGetTriggerTool
import app.oreshkov.oracleformsmcp.server.tools.registerListModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerListTriggersTool
import app.oreshkov.oracleformsmcp.server.tools.registerReadSourceTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchModulesTool
import app.oreshkov.oracleformsmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
 * The trace this server exists for, end to end, through tool calls only.
 *
 * The question is the one a Forms reader actually asks: *how does the value chosen in that modal
 * get into the field on the form?* Answering it crosses every gap this project has had to close —
 * a modal window, a toolbar block subclassed from another form, a trigger whose body lives there,
 * a `Do_Key` that fires a third trigger, and a `:GLOBAL` that carries the value home.
 *
 * Two properties are under test, and neither is about a single tool:
 *
 * 1. **Every step is reachable by tool calls alone.** Each call's arguments come out of the
 *    previous call's result — the called form's name is read from the PL/SQL, the parent module and
 *    `ownerPath` from the inheritance pointer, the toolbar canvas from the window object, the line
 *    range from the hit. Nothing here opens a file, greps a directory, or hardcodes a fact that a
 *    result did not state. A step that regressed into needing raw file access could not be written
 *    this way at all.
 * 2. **Every response fits a client's budget.** Each result's size is recorded and asserted against
 *    [RESPONSE_BUDGET_CHARS], so a field added to a DTO later cannot quietly re-create the day
 *    `list_modules` returned six times what any client would accept.
 */
class TraceScenarioTest {

    private companion object {
        /**
         * Claude Code rejects an MCP tool result over 25,000 tokens and warns at 10,000; ~4 chars
         * per token puts the hard limit near 100,000 characters. Every step of the trace is held
         * under it, counting the JSON text *and* the `structuredContent` copy beside it, which is
         * what a client actually receives.
         */
        const val RESPONSE_BUDGET_CHARS = 100_000
    }

    private val temp: Path = Files.createTempDirectory("trace-scenario")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        binaryConversion = false,
    )

    /** The tools a client would have; the trace may use any of them and nothing else. */
    private val server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    ) {
        registerListModulesTool(service)
        registerFetchModuleTool(service)
        registerGetModuleOverviewTool(service)
        registerGetBlockTool(service)
        registerListTriggersTool(service)
        registerGetTriggerTool(service)
        registerSearchSourceTool(service)
        registerSearchModulesTool(service)
        registerReadSourceTool(service)
        registerGetObjectXmlTool(service)
    }

    private val connection = FakeClientConnection()

    /** Every call the trace made, in order: the tool, and the size of what it returned. */
    private val steps = mutableListOf<Pair<String, Int>>()

    init {
        listOf("entry_fmb.xml", "picker_fmb.xml", "toolbar_fmb.xml").forEach { name ->
            val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
            resource.use { Files.copy(it, formsDir.resolve(name)) }
        }
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    /**
     * Calls [tool] the way a client does and records what came back. A tool error fails the trace
     * here rather than three steps later: an `isError` result is exactly the point at which a
     * reader would have given up on the tools and reached for the files.
     */
    private suspend fun call(tool: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val result = server.tools.getValue(tool).handler(
            connection,
            CallToolRequest(CallToolRequestParams(name = tool, arguments = arguments)),
        )
        assertFalse(result.isError == true, "$tool failed the trace: ${result.content}")
        // What a client receives: the JSON text block plus the structuredContent copy of it.
        val size = result.content.sumOf { (it as? TextContent)?.text?.length ?: 0 } +
            (result.structuredContent?.toString()?.length ?: 0)
        steps += tool to size
        return assertNotNull(result.structuredContent, "$tool returned no structuredContent")
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            put(
                key,
                when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is Int -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                },
            )
        }
    }

    private fun JsonObject.str(field: String): String = assertNotNull(this[field], "missing '$field' in $this")
        .jsonPrimitive.content

    @Test
    fun theWholeTraceIsReachableThroughToolCallsAlone() = runBlocking {
        // 1. Discovery. The forms directory is a list, not a filesystem to explore.
        val modules = call("list_modules").getValue("modules").jsonArray.map { it.jsonObject.str("name") }
        assertTrue(modules.containsAll(listOf("ENTRY", "PICKER", "TOOLBAR")), modules.toString())

        // 2. The form the question starts on, converted and indexed.
        val entry = "ENTRY.fmb"
        val fetched = call("fetch_module", args("module" to entry))
        assertEquals("ENTRY", fetched.getValue("module").jsonObject.str("name"))
        assertTrue(fetched.str("convertedUri").startsWith("oracleforms://$entry/"), fetched.str("convertedUri"))

        // 3. The field's trigger — and, read out of its PL/SQL, the form it opens.
        val keyHelp = call(
            "get_trigger",
            args("module" to entry, "name" to "KEY-HELP", "block" to "MISSIONS", "item" to "AGENT_REF"),
        )
        val body = keyHelp.str("text")
        assertEquals(null, keyHelp["bodySource"], "an own body is the default and carries no qualifier")
        val calledForm = Regex("""call_form\('([^']+)'""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        assertEquals("picker", assertNotNull(calledForm, "the trigger body must name the form it opens: $body"))

        // 4. ...which is fetchable under exactly the name the PL/SQL used.
        val picker = call("fetch_module", args("module" to calledForm)).getValue("module").jsonObject
        assertEquals("PICKER", picker.str("name"))

        // 5. The window is modal — the fact that makes this a dialog — and it names its toolbar
        //    canvas, both in one overview call instead of one get_object_xml per object.
        val detail = call("get_module_overview", args("module" to "PICKER.fmb", "verbosity" to "detailed"))
            .getValue("detail").jsonObject
        val window = detail.getValue("windows").jsonArray.map { it.jsonObject }.single { it.str("name") == "WIN_LIST" }
        assertEquals("true", window.str("modal"))
        val toolbarCanvas = window.str("horizontalToolbarCanvasName")
        assertEquals("BAR_LIST", toolbarCanvas)

        // 6. The toolbar's block (named after its canvas, as Forms shops name them) is subclassed
        //    from another form, and says so rather than looking like a local block.
        val block = call("get_block", args("module" to "PICKER.fmb", "block" to toolbarCanvas)).getValue("block")
            .jsonObject
        val blockParent = block.getValue("inherited").jsonObject
        assertEquals("TOOLBAR", blockParent.str("module"))
        val selectItem = block.getValue("items").jsonArray.map { it.jsonObject }.single { it.str("name") == "SELECT" }
        assertEquals("BAR", selectItem.getValue("inherited").jsonObject.str("ownerPath"))

        // 7. The button's trigger is empty *here* — and the result says so instead of asserting
        //    that the button does nothing, and names the two calls that reach the real body.
        val cold = call(
            "get_trigger",
            args("module" to "PICKER.fmb", "name" to "WHEN-BUTTON-PRESSED", "block" to toolbarCanvas, "item" to "SELECT"),
        )
        assertEquals("", cold.str("text"))
        assertEquals("INHERITED", cold.str("bodySource"))
        val parent = cold.getValue("inherited").jsonObject
        val parentModule = "${parent.str("module")}.fmb"
        val parentPath = parent.str("ownerPath")
        assertTrue(cold.str("hint").contains("fetch_module(module=\"$parentModule\")"), cold.str("hint"))

        // 8. Fetch the parent the hint named, then let the same call follow the pointer.
        call("fetch_module", args("module" to parentModule))
        val resolved = call(
            "get_trigger",
            args(
                "module" to "PICKER.fmb",
                "name" to "WHEN-BUTTON-PRESSED",
                "block" to toolbarCanvas,
                "item" to "SELECT",
                "resolve" to true,
            ),
        )
        assertEquals("RESOLVED", resolved.str("bodySource"))
        assertEquals("TOOLBAR", resolved.getValue("resolvedFrom").jsonObject.str("name"))
        // The other route the hint spelled out — asking the parent directly, in its own
        // vocabulary — must reach the very same body.
        val direct = call(
            "get_trigger",
            args("module" to parentModule, "name" to "WHEN-BUTTON-PRESSED", "ownerPath" to parentPath),
        )
        assertEquals(resolved.str("text"), direct.str("text"))
        val doKey = Regex("""Do_Key\('([^']+)'""", RegexOption.IGNORE_CASE).find(resolved.str("text"))
            ?.groupValues?.get(1)
        assertEquals("HELP", assertNotNull(doKey, "the inherited body must be the real one: ${resolved.str("text")}"))

        // 9. `Do_Key('HELP')` fires a KEY-HELP trigger — which one is a listing, not a guess.
        val keyHelpLevels = call("list_triggers", args("module" to "PICKER.fmb"))
            .getValue("triggers").jsonArray.map { it.jsonObject }.filter { it.str("name") == "KEY-$doKey" }
        val onBlock = keyHelpLevels.single()
        assertEquals("BLOCK", onBlock.str("level"))

        // 10. ...and that trigger is where the picked value leaves the modal.
        val writer = call(
            "get_trigger",
            args("module" to "PICKER.fmb", "name" to "KEY-$doKey", "block" to onBlock.str("block")),
        )
        val global = Regex(""":GLOBAL\.(\w+)""", RegexOption.IGNORE_CASE).findAll(writer.str("text"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("picked_name", "picked_ref"), global)

        // 11. The other end of that global: one cross-module search, no per-module fetching.
        val hits = call("search_modules", args("query" to ":GLOBAL.picked_ref"))
            .getValue("hits").jsonArray.map { it.jsonObject }
        val homeward = hits.single { it.str("moduleSpec") == entry && it.str("snippet").contains("AGENT_REF") }
        assertTrue(homeward.str("snippet").contains(":GLOBAL.picked_ref"), homeward.str("snippet"))

        // 12. And the hit opens: the line the search reported can be read in context, by the uri
        //     the hit carried, without knowing where the cache put the file.
        val source = call(
            "read_source",
            args("module" to entry, "uri" to homeward.str("uri"), "startLine" to 1, "maxLines" to 20),
        )
        assertTrue(source.str("text").contains(":MISSIONS.AGENT_REF := :GLOBAL.picked_ref"), source.str("text"))

        // The trace is closed: modal → subclassed toolbar → inherited trigger → Do_Key →
        // block-level KEY-HELP → :GLOBAL → the field on the calling form.
        assertEquals(
            listOf(
                "list_modules", "fetch_module", "get_trigger", "fetch_module", "get_module_overview",
                "get_block", "get_trigger", "fetch_module", "get_trigger", "get_trigger",
                "list_triggers", "get_trigger", "search_modules", "read_source",
            ),
            steps.map { it.first },
            "the trace must be exactly these tool calls — a new step means a new escape hatch",
        )
    }

    /**
     * Item 23 of the plan the traces came from: the sizes are not incidental. A client rejects an
     * oversized tool result outright, so the budget is asserted per step — the failure message
     * names the step that grew.
     */
    @Test
    fun everyStepOfTheTraceFitsAClientsToolOutputBudget() = runBlocking {
        theWholeTraceIsReachableThroughToolCallsAlone()

        val oversized = steps.filter { (_, size) -> size >= RESPONSE_BUDGET_CHARS }
        assertEquals(
            emptyList(),
            oversized,
            "these responses exceed $RESPONSE_BUDGET_CHARS chars: ${steps.joinToString { "${it.first}=${it.second}" }}",
        )
    }
}
