package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.TextEncoding
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The properties a reader has to have and used to reach through `get_object_xml` for, one call per
 * object: what a window's modality is, which window a canvas is on, and what an item's property
 * class makes it. Surfaced through the tools that already answer for those objects rather than
 * through new ones.
 */
class ItemAndLayoutSemanticsTest {

    private val temp: Path = Files.createTempDirectory("semantics-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val pickerKey = ModuleKey.of("picker", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        binaryConversion = false,
    )

    init {
        val resource = javaClass.getResourceAsStream("/fixtures/picker_fmb.xml")!!
        resource.use { Files.copy(it, formsDir.resolve("picker_fmb.xml")) }
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun theOverviewStaysNamesOnlyUntilDetailIsAskedFor() = runTest {
        service.fetchModule(pickerKey)

        val concise = service.overview(pickerKey)
        assertEquals(listOf("BAR_LIST", "CV_LIST"), concise.canvases)
        assertNull(concise.detail, "the default overview stays a set of name lists")

        val detailed = service.overview(pickerKey, detailed = true)
        // The name lists keep their shape; the objects arrive beside them.
        assertEquals(concise.canvases, detailed.canvases)
        val detail = assertNotNull(detailed.detail)

        val window = detail.windows.single { it.name == "WIN_PICKER" }
        assertEquals(true, window.modal)
        assertEquals("BAR_LIST", window.horizontalToolbarCanvasName)
        // ...which answers "which window hosts this canvas?" in the same call.
        assertEquals("WIN_PICKER", detail.canvases.single { it.name == "CV_LIST" }.windowName)
        assertEquals(true, detail.canvases.single { it.name == "CV_LIST" }.raiseOnEnter)
    }

    @Test
    fun getBlockKeepsEveryFieldAReaderCannotInferAndDropsTheRest() = runTest {
        service.fetchModule(pickerKey)

        val concise = service.getBlock(pickerKey, "CUSTOMERS").block.items.associateBy { it.name }
        val name = concise.getValue("NAME")
        // Kept: what an item is, what it is called, what it does, and where it comes from.
        assertEquals("Text Item", name.itemType)
        assertEquals("PC_TEXT", name.propertyClass)
        assertEquals("Name", name.prompt)
        // Dropped: descriptive detail, recoverable with one argument.
        assertNull(name.dataType)
        assertNull(name.columnName)
        assertNull(name.canvasName)

        val detailed = service.getBlock(pickerKey, "CUSTOMERS", detailed = true).block.items
            .associateBy { it.name }
        assertEquals("Char", detailed.getValue("NAME").dataType)
        assertEquals("NAME", detailed.getValue("NAME").columnName)
        assertEquals("LOV_REFS", detailed.getValue("REF").lovName)
        assertEquals(true, detailed.getValue("REF").required)
    }

    /**
     * Concise trims bytes, never meaning: an item's subclassing pointer is the one field whose
     * absence would be read as a fact about the item, so it survives at every verbosity.
     */
    @Test
    fun theSubclassingPointerSurvivesConciseVerbosity() = runTest {
        service.fetchModule(pickerKey)

        val concise = service.getBlock(pickerKey, "BAR_LIST").block.items.single { it.name == "SELECT" }
        assertEquals("BAR", assertNotNull(concise.inherited).ownerPath)
        assertEquals(listOf("WHEN-BUTTON-PRESSED"), concise.triggerNames)
    }

    @Test
    fun aRecoveredBodyIsServedWithTheFlagThatSaysSo() = runTest {
        service.fetchModule(pickerKey)

        val recovered = service.getTrigger(pickerKey, "WHEN-NEW-RECORD-INSTANCE", block = "ORPHAN", item = null)
        assertEquals(TextEncoding.RECOVERED, recovered.textEncoding)
        assertEquals("BEGIN\n\t:ORPHAN.FLAG := 'N';\nEND;", recovered.text)

        // The recovery is what makes the line range mean anything: read_source over the reported
        // location returns the same three lines, not one long one.
        val at = assertNotNull(recovered.source)
        assertEquals(3, at.endLine)
        assertEquals(recovered.text, service.readSource(pickerKey, at.uri, at.startLine, at.endLine).text)

        val untouched = service.getTrigger(pickerKey, "KEY-HELP", block = "CUSTOMERS", item = null)
        assertEquals(TextEncoding.ORIGINAL, untouched.textEncoding)
    }

    @Test
    fun aRecoveredBodyIsSearchableByLine() = runTest {
        service.fetchModule(pickerKey)

        val hits = service.searchSource(pickerKey, "ORPHAN.FLAG", regex = false, scope = "plsql", maxResults = 5)

        // Before recovery the whole body was line 1; the hit now lands on the line that matches.
        assertEquals(2, hits.hits.single().line)
        assertTrue(hits.hits.single().snippet.contains(":ORPHAN.FLAG"))
    }
}
