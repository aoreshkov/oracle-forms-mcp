package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TextEncoding
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun pickerIndex() = parseFixture("picker_fmb.xml", ModuleKey.of("picker", ModuleType.FORM))

    /** Slices a ref the way FormsService.readRef does — an empty sidecar has no lines to take. */
    private fun readRef(ref: SourceRef): String {
        val lines = cacheDir.resolve(ref.file).readLines()
        val end = ref.endLine.coerceAtMost(lines.size)
        return if (ref.startLine > end) "" else lines.subList(ref.startLine - 1, end).joinToString("\n")
    }

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
    fun subclassedBlockCarriesItsCrossModulePointer() {
        val block = pickerIndex().blocks.single { it.name == "BAR_LIST" }
        val ref = assertNotNull(block.inherited)
        assertEquals("TOOLBAR", ref.module)
        assertEquals("toolbar.fmb", ref.file)
        assertEquals("BAR", ref.name) // its name over there, not BAR_LIST
        assertEquals(null, ref.ownerPath)
        assertEquals("3", ref.parentType)
        assertFalse(ref.subObject)
    }

    @Test
    fun subclassedChildrenInheritThePointerWithTheParentsPath() {
        val block = pickerIndex().blocks.single { it.name == "BAR_LIST" }
        val item = assertNotNull(block.items.single { it.name == "SELECT" }.inherited)
        assertEquals("TOOLBAR", item.module)
        assertEquals("SELECT", item.name)
        assertEquals("BAR", item.ownerPath) // BAR_LIST.SELECT here is BAR.SELECT there
        assertTrue(item.subObject)

        val trigger = pickerIndex().triggers.single {
            it.name == "WHEN-BUTTON-PRESSED" && it.itemName == "SELECT"
        }
        val ref = assertNotNull(trigger.inherited)
        assertEquals("TOOLBAR", ref.module)
        assertEquals("toolbar.fmb", ref.file)
        assertEquals("BAR.SELECT", ref.ownerPath)
        assertEquals("", readRef(assertNotNull(trigger.textRef)))
    }

    @Test
    fun aLocallyOverriddenBodyKeepsItsOwnText() {
        // Subclassed, but this module supplies the code: the pointer stands, the body is real.
        val trigger = pickerIndex().triggers.single {
            it.name == "WHEN-BUTTON-PRESSED" && it.itemName == "CANCEL"
        }
        assertNotNull(trigger.inherited)
        assertTrue(readRef(assertNotNull(trigger.textRef)).contains("Exit_Form(NO_VALIDATE)"))
    }

    @Test
    fun aPropertyClassInThisSameModuleIsNotAnInheritancePointer() {
        // `ParentName` also carries the property class, with ParentModule naming *this* module.
        // Nothing is hidden there — the class is indexed alongside — and a real form has dozens,
        // so treating it as inheritance would be both wrong and the bulk of get_block's payload.
        val item = pickerIndex().blocks.single { it.name == "CUSTOMERS" }.items.single { it.name == "NAME" }
        assertEquals(null, item.inherited)
    }

    @Test
    fun objectGroupMembersKeepTheirOwnNameAndRecordTheGroup() {
        val index = pickerIndex()
        val block = index.blocks.single { it.name == "MSG" }
        val ref = assertNotNull(block.inherited)
        assertEquals("SHARED", ref.module)
        assertEquals("shared.olb", ref.file)
        assertEquals("MSG", ref.name) // its own name — ParentName ("STD") is the group
        assertEquals("STD", ref.objectGroup)
        assertEquals(null, ref.ownerPath)

        // Members inherit the group and hang off the object's own path, not the group's name.
        val item = assertNotNull(block.items.single { it.name == "SEVERITY" }.inherited)
        assertEquals("MSG", item.ownerPath)
        assertEquals("SEVERITY", item.name)
        assertEquals("STD", item.objectGroup)

        // Top-level triggers and program units come in the same way, and are empty here.
        val trigger = index.triggers.single { it.name == "ON-ERROR" }
        assertEquals("ON-ERROR", assertNotNull(trigger.inherited).name)
        assertEquals("STD", trigger.inherited.objectGroup)
        assertEquals("", readRef(assertNotNull(trigger.textRef)))
        val unit = index.programUnits.single { it.name == "SHOW_INFO" }
        assertEquals("SHOW_INFO", assertNotNull(unit.inherited).name)
    }

    @Test
    fun subclassedObjectWithNoPointerAboveItIsStillFlagged() {
        val item = pickerIndex().blocks.single { it.name == "ORPHAN" }.items.single()
        val ref = assertNotNull(item.inherited)
        assertTrue(ref.subObject)
        assertEquals(null, ref.module)
        assertEquals(null, ref.ownerPath)
    }

    @Test
    fun objectRefsCarryTheInheritanceOfTheLevelTheyAddress() {
        // The raw Item fragment holds only SubclassSubObject="true" — the parent pointer is on the
        // enclosing Block, which is exactly why the ref resolves it down to this level.
        val index = pickerIndex()
        val item = index.objectRefs.single {
            it.objectType == "Item" && it.name == "SELECT" && it.ownerPath == "BAR_LIST"
        }
        assertEquals("BAR", assertNotNull(item.inherited).ownerPath)
        val trigger = index.objectRefs.single {
            it.objectType == "Trigger" && it.ownerPath == "BAR_LIST.SELECT"
        }
        assertEquals("BAR.SELECT", assertNotNull(trigger.inherited).ownerPath)
    }

    @Test
    fun subclassedProgramUnitCarriesItsPointer() {
        val unit = pickerIndex().programUnits.single { it.name == "BAR_REFRESH" }
        val ref = assertNotNull(unit.inherited)
        assertEquals("TOOLBAR", ref.module)
        assertEquals("BAR_REFRESH", ref.name)
        assertEquals("", readRef(assertNotNull(unit.textRef)))
    }

    @Test
    fun ordinaryObjectsCarryNoInheritance() {
        val index = ordersIndex()
        assertTrue(index.blocks.all { it.inherited == null && it.items.all { i -> i.inherited == null } })
        assertTrue(index.triggers.all { it.inherited == null })
        assertTrue(index.objectRefs.all { it.inherited == null })
    }

    @Test
    fun itemsCarryThePropertiesThatGiveThemTheirRole() {
        val items = pickerIndex().blocks.single { it.name == "CUSTOMERS" }.items.associateBy { it.name }

        // A property class the module actually declares.
        assertEquals("PC_TEXT", items.getValue("NAME").propertyClass)
        // A same-module ParentName that is *not* a declared property class is not reported as one:
        // ParentType would say so directly, but its numbering is version-dependent and undocumented.
        assertEquals(null, items.getValue("REF").propertyClass)

        val ref = items.getValue("REF")
        assertEquals(true, ref.visible)
        assertEquals(true, ref.required)
        assertEquals("LOV_REFS", ref.lovName)

        // Forms writes a property only where it is overridden, so absence means "the default" —
        // reporting false here would invent a fact.
        assertEquals(null, items.getValue("NAME").visible)
        assertEquals(null, items.getValue("NAME").required)
        assertEquals(null, items.getValue("NAME").lovName)
    }

    @Test
    fun windowsAndCanvasesCarryTheirLayoutProperties() {
        val index = pickerIndex()
        val window = index.windows.single { it.name == "WIN_PICKER" }
        assertEquals(true, window.modal)
        assertEquals(600, window.width)
        assertEquals(420, window.height)
        assertEquals("BAR_LIST", window.horizontalToolbarCanvasName)
        assertEquals(null, window.verticalToolbarCanvasName)

        val canvas = index.canvases.single { it.name == "CV_LIST" }
        assertEquals("WIN_PICKER", canvas.windowName)
        assertEquals(true, canvas.raiseOnEnter)
        assertEquals(600, canvas.width)
        assertEquals(580, canvas.viewportWidth)
        assertEquals(360, canvas.viewportHeight)

        // A window without those properties reports nothing rather than guessing at defaults.
        val plain = ordersIndex().windows.single()
        assertEquals(null, plain.modal)
        assertEquals(null, plain.width)
    }

    /**
     * A doubly-escaped file writes a newline as `&amp;#10;`, so the XML parser decodes it once and
     * the body arrives as one physical line holding the literal characters `&#10;`. Left alone it
     * makes `lineCount` 1 for a whole procedure, collapses every recorded range onto that line, and
     * leaves a line-oriented search with nothing to report.
     */
    @Test
    fun aDoublyEscapedBodyIsRecoveredAndSaysSo() {
        val trigger = pickerIndex().triggers.single { it.name == "WHEN-NEW-RECORD-INSTANCE" }

        assertEquals(TextEncoding.RECOVERED, trigger.textEncoding)
        assertEquals("BEGIN\n\t:ORPHAN.FLAG := 'N';\nEND;", readRef(assertNotNull(trigger.textRef)))
        assertEquals(3, trigger.lineCount, "the line count must describe the recovered text")
        assertEquals("BEGIN", trigger.firstLine)
    }

    @Test
    fun anOrdinaryBodyIsLeftExactlyAsItWas() {
        // Correctly escaped, so it already has real newlines and nothing is touched.
        val trigger = ordersIndex().triggers.single { it.name == "WHEN-VALIDATE-ITEM" }
        assertEquals(TextEncoding.ORIGINAL, trigger.textEncoding)
        assertTrue(pickerIndex().triggers.none { it.name == "KEY-HELP" && it.textEncoding != TextEncoding.ORIGINAL })
    }

    @Test
    fun stampsRealFingerprintOfConvertedFile() {
        val index = ordersIndex()
        assertTrue(index.fingerprint.sizeBytes > 0)
        assertTrue(index.fingerprint.sha256.isNotBlank())
        assertTrue(cacheDir.resolve(index.convertedFile).readText().contains("FormModule"))
    }
}
