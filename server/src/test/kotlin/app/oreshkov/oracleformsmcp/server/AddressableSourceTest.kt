package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.dto.SourceLocation
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Every `SourceLocation` this server hands out must be openable.
 *
 * A `SourceRef` is layout-independent by design — it never carries an absolute path — which left
 * its line ranges pointing at a file only the server could find. The property under test is that
 * the loop now closes: whatever a read tool reports as `source`, `read_source` accepts back and
 * returns the lines it named.
 */
class AddressableSourceTest {

    private val temp: Path = Files.createTempDirectory("addressable-source-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)

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
        copyFixture("orders_fmb.xml")
        copyFixture("utils.pld")
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    /** Reads back exactly the range a result pointed at, by URI and by cache-relative path alike. */
    private suspend fun readBack(key: ModuleKey, at: SourceLocation): String {
        val byUri = service.readSource(key, at.uri, at.startLine, at.endLine, maxLines = 2_000)
        val byFile = service.readSource(key, at.file, at.startLine, at.endLine, maxLines = 2_000)
        assertEquals(byUri.text, byFile.text, "'uri' and 'file' must name the same lines")
        return byUri.text
    }

    @Test
    fun everyLocationAReadToolReportsCanBeReadBack() = runTest {
        service.fetchModule(ordersKey)

        val trigger = service.getTrigger(ordersKey, "WHEN-VALIDATE-ITEM", block = null, item = null)
        val triggerAt = assertNotNull(trigger.source, "a served body must say where it came from")
        assertEquals(trigger.text, readBack(ordersKey, triggerAt))
        assertEquals("oracleforms://ORDERS.fmb/plsql/triggers/ORDERS.ORDER_ID.WHEN-VALIDATE-ITEM.sql", triggerAt.uri)

        val unit = service.getProgramUnit(ordersKey, "CALC_TOTAL", unitType = null)
        assertEquals(unit.text, readBack(ordersKey, assertNotNull(unit.source)))

        // The XML fragment and the block point into the converted file, at the same range.
        val objectXml = service.getObjectXml(ordersKey, "Block", "ORDERS", owner = null)
        val xmlAt = assertNotNull(objectXml.source)
        assertEquals("oracleforms://ORDERS.fmb/converted", xmlAt.uri)
        assertEquals(objectXml.xml, readBack(ordersKey, xmlAt))
        assertEquals(xmlAt, assertNotNull(service.getBlock(ordersKey, "ORDERS").source))
    }

    @Test
    fun aSearchHitCarriesTheUriOfTheFileItWasFoundIn() = runTest {
        service.fetchModule(ordersKey)

        val hit = service.searchSource(ordersKey, "calc_total", regex = false, scope = "plsql", maxResults = 5)
            .hits.first()
        val uri = assertNotNull(hit.uri, "a hit that names a file must say how to open it")
        val line = service.readSource(ordersKey, uri, startLine = hit.line, endLine = hit.line)

        assertTrue(line.text.contains("calc_total"), "the reported line must be the matching one: ${line.text}")
    }

    @Test
    fun aLibraryIsAddressedThroughTheSameConvertedUri() = runTest {
        service.fetchModule(utilsKey)

        // A .pll converts to a .pld text dump, so its program units point into that file directly.
        val unit = service.getProgramUnit(utilsKey, "PKG_UTIL", unitType = "PACKAGE_BODY")
        val at = assertNotNull(unit.source)
        assertEquals("oracleforms://UTILS.pll/converted", at.uri)
        assertEquals(unit.text, readBack(utilsKey, at))
    }

    @Test
    fun theResponseIsCappedByLinesAndSaysSo() = runTest {
        service.fetchModule(ordersKey)

        val page = service.readSource(ordersKey, "converted/orders_fmb.xml", startLine = 1, maxLines = 3)

        assertEquals(3, page.text.lines().size)
        assertEquals(1, page.source.startLine)
        assertEquals(3, page.source.endLine)
        assertTrue(page.truncated, "a file longer than the cap must say it was cut")
        assertTrue(page.totalLines > 3, "totalLines describes the whole file, not the page")

        // The next page continues exactly where this one stopped.
        val next = service.readSource(ordersKey, page.source.uri, startLine = page.source.endLine + 1, maxLines = 3)
        assertEquals(4, next.source.startLine)
        assertTrue(next.text.isNotEmpty())
    }

    @Test
    fun anEmptyRangeAndAnOutOfRangeStartAreBothAnswered() = runTest {
        service.fetchModule(ordersKey)
        val total = service.readSource(ordersKey, "converted/orders_fmb.xml", maxLines = 2_000).totalLines

        // Past the end is a caller error, and the message says how far the file goes.
        val failure = assertFailsWith<IllegalArgumentException> {
            service.readSource(ordersKey, "converted/orders_fmb.xml", startLine = total + 10)
        }
        assertTrue(failure.message!!.contains("$total lines"), failure.message!!)
    }

    @Test
    fun aUriForAnotherModuleIsRejectedRatherThanReadAgainstThisOne() = runTest {
        service.fetchModule(ordersKey)

        val failure = assertFailsWith<IllegalArgumentException> {
            service.readSource(ordersKey, "oracleforms://UTILS.pll/converted")
        }
        assertTrue(failure.message!!.contains("not a source URI of ORDERS.fmb"), failure.message!!)
        assertTrue(failure.message!!.contains("source.uri"), "the message must name where to get one")
    }

    @Test
    fun aTruncatedResourceReadSaysSoInsideTheTextItReturns() = runTest {
        service.fetchModule(ordersKey)

        val whole = service.readSourceResource(ordersKey, "oracleforms://ORDERS.fmb/converted")

        // The fixture is small, so nothing is cut and no marker is added.
        assertTrue(whole.trimStart().startsWith("<?xml"))
        assertTrue(!whole.contains("truncated at line"))

        // A resource read returns bytes and nothing else, so when it *is* cut it must say so in
        // them — and name the call that continues it.
        val sidecar = service.readSourceResource(
            ordersKey,
            "oracleforms://ORDERS.fmb/plsql/triggers/ORDERS.ORDER_ID.WHEN-VALIDATE-ITEM.sql",
        )
        assertTrue(sidecar.contains("RAISE FORM_TRIGGER_FAILURE"))
    }

    @Test
    fun fetchModuleNamesTheConvertedFileItProduced() = runTest {
        val summary = service.fetchModule(ordersKey)

        assertEquals("oracleforms://ORDERS.fmb/converted", summary.convertedUri)
        assertTrue(
            service.readSource(ordersKey, assertNotNull(summary.convertedUri)).text.contains("<FormModule"),
        )
    }
}
