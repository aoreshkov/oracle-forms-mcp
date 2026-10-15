package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.dto.ModuleIndexDigest
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Resource reads are bounded like tool results, and a cut is stated inside the text.
 *
 * A resource read carries bytes and nothing else: no `truncated` beside it, no hint. A client
 * that trims an over-long read to fit leaves JSON or XML that looks complete, so every resource
 * here either fits or says, in what it returns, that it was cut and which call reads the rest.
 */
class BoundedResourcesTest {

    private val temp: Path = Files.createTempDirectory("bounded-resources-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val bigKey = ModuleKey.of("big", ModuleType.FORM)
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
    )

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    @Test
    fun anIndexTooLargeForOneReadIsServedAsADigestThatSaysSo() = runTest {
        BigModules.writeForm(formsDir)
        service.fetchModule(bigKey)

        val text = service.moduleIndexResource(bigKey)

        assertTrue(text.length <= MAX_RESULT_CHARS, "a resource read must fit: ${text.length}")
        val digest = toolJson.decodeFromString<ModuleIndexDigest>(text)
        assertTrue(digest.truncated)
        assertTrue(digest.indexChars > MAX_RESULT_CHARS, "says how large the index it did not send is")
        assertEquals(BigModules.BLOCKS, digest.blockCount)
        assertEquals(BigModules.BLOCKS * BigModules.ITEMS_PER_BLOCK, digest.itemCount)
        assertEquals(BigModules.WINDOWS, digest.windowCount)
        assertEquals(BigModules.PROGRAM_UNITS, digest.programUnitCount)
        val hint = assertNotNull(digest.hint)
        listOf("get_module_overview", "list_blocks", "list_program_units").forEach {
            assertTrue(hint.contains(it), "the digest names $it: $hint")
        }
    }

    @Test
    fun anOrdinaryIndexIsServedWhole() = runTest {
        copyFixture("orders_fmb.xml")
        service.fetchModule(ordersKey)

        val text = service.moduleIndexResource(ordersKey)

        assertEquals(service.index(ordersKey), toolJson.decodeFromString<ModuleIndex>(text))
    }

    @Test
    fun theAnnotationsResourceIsCutToFitAndCountsWhatItLeftOut() = runTest {
        copyFixture("orders_fmb.xml")
        service.fetchModule(ordersKey)
        repeat(200) { n ->
            service.annotate(ordersKey, ElementKind.MODULE, "ORDERS", null, AnnotationKind.NOTE, "note $n " + "x".repeat(500))
        }
        service.relate(
            ordersKey, ElementKind.BLOCK, "ORDERS", null, ElementKind.BLOCK, "ORDER_LINES", null,
            relType = "master-of", note = null,
        )

        val view = service.moduleAnnotations(ordersKey)

        assertTrue(toolJson.encodeToString(view).length <= MAX_RESULT_CHARS, "a resource read must fit")
        assertEquals(200, view.totalNotes)
        assertEquals(1, view.totalRelations)
        assertTrue(view.truncated)
        assertTrue(view.notes.isNotEmpty() && view.notes.size < 200, "cut, not emptied: ${view.notes.size}")
        assertTrue(assertNotNull(view.hint).contains("search_annotations"), view.hint)

        // The search tool serves the same rows and had the same unbounded shape.
        val search = service.searchAnnotations(ordersKey, text = null, kind = null, tag = null)
        assertTrue(toolJson.encodeToString(search).length <= MAX_RESULT_CHARS)
        assertTrue(search.truncated)
        assertEquals(200, search.totalNotes)
    }

