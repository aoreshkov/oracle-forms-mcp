package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormsXmlParserTest {

    private val cacheDir: Path = Files.createTempDirectory("parser-test")
    private val parser = FormsModuleParser()

    @AfterTest
    fun cleanup() {
        cacheDir.toFile().deleteRecursively()
    }

    private fun parseFixture(fixture: String, key: ModuleKey): ModuleIndex {
        val converted = copyFixture(fixture, cacheDir.resolve("converted").createDirectories())
        return parser.parse(key, converted.toString(), cacheDir.toString())
    }

    private fun ordersIndex() = parseFixture("orders_fmb.xml", ModuleKey.of("orders", ModuleType.FORM))

    private fun dupesIndex() = parseFixture("dupes_fmb.xml", ModuleKey.of("dupes", ModuleType.FORM))

    private fun readRef(ref: SourceRef): String =
        cacheDir.resolve(ref.file).readLines().subList(ref.startLine - 1, ref.endLine).joinToString("\n")

    @Test
    fun indexesAllSections() {
        val index = ordersIndex()
        assertEquals("12.2.1.19.0", index.formsVersion)
        assertEquals(listOf("ORDERS", "CONTROL"), index.blocks.map { it.name })
        assertEquals(3, index.triggers.size)
        assertEquals(3, index.programUnits.size)
        assertEquals(listOf("UTILS"), index.attachedLibraries.map { it.name })
        assertEquals(listOf("LOV_CUSTOMERS"), index.lovs.map { it.name })
        assertEquals(listOf("RG_CUSTOMERS"), index.recordGroups.map { it.name })
        assertEquals(listOf("WINDOW_MAIN"), index.windows.map { it.name })
        assertEquals(listOf("CV_MAIN"), index.canvases.map { it.name })
        assertEquals(listOf("AL_CONFIRM"), index.alerts.map { it.name })
        assertEquals(listOf("P_ORDER_ID"), index.parameters.map { it.name })
        assertEquals(listOf("VA_HIGHLIGHT"), index.visualAttributes)
        assertEquals(listOf("PC_TEXT"), index.propertyClasses)
        assertEquals(listOf("ED_NOTES"), index.editors)
    }

    @Test
    fun blockDetailsAndItems() {
        val orders = ordersIndex().blocks.first { it.name == "ORDERS" }
        assertEquals("ORDERS", orders.queryDataSourceName)
        assertEquals(listOf("ORDER_ID", "CUSTOMER_NAME"), orders.items.map { it.name })
        assertEquals(listOf("WHEN-VALIDATE-RECORD"), orders.triggerNames)

        val orderId = orders.items.first()
        assertEquals("Text Item", orderId.itemType)
        assertEquals("ORDER_ID", orderId.columnName)
        assertEquals("CV_MAIN", orderId.canvasName)
        assertEquals(listOf("WHEN-VALIDATE-ITEM"), orderId.triggerNames)

        val control = ordersIndex().blocks.first { it.name == "CONTROL" }
        assertEquals(null, control.queryDataSourceName)
        assertEquals(listOf("BTN_SAVE"), control.items.map { it.name })
    }

    @Test
    fun classifiesTriggerLevels() {
        val triggers = ordersIndex().triggers
        val byName = triggers.associateBy { it.name }

        val itemTrigger = byName.getValue("WHEN-VALIDATE-ITEM")
        assertEquals(TriggerLevel.ITEM, itemTrigger.level)
        assertEquals("ORDERS", itemTrigger.blockName)
        assertEquals("ORDER_ID", itemTrigger.itemName)

        val blockTrigger = byName.getValue("WHEN-VALIDATE-RECORD")
        assertEquals(TriggerLevel.BLOCK, blockTrigger.level)
        assertEquals("ORDERS", blockTrigger.blockName)

        assertEquals(TriggerLevel.FORM, byName.getValue("KEY-COMMIT").level)
    }

    @Test
    fun decodesTriggerTextIntoSidecarWithRealNewlines() {
        val trigger = ordersIndex().triggers.first { it.name == "WHEN-VALIDATE-ITEM" }
        val text = readRef(assertNotNull(trigger.textRef))
        assertEquals(
            "IF :ORDERS.ORDER_ID IS NULL THEN\n  RAISE FORM_TRIGGER_FAILURE;\nEND IF;",
            text,
        )
        assertEquals("IF :ORDERS.ORDER_ID IS NULL THEN", trigger.firstLine)
        assertEquals(3, trigger.lineCount)
    }

    @Test
    fun programUnitsWithSpecBodyDisambiguation() {
        val units = ordersIndex().programUnits
        assertEquals(
            listOf(
                "CALC_TOTAL" to ProgramUnitType.PROCEDURE,
                "PKG_ORDERS" to ProgramUnitType.PACKAGE_SPEC,
                "PKG_ORDERS" to ProgramUnitType.PACKAGE_BODY,
            ),
            units.map { it.name to it.unitType },
        )
        // Decoded entities: &quot; became a real double quote in the sidecar.
        val calcTotal = readRef(assertNotNull(units.first().textRef))
        assertTrue(calcTotal.contains("-- \"total\" calculation"))
    }

    @Test
    fun objectRefSlicesReparseAsXml() {
        val index = ordersIndex()
        val blockRef = index.objectRefs.first { it.objectType == "Block" && it.name == "ORDERS" }
        val slice = readRef(blockRef.ref)
        assertTrue(slice.trimStart().startsWith("<Block"), "slice should start at the Block element:\n$slice")
        assertTrue(slice.trimEnd().endsWith("</Block>"), "slice should end with the Block close:\n$slice")
        assertTrue(slice.contains("ORDER_ID"))

        val itemTriggerRef = index.objectRefs.first {
            it.objectType == "Trigger" && it.ownerPath == "ORDERS.ORDER_ID"
        }
        assertTrue(readRef(itemTriggerRef.ref).contains("WHEN-VALIDATE-ITEM"))
    }

    @Test
    fun unknownElementsAreSkippedNotFatal() {
        val index = ordersIndex()
        // The FutureUnknownElement contributed nothing but still got an ObjectRef (it is named).
        assertTrue(index.objectRefs.any { it.objectType == "FutureUnknownElement" && it.name == "MYSTERY" })
    }

    @Test
    fun parsesMenuModules() {
        val index = parseFixture("mainmenu_mmb.xml", ModuleKey.of("mainmenu", ModuleType.MENU))
        assertEquals(listOf("MAIN", "FILE_MENU"), index.menus.map { it.name })
        assertEquals(listOf("DO_EXIT"), index.programUnits.map { it.name })

        val exit = index.menus.last().items.single()
        assertEquals("EXIT", exit.name)
        assertEquals("Exit", exit.label)
        assertEquals("do_exit;", readRef(assertNotNull(exit.commandRef)))
    }

    @Test
    fun parsesObjectLibraries() {
        val index = parseFixture("objects_olb.xml", ModuleKey.of("objects", ModuleType.OBJECT_LIBRARY))
        val tab = index.objectLibraryTabs.single()
        assertEquals("TAB_STANDARD", tab.name)
        assertEquals(
            listOf("TEMPLATE_BLOCK" to "Block", "VA_STD" to "VisualAttribute"),
            tab.entries.map { it.name to it.objectType },
        )
        // Tab contents must not leak into the form-level sections.
        assertEquals(emptyList(), index.blocks)
        assertEquals(emptyList(), index.visualAttributes)
    }

    @Test
    fun sameTriggerNameAtThreeLevelsGetsThreeDistinctSidecars() {
        val triggers = dupesIndex().triggers.filter { it.name == "KEY-NEXT-ITEM" }
        assertEquals(3, triggers.size)
        assertEquals(3, triggers.mapNotNull { it.textRef?.file }.distinct().size)

        val byLevel = triggers.associateBy { it.level }
        assertEquals(setOf(TriggerLevel.FORM, TriggerLevel.BLOCK, TriggerLevel.ITEM), byLevel.keys)
        assertEquals("STOCK", byLevel.getValue(TriggerLevel.BLOCK).blockName)
        assertEquals("QTY", byLevel.getValue(TriggerLevel.ITEM).itemName)
        assertEquals(
            "-- form level: default navigation",
            readRef(assertNotNull(byLevel.getValue(TriggerLevel.FORM).textRef)),
        )
        assertEquals(
            "-- block level: next stock record",
            readRef(assertNotNull(byLevel.getValue(TriggerLevel.BLOCK).textRef)),
        )
        assertEquals(
            "-- item level: validate qty",
            readRef(assertNotNull(byLevel.getValue(TriggerLevel.ITEM).textRef)),
        )
    }

    @Test
    fun blockNamedFormDoesNotClobberFormLevelTriggerSidecar() {
        val commits = dupesIndex().triggers.filter { it.name == "KEY-COMMIT" }
        val blockTrigger = commits.single { it.level == TriggerLevel.BLOCK }
        val formTrigger = commits.single { it.level == TriggerLevel.FORM }
        assertNotEquals(
            assertNotNull(blockTrigger.textRef).file,
            assertNotNull(formTrigger.textRef).file,
        )
        assertEquals("-- block FORM commit", readRef(blockTrigger.textRef!!))
        assertEquals("-- form level commit", readRef(formTrigger.textRef!!))
    }

    @Test
    fun duplicateItemNamesAcrossBlocksAreBothIndexed() {
        val index = dupesIndex()
        assertEquals(
            listOf("STOCK", "AUDIT"),
            index.blocks.filter { block -> block.items.any { it.name == "ID" } }.map { it.name },
        )
        val idRefs = index.objectRefs.filter { it.objectType == "Item" && it.name == "ID" }
        assertEquals(setOf("STOCK", "AUDIT"), idRefs.map { it.ownerPath }.toSet())
    }

    @Test
    fun stampsRealFingerprintOfConvertedFile() {
        val index = ordersIndex()
        assertTrue(index.fingerprint.sizeBytes > 0)
        assertTrue(index.fingerprint.sha256.isNotBlank())
        assertTrue(cacheDir.resolve(index.convertedFile).readText().contains("FormModule"))
    }
}
