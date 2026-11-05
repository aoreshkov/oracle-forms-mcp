package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A server started without a forms directory (#128) starts, offers the tools it always offers, and
 * answers every call with an error that names the setting — never with an empty result, which an
 * agent would read as "there are no Forms modules here".
 */
class UnconfiguredServerTest {

    private val unconfigured = fakeService(formsDir = null)

    private fun callEveryTool(arguments: JsonObject) = runBlocking {
        serverWithAllTools(unconfigured).tools.mapValues { (name, registered) ->
            registered.handler(FakeClientConnection(), CallToolRequest(CallToolRequestParams(name, arguments)))
        }
    }

    @Test
    fun theToolListIsTheConfiguredServersOwn() {
        val configured = serverWithAllTools().tools.mapValues { it.value.tool }
        assertEquals(configured, serverWithAllTools(unconfigured).tools.mapValues { it.value.tool })
    }

    @Test
    fun everyToolAnswersWithTheSettingToMake() {
        // A plausible call to each: the refusal comes before any argument is looked at.
        val results = callEveryTool(buildJsonObject { put("module", "ORDERS.fmb") })
        results.forEach { (name, result) ->
            assertEquals(true, result.isError, "$name answered without a forms directory")
            val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
            assertEquals(FORMS_DIR_NOT_CONFIGURED, text, name)
        }
    }

    /** Even ahead of an argument error: fixing the arguments first would only meet this error next. */
    @Test
    fun theRefusalComesBeforeTheArgumentCheck() {
        callEveryTool(buildJsonObject { put("notAnArgument", "x") }).forEach { (name, result) ->
            val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
            assertEquals(FORMS_DIR_NOT_CONFIGURED, text, name)
        }
    }

    @Test
    fun theMessageNamesEverySpellingOfTheSettingAndSaysItIsNotAnEmptyDirectory() {
        listOf(ENV_FORMS_DIR, "--forms-dir", "forms_dir", "restart", "not an empty directory").forEach {
            assertTrue(it in FORMS_DIR_NOT_CONFIGURED, "the message no longer mentions '$it'")
        }
    }

    /** Resources and the prompt reach the service directly, without the tool layer's refusal. */
    @Test
    fun theServiceRefusesWhatTheResourcesAndThePromptCall() = runBlocking {
        val key = ModuleKey("ORDERS", ModuleType.FORM)
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.index(key) }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.resolveModule("ORDERS") }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.resolveModule("ORDERS.fmb") }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.moduleIndexResource(key) }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.moduleAnnotations(key) }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.listModules() }
        assertFailsWith<FormsDirNotConfiguredException> { unconfigured.prefetch() }
        Unit
    }

    @Test
    fun theFactoryBuildsAServerWithoutOne() {
        val root = Files.createTempDirectory("unconfigured")
        try {
            McpServerFactory.create(
                ServerConfig(formsDir = null, cacheDir = root.resolve("cache"), annotationsDir = root.resolve("notes")),
            ).use { handle ->
                assertEquals(FORMS_DIR_NOT_CONFIGURED, handle.service.unavailableReason)
                val listModules = handle.server.tools.getValue("list_modules")
                val result = runBlocking {
                    listModules.handler(FakeClientConnection(), CallToolRequest(CallToolRequestParams("list_modules")))
                }
                assertEquals(true, result.isError, "list_modules answered without a forms directory")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theInstructionsLeadWithItOnlyWhenThereIsNone() {
        assertEquals(SERVER_INSTRUCTIONS, serverInstructions(formsDirConfigured = true))
        val unconfigured = serverInstructions(formsDirConfigured = false)
        assertTrue(unconfigured.startsWith(FORMS_DIR_NOT_CONFIGURED))
        assertTrue(unconfigured.endsWith(SERVER_INSTRUCTIONS), "the rest of the guidance still follows it")
    }
}