    /**
     * A note's length is unbounded, so one note can be larger than a whole read. Stopping at it
     * would hide every note after it from both the resource and the search tool, whatever filter
     * is used; it is left out instead, and the hint names the call that serves it.
     */
    @Test
    fun oneNoteTooLongToServeDoesNotHideTheNotesAfterIt() = runTest {
        copyFixture("orders_fmb.xml")
        service.fetchModule(ordersKey)
        service.annotate(ordersKey, ElementKind.MODULE, "ORDERS", null, AnnotationKind.NOTE, "y".repeat(70_000))
        repeat(3) { service.annotate(ordersKey, ElementKind.MODULE, "ORDERS", null, AnnotationKind.NOTE, "short $it") }

        val view = service.moduleAnnotations(ordersKey)
        assertEquals(listOf("short 0", "short 1", "short 2"), view.notes.map { it.body })
        assertTrue(view.truncated)
        assertTrue(assertNotNull(view.hint).contains("get_element_annotations"), view.hint)

        val search = service.searchAnnotations(ordersKey, text = "short", kind = null, tag = null)
        assertEquals(3, search.notes.size)
        assertFalse(search.truncated)
        assertNull(search.hint)

        val all = service.searchAnnotations(ordersKey, text = null, kind = null, tag = null)
        assertTrue(all.truncated)
        assertTrue(assertNotNull(all.hint).contains("Showing 3 of 4 notes"), all.hint)
    }

    @Test
    fun aSmallAnnotationSetIsServedWholeWithoutAHint() = runTest {
        copyFixture("orders_fmb.xml")
        service.fetchModule(ordersKey)
        service.annotate(ordersKey, ElementKind.MODULE, "ORDERS", null, AnnotationKind.NOTE, "order entry")

        val view = service.moduleAnnotations(ordersKey)

        assertEquals(1, view.totalNotes)
        assertEquals(1, view.notes.size)
        assertFalse(view.truncated)
        assertNull(view.hint)
    }

    /**
     * A one-line converted file is the shape that broke this: the first and only line is cut
     * part-way, there is no next line, and the old note sent the reader to `startLine=2`.
     */
    @Test
    fun aResourceReadOfALineLongerThanOneReadSaysTheLineWasCut() = runTest {
        formsDir.resolve("oneline_fmb.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Module version=\"12.2.1.19.0\" " +
                "xmlns=\"http://xmlns.oracle.com/Forms\"><FormModule Name=\"ONELINE\" " +
                "Title=\"${"q".repeat(60_000)}\"/></Module>",
        )
        val key = ModuleKey.of("oneline", ModuleType.FORM)
        service.fetchModule(key)

        val text = service.readSourceResource(key, "oracleforms://ONELINE.fmb/converted")

        assertTrue(text.length < MAX_RESULT_CHARS)
        assertTrue(text.contains("line 1 of 1 was cut part-way"), text.takeLast(400))
        assertTrue(text.contains("get_object_xml"), text.takeLast(400))
        assertFalse(text.contains("startLine="), "there is no next line to continue at: ${text.takeLast(400)}")
    }

    /** With lines after the cut one, the continuation is offered — for those lines, not "the rest". */
    @Test
    fun aCutFirstLineFollowedByMoreLinesOffersTheLinesAfterIt() = runTest {
        formsDir.resolve("oneline_fmb.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Module version=\"12.2.1.19.0\" " +
                "xmlns=\"http://xmlns.oracle.com/Forms\"><FormModule Name=\"ONELINE\" " +
                "Title=\"${"q".repeat(60_000)}\">\n</FormModule>\n</Module>\n",
        )
        val key = ModuleKey.of("oneline", ModuleType.FORM)
        service.fetchModule(key)

        val text = service.readSourceResource(key, "oracleforms://ONELINE.fmb/converted")

        val note = text.takeLast(500)
        assertTrue(note.contains("line 1 of 3 was cut part-way"), note)
        assertTrue(note.contains("startLine=2) for the lines after it"), note)
        assertFalse(note.contains("for the rest"), note)
    }

    @Test
    fun theDetailedOverviewCapsWindowsAndCanvasesAndSaysSo() = runTest {
        BigModules.writeForm(formsDir)
        service.fetchModule(bigKey)

        val overview = service.overview(bigKey, detailed = true)

        val detail = assertNotNull(overview.detail)
        assertEquals(500, detail.windows.size)
        assertEquals(500, detail.canvases.size)
        assertTrue(overview.truncated)
    }
}
