package app.oreshkov.oracleformsmcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCPB manifest declares `tools_generated: false`, so its `tools` list is what a desktop install
 * dialog shows — written by hand, and nothing else notices when it falls behind the server. It
 * already had: `read_source` and `search_modules` shipped missing from it.
 */
class McpbManifestTest {

    @Test
    fun manifestListsExactlyTheToolsTheServerRegisters() {
        val text = assertNotNull(
            javaClass.getResource("/mcpb/manifest.template.json"),
            "the build copies server/mcpb/manifest.template.json into the test resources",
        ).readText()
        val manifest = Json.parseToJsonElement(text).jsonObject
        val listed = manifest.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(listed.size, listed.toSet().size, "the manifest lists a tool twice: $listed")
        val registered = serverWithAllTools().tools.keys
        assertEquals(
            registered,
            listed.toSet(),
            "missing from the manifest: ${registered - listed.toSet()}; " +
                "listed but not registered: ${listed.toSet() - registered}",
        )
    }
}
