package app.oreshkov.oracleformsmcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class ToolSupportTest {

    /**
     * The 2026-07-28 spec names two valid `inputSchema` forms for a parameterless tool and
     * recommends `{"type":"object","additionalProperties":false}`, which the SDK's [ToolSchema]
     * cannot express. `{"type":"object"}` is the other valid form and what the helper must emit —
     * notably *not* an empty `properties` object, which constrains nothing yet claims the tool has
     * properties. Revisit when the SDK can carry `additionalProperties`.
     */
    @Test
    fun theNoArgumentSchemaIsTheSpecsBareObjectForm() {
        assertEquals("""{"type":"object"}""", Json.encodeToString(ToolSchema.serializer(), emptySchema()))
    }
}
