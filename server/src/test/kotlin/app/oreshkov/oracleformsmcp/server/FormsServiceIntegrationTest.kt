package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The whole copy-mode pipeline against the real scanner, converter, parser, and on-disk cache:
 * exactly what a no-ORACLE_HOME deployment runs.
 */
class FormsServiceIntegrationTest {

    private val temp: Path = Files.createTempDirectory("forms-e2e-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        formsDir = formsDir,
        oracleConversion = false,
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

    @Test
    fun fullReadPathOverAFetchedForm() = runTest {
        val summary = service.fetchModule(ordersKey)
        assertEquals(2, summary.blockCount)
        assertEquals(3, summary.itemCount)
        assertEquals(3, summary.triggerCount)
        assertEquals(3, summary.programUnitCount)
        assertEquals(listOf("UTILS"), summary.attachedLibraries)

        val overview = service.overview(ordersKey)
        assertEquals(listOf("ORDERS", "CONTROL"), overview.blocks)
        assertEquals(listOf("WINDOW_MAIN"), overview.windows)

        val block = service.getBlock(ordersKey, "orders").block
        assertEquals("ORDERS", block.queryDataSourceName)

        val triggers = service.listTriggers(ordersKey, block = "ORDERS", item = null, level = null)
        assertEquals(2, triggers.triggers.size)

        val trigger = service.getTrigger(ordersKey, "WHEN-VALIDATE-ITEM", block = null, item = null)
        assertEquals(TriggerLevel.ITEM, trigger.level)
        assertEquals(
            "IF :ORDERS.ORDER_ID IS NULL THEN\n  RAISE FORM_TRIGGER_FAILURE;\nEND IF;",
            trigger.text,
        )

        val spec = service.getProgramUnit(ordersKey, "PKG_ORDERS", unitType = "PACKAGE_SPEC")
        assertTrue(spec.text.startsWith("PACKAGE pkg_orders IS"))
        val ambiguous = assertFailsWith<IllegalArgumentException> {
            service.getProgramUnit(ordersKey, "PKG_ORDERS", unitType = null)
        }
        assertTrue(ambiguous.message!!.contains("unitType"))

        val plsqlHits = service.searchSource(ordersKey, "calc_total", regex = false, scope = "plsql", maxResults = 50)
        assertTrue(plsqlHits.hits.isNotEmpty())
        val xmlHits = service.searchSource(ordersKey, "QueryDataSourceName", regex = false, scope = "xml", maxResults = 50)
        assertTrue(xmlHits.hits.isNotEmpty())

        val objectXml = service.getObjectXml(ordersKey, "Block", "CONTROL", owner = null)
        assertTrue(objectXml.xml.trimStart().startsWith("<Block"))
        assertTrue(!objectXml.truncated)
    }

    @Test
    fun libraryDumpIsServedFromThePldItself() = runTest {
        service.fetchModule(utilsKey)
        val units = service.listProgramUnits(utilsKey).units
        assertEquals(4, units.size)
        val body = service.getProgramUnit(utilsKey, "PKG_UTIL", unitType = "PACKAGE_BODY")
        assertTrue(body.text.startsWith("PACKAGE BODY pkg_util"))
    }
}
