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

    /**
     * A block the size of a real data-entry screen: every item with the properties `get_block`
     * reports at `detailed`, a property class, a trigger, and the base table behind it.
     */
    private fun writeCrowdedBlock(items: Int = 150, columns: Int = 350) {
        val rows = (1..items).joinToString("\n") { n ->
            """
            |      <Item Name="FIELD_$n" ItemType="Text Item" DataType="Char" ColumnName="FIELD_$n" CanvasName="CV_MAIN" Prompt="Field number $n" MaximumLength="40" ParentModule="WIDE" ParentModuleType="12" ParentName="PC_FIELD" ParentType="29" InsertAllowed="true">
            |        <Trigger Name="WHEN-VALIDATE-ITEM" TriggerText="NULL;"/>
            |      </Item>
            """.trimMargin()
        }
        val cols = (1..columns).joinToString("\n") { n ->
            "      <DataSourceColumn DSCLength=\"40\" DSCMandatory=\"false\" DSCPrecision=\"0\" " +
                "DSCName=\"COLUMN_$n\" DSCScale=\"0\" DSCType=\"VARCHAR2\" DSCNochildren=\"false\" Type=\"Query\"/>"
        }
        formsDir.resolve("wide_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="WIDE">
            |    <Block Name="WIDE_BLOCK" QueryDataSourceName="WIDE">
            |$rows
            |$cols
            |    </Block>
            |    <PropertyClass Name="PC_FIELD" DatabaseItem="true" UpdateAllowed="true" Required="false"/>
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

    /**
     * The detailed block of a real data-entry screen — items, their resolved properties, and the
     * base table's columns — is the largest thing this server returns that is not a file slice, and
     * all three together do not fit. What must hold is that the result fits, says what it left out,
     * and keeps the parts that answer the question: the counts and the column name lists.
     */
    @Test
    fun theDetailedBlockOfACrowdedScreenIsCutToFitAndSaysWhatItLeftOut() = runTest {
        writeCrowdedBlock()
        service.fetchModule(wideKey)

        val detail = service.getBlock(wideKey, "WIDE_BLOCK", detailed = true, columns = true)
        val size = textLength(toolResult(detail, detail.source))

        assertTrue(size <= MAX_RESULT_CHARS, "a detailed block serialized to $size chars")
        assertTrue(detail.truncated)
        assertEquals(150, detail.itemTotal, "the count is of the block, not of the page")
        assertTrue(detail.block.items.size < 150)
        assertTrue(detail.effectiveDml.keys.all { name -> detail.block.items.any { it.name == name } })
        val columns = assertNotNull(detail.columns)
        assertEquals(350, columns.total, "the totals describe the table, not the rows that fit")
        // The name lists are the answer, so they are never cut, and they are computed against the
        // whole block rather than the items that fit.
        assertEquals(350, columns.columnsWithoutItem.size)
        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("of 150 items"), hint)
        assertTrue(hint.contains("verbosity=\"concise\""), hint)
    }

    /**
     * Items small enough to all fit, whose class writes a long initial value: the resolved map is
     * the part that overflows. An item missing from a cut map must not read like an item whose
     * class did not resolve — that absence is how a property gets reported as the Forms default.
     */
    @Test
    fun aCutEffectiveDmlIsReportedAndNotMistakenForAnUnresolvedClass() = runTest {
        val items = (1..100).joinToString("\n") { n ->
            "      <Item Name=\"FIELD_$n\" ItemType=\"Text Item\" ParentModule=\"WIDE\" " +
                "ParentModuleType=\"12\" ParentName=\"PC_LONG\" ParentType=\"29\"/>"
        }
        formsDir.resolve("wide_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="WIDE">
            |    <Block Name="WIDE_BLOCK" QueryDataSourceName="WIDE">
            |$items
            |    </Block>
            |    <PropertyClass Name="PC_LONG" DatabaseItem="true" InitializeValue="${"v".repeat(600)}"/>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
        service.fetchModule(wideKey)

        val detail = service.getBlock(wideKey, "WIDE_BLOCK", detailed = true)

        assertEquals(100, detail.block.items.size, "the items themselves must fit for this test")
        assertTrue(detail.propertyClasses.single().resolved)
        val served = detail.effectiveDml.size
        assertTrue(served in 1..<100, "effectiveDml was not cut: $served")
        assertTrue(detail.truncated, "a cut effectiveDml is a cut result")
        assertTrue(textLength(toolResult(detail, detail.source)) <= MAX_RESULT_CHARS)
        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("'effectiveDml' covers $served of the 100 items"), hint)
        assertTrue(hint.contains("from 'FIELD_${served + 1}' on"), hint)
        assertTrue(hint.contains("not thereby unresolved"), hint)
        assertFalse(hint.contains("of 100 items: the rest would not fit"), "items were not cut: $hint")
        assertTrue(detail.unresolvedItems.isEmpty(), "every item resolved; none is unknown")

        // The follow-up the hint names reaches the omitted items, and answers from the same resolution.
        val next = "FIELD_${served + 1}"
        assertTrue(hint.contains("items=[\"$next\""), hint)
        val narrowed = service.getBlock(wideKey, "WIDE_BLOCK", detailed = true, items = listOf(next))
        assertEquals(setOf(next), narrowed.effectiveDml.keys)
        assertFalse(narrowed.truncated)
    }

    /**
     * The unknowns are spent through the same budget as everything else: a block of unresolved
     * items whose rows outgrow their share is cut and says so, and the per-class count in the hint
     * still covers every one of them.
     */
    @Test
    fun aCutUnresolvedItemsListIsReported() = runTest {
        val farAway = "SHARED_${"X".repeat(1_500)}"
        val items = (1..100).joinToString("\n") { n ->
            "      <Item Name=\"FIELD_$n\" ItemType=\"Text Item\" ParentModule=\"WIDE\" " +
                "ParentModuleType=\"12\" ParentName=\"PC_REMOTE\" ParentType=\"29\"/>"
        }
        formsDir.resolve("wide_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="WIDE">
            |    <Block Name="WIDE_BLOCK" QueryDataSourceName="WIDE">
            |$items
            |    </Block>
            |    <PropertyClass Name="PC_REMOTE" ParentModule="$farAway" ParentModuleType="12" ParentName="PC_REMOTE" ParentFilename="$farAway.fmb" ParentType="29"/>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
        service.fetchModule(wideKey)

        val detail = service.getBlock(wideKey, "WIDE_BLOCK", detailed = true)

        assertEquals(100, detail.block.items.size, "the items themselves must fit for this test")
        assertTrue(detail.unresolvedItems.size in 1..<100, "unresolvedItems was not cut: ${detail.unresolvedItems.size}")
        assertTrue(detail.truncated)
        assertTrue(textLength(toolResult(detail, detail.source)) <= MAX_RESULT_CHARS)
        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("'unresolvedItems' lists ${detail.unresolvedItems.size} of the 100 items"), hint)
        assertTrue(hint.contains("100 item(s) take their properties from PC_REMOTE"), hint)
    }

    /**
     * Two hundred hits of attribute-dense XML are larger than one response. The page is cut to fit
     * and continues exactly where it stopped — while the counts still describe every hit.
     */
    @Test
    fun aSearchPageTooLargeForOneResponseIsCutAndContinues() = runTest {
        writeWideForm()
        service.fetchModule(wideKey)

        val page = service.searchSource(wideKey, "value-", regex = false, scope = "xml", maxResults = 200)

        assertTrue(textLength(toolResult(page)) <= MAX_RESULT_CHARS, "spilled on the client")
        assertTrue(page.hits.size < 200, "the fixture must overflow the character budget")
        assertEquals(300, page.total)
        assertEquals(listOf(300), page.files.map { it.hits })
        assertTrue(page.truncated)
        assertEquals(page.hits.size, page.nextOffset)
        assertTrue(assertNotNull(page.hint).contains("offset=${page.hits.size})"), page.hint)

        val next = service.searchSource(
            wideKey, "value-", regex = false, scope = "xml", maxResults = 200, offset = page.nextOffset!!,
        )
        assertEquals(page.hits.last().line + 1, next.hits.first().line, "no hole, no overlap")
    }

    /**
     * Relations are spent through the same budget, first and within a share: a generated master of
     * dozens of long-joined details is cut rather than crowding out its items, and the hint names
     * the call that reads the first relation left out.
     */
    @Test
    fun tooManyRelationsAreCutAndNameTheCallThatReadsTheRest() = runTest {
        val join = "MASTER.KEY_COLUMN = DETAIL.KEY_COLUMN AND DETAIL.STATUS = 'OPEN' ".repeat(8)
        val relations = (1..80).joinToString("\n") { n ->
            "      <Relation Name=\"MASTER_DETAIL_$n\" DetailBlock=\"DETAIL_$n\" JoinCondition=\"$join\" " +
                "PreventMasterlessOperations=\"true\" RelationType=\"Join\"/>"
        }
        formsDir.resolve("wide_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="WIDE">
            |    <Block Name="MASTER" QueryDataSourceName="MASTER">
            |      <Item Name="KEY_COLUMN" ItemType="Text Item"/>
            |$relations
            |    </Block>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
        service.fetchModule(wideKey)

        val detail = service.getBlock(wideKey, "MASTER")

        val served = detail.block.relations.size
        assertTrue(served in 1..<80, "relations were not cut: $served")
        assertEquals(1, detail.block.items.size, "the items keep their share")
        assertTrue(detail.truncated)
        assertTrue(textLength(toolResult(detail, detail.source)) <= MAX_RESULT_CHARS)
        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("Returned $served of the 80 relations 'MASTER' is the master of"), hint)
        assertTrue(
            hint.contains("objectType=\"Relation\", name=\"MASTER_DETAIL_${served + 1}\", owner=\"MASTER\""),
            hint,
        )
    }

    /** A screen that does fit is not cut, and nothing says it was. */
    @Test
    fun anOrdinaryBlockIsServedWhole() = runTest {
        writeCrowdedBlock(items = 30, columns = 40)
        service.fetchModule(wideKey)

        val detail = service.getBlock(wideKey, "WIDE_BLOCK", detailed = true, columns = true)

        assertFalse(detail.truncated)
        assertEquals(30, detail.block.items.size)
        assertEquals(30, detail.effectiveDml.size)
        assertFalse(assertNotNull(detail.columns).truncated)
        assertTrue(textLength(toolResult(detail, detail.source)) <= MAX_RESULT_CHARS)
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
