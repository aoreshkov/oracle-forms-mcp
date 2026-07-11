package app.oreshkov.oracleformsmcp.server.tools

import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.dto.TriggerList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OutputSchemasTest {

    @Test
    fun moduleListSchemaHasPropertiesAndRequired() {
        val schema = outputSchemaOf<ModuleList>()
        val properties = assertNotNull(schema.properties)
        assertTrue("formsDir" in properties.keys)
        assertTrue("modules" in properties.keys)
        assertEquals(listOf("formsDir"), schema.required)
    }

    @Test
    fun nestedDtosBecomeDefsWithRefs() {
        val schema = outputSchemaOf<BlockDetail>()
        val defs = assertNotNull(schema.defs, "nested BlockInfo should produce \$defs")
        assertTrue("BlockInfo" in defs.keys)
        assertTrue("ItemInfo" in defs.keys)
        val blockProp = schema.properties?.get("block") as? JsonObject
        assertEquals(JsonPrimitive("#/\$defs/BlockInfo"), blockProp?.get("\$ref"))
    }

    @Test
    fun enumsAreEmittedAsStringEnums() {
        val schema = outputSchemaOf<TriggerList>()
        val defs = assertNotNull(schema.defs)
        val summary = assertNotNull(defs["TriggerSummary"] as? JsonObject)
        val level = ((summary["properties"] as JsonObject)["level"]) as JsonObject
        assertEquals(JsonPrimitive("string"), level["type"])
        val values = (level["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("FORM", "BLOCK", "ITEM", "MENU"), values)
    }
}
