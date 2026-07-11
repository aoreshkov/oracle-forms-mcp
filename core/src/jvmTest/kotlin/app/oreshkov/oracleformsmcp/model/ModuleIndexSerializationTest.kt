package app.oreshkov.oracleformsmcp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class ModuleIndexSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleIndex() = ModuleIndex(
        key = ModuleKey.of("orders", ModuleType.FORM),
        formsVersion = "12.2.1.19.0",
        sourceFile = "C:/forms/ORDERS.fmb",
        fingerprint = ModuleFingerprint(1234, 1720000000000, "abc123"),
        convertedFile = "converted/orders_fmb.xml",
        parsedAt = Instant.fromEpochMilliseconds(1720000001000),
        blocks = listOf(
            BlockInfo(
                name = "ORDERS",
                queryDataSourceName = "ORDERS",
                items = listOf(ItemInfo(name = "ORDER_ID", itemType = "Text Item")),
                triggerNames = listOf("WHEN-VALIDATE-RECORD"),
                sourceRef = SourceRef("converted/orders_fmb.xml", 10, 42),
            ),
        ),
        triggers = listOf(
            TriggerInfo(
                name = "WHEN-VALIDATE-RECORD",
                level = TriggerLevel.BLOCK,
                blockName = "ORDERS",
                firstLine = "BEGIN",
                lineCount = 3,
                textRef = SourceRef("plsql/triggers/ORDERS.WHEN-VALIDATE-RECORD.sql", 1, 3),
            ),
        ),
        programUnits = listOf(
            ProgramUnitInfo(
                name = "CALC_TOTAL",
                unitType = ProgramUnitType.PROCEDURE,
                lineCount = 12,
                textRef = SourceRef("plsql/program-units/CALC_TOTAL.sql", 1, 12),
            ),
        ),
        objectRefs = listOf(
            ObjectRef("Block", "ORDERS", null, SourceRef("converted/orders_fmb.xml", 10, 42)),
        ),
    )

    @Test
    fun roundTripsThroughJson() {
        val index = sampleIndex()
        val decoded = json.decodeFromString<ModuleIndex>(json.encodeToString(ModuleIndex.serializer(), index))
        assertEquals(index, decoded)
    }

    @Test
    fun toleratesUnknownKeysAndMissingSections() {
        // Forward-compat: a future writer may add fields; missing sections default to empty.
        val minimal = """
            {
              "key": {"name": "UTILS", "type": "LIBRARY"},
              "sourceFile": "C:/forms/UTILS.pll",
              "fingerprint": {"sizeBytes": 1, "lastModifiedMillis": 2, "sha256": "x"},
              "convertedFile": "converted/utils.pld",
              "parsedAt": "2026-07-11T00:00:00Z",
              "someFutureField": true
            }
        """.trimIndent()
        val decoded = json.decodeFromString<ModuleIndex>(minimal)
        assertEquals(ModuleKey.of("UTILS", ModuleType.LIBRARY), decoded.key)
        assertEquals(emptyList(), decoded.blocks)
        assertNull(decoded.formsVersion)
    }
}
