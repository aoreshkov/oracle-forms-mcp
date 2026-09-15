package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.tools.toolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
 * A read that obeys every ceiling here must also fit what the client accepts.
 *
 * Converted XML is the worst case for that: one item per line, each line hundreds of attributes
 * long, every attribute two quotes that travel as `\"`. The old ceilings were counted in raw
 * characters and were far above a client's budget, so a wide block came back spilled to a file
 * and read in chunks whose boundaries left holes. The form here is generated to have that shape.
 */
class ResultBudgetTest {

    private val temp: Path = Files.createTempDirectory("result-budget-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val wideKey = ModuleKey.of("wide", ModuleType.FORM)

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

    /** A block of [items] items, each one line of ~[attributes] quoted attributes. */
    private fun writeWideForm(items: Int = 300, attributes: Int = 60, hugeLine: Boolean = false) {
        val rows = (1..items).joinToString("\n") { n ->
            val attrs = (1..attributes).joinToString(" ") { a -> "Attr$a=\"value-$n-$a\"" }
            "      <Item Name=\"ITEM_$n\" ItemType=\"Text Item\" $attrs/>"
        }
        val huge = if (hugeLine) "\n      <Item Name=\"HUGE\" Hint=\"${"q".repeat(90_000)}\"/>" else ""
        formsDir.resolve("wide_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="WIDE">
            |    <Block Name="WIDE_BLOCK" QueryDataSourceName="WIDE">
            |$rows$huge
            |    </Block>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
    }

    private fun textLength(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): Int =
        result.content.filterIsInstance<TextContent>().sumOf { it.text.length }

    @Test
    fun aWideSliceFitsTheDeclaredCeilingAndSaysWhereToContinue() = runTest {
        writeWideForm()
        service.fetchModule(wideKey)
        val uri = "oracleforms://WIDE.fmb/converted"

        val page = service.readSource(wideKey, uri, startLine = 5, endLine = 300, maxLines = 2_000)

        assertTrue(page.truncated, "300 lines of wide XML cannot fit one response")
        assertTrue(textLength(toolResult(page, page.source)) <= MAX_RESULT_CHARS, "spilled on the client")
        assertEquals(300, page.requestedEndLine)
        assertEquals(page.source.endLine + 1, page.nextStartLine)
        assertFalse(page.lineCut)
        val hint = assertNotNull(page.hint)
        assertTrue(hint.contains("of the requested 5-300"), hint)
        assertTrue(hint.contains("startLine=${page.nextStartLine}, endLine=300"), hint)
    }

    /** Following `nextStartLine` covers the request exactly: no hole, no overlap. */
    @Test
    fun followingTheContinuationReadsEveryLineOnce() = runTest {
        writeWideForm()
        service.fetchModule(wideKey)
        val uri = "oracleforms://WIDE.fmb/converted"
        val all = service.readSource(wideKey, uri, maxLines = 1).totalLines

        val collected = mutableListOf<String>()
        var start: Int? = 1
        var calls = 0
        while (start != null) {
            val page = service.readSource(wideKey, uri, startLine = start, endLine = all, maxLines = 2_000)
            assertEquals(start, page.source.startLine)
            collected += page.text.lines()
            start = page.nextStartLine
            calls++
        }

        assertTrue(calls > 1, "the fixture must actually need continuation")
        assertEquals(Files.readAllLines(formsDir.resolve("wide_fmb.xml")), collected)
    }

    @Test
    fun aSliceThatStopsAtTheLineCapSaysSoWithoutBlamingLineLength() = runTest {
        writeWideForm(items = 20, attributes = 2)
        service.fetchModule(wideKey)

        val page = service.readSource(wideKey, "converted/wide_fmb.xml", startLine = 1, maxLines = 3)

        val hint = assertNotNull(page.hint)
        assertTrue(hint.contains("line cap"), hint)
        assertTrue(hint.contains("file=\"converted/wide_fmb.xml\""), "continues in the form it was asked: $hint")
        assertFalse(hint.contains("thousands of characters"), hint)
    }

    @Test
    fun aSingleLineLongerThanTheBudgetIsCutAndSaysSo() = runTest {
        writeWideForm(items = 1, attributes = 1, hugeLine = true)
        service.fetchModule(wideKey)
        val uri = "oracleforms://WIDE.fmb/converted"
        val hugeLine = Files.readAllLines(formsDir.resolve("wide_fmb.xml")).indexOfFirst { "HUGE" in it } + 1

        val page = service.readSource(wideKey, uri, startLine = hugeLine, endLine = hugeLine + 1)

        assertTrue(page.lineCut)
        assertEquals(hugeLine, page.source.endLine)
        assertTrue(textLength(toolResult(page, page.source)) <= MAX_RESULT_CHARS)
        assertTrue(assertNotNull(page.hint).contains("alone is longer than one response"), page.hint)
    }

    @Test
    fun aWideObjectFragmentIsCutInsideTheCeilingAndPointsAtTheRest() = runTest {
        writeWideForm()
        service.fetchModule(wideKey)

        val block = service.getObjectXml(wideKey, "Block", "WIDE_BLOCK", owner = null)

        assertTrue(block.truncated)
        assertTrue(textLength(toolResult(block, block.source)) <= MAX_RESULT_CHARS, "spilled on the client")
        val hint = assertNotNull(block.hint)
        assertTrue(hint.contains("read_source(module=\"WIDE.fmb\", uri=\"oracleforms://WIDE.fmb/converted\""), hint)

        val small = service.getObjectXml(wideKey, "Item", "ITEM_1", owner = "WIDE_BLOCK")
        assertFalse(small.truncated)
        assertNull(small.hint)
    }

    @Test
    fun theEscapedLengthCountsWhatJsonActuallyWrites() {
        assertEquals(toolJsonLength("plain"), jsonEscapedLength("plain"))
        val xml = "<Item Name=\"A\" Text=\"tab\there\\slash\"/>"
        assertEquals(toolJsonLength(xml), jsonEscapedLength(xml))
        assertEquals(3, jsonEscapedPrefixLength("a\"bc", 4))
        assertEquals(4, jsonEscapedPrefixLength("a\"bc", 99))
    }

    private fun toolJsonLength(text: String): Int =
        app.oreshkov.oracleformsmcp.server.tools.toolJson
            .encodeToString(kotlinx.serialization.serializer<String>(), text).length - 2
}
