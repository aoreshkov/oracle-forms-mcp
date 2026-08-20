package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.core.ModuleNotFetchedException
import app.oreshkov.oracleformsmcp.core.ModuleStaleException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class FormsServiceTest {

    private val temp: Path = Files.createTempDirectory("forms-service-test")
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun preConverted(name: String, content: String = "<Module/>"): Path {
        val file = temp.resolve(name)
        file.writeText(content)
        return file
    }

    private fun serviceFor(vararg modules: ScannedModule): FormsService =
        fakeService(scanner = FakeScanner(modules.toList()), cacheRoot = temp.resolve("cache"))

    private fun ordersModule(file: Path): ScannedModule =
        ScannedModule(key = ordersKey, preConvertedPath = file.toString())

    /**
     * Tool handlers are dispatched concurrently (MCP SDK 0.15+). Without the per-module fetch lock
     * both callers miss the cache and both run convert → parse → putIndex into the same cache
     * directory. Exactly one may do the work; the rest must see it as a warm hit.
     */
    @Test
    fun concurrentFetchesOfOneModuleConvertOnce() = runBlocking {
        val file = preConverted("orders_fmb.xml")
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(listOf(ordersModule(file))),
            converter = converter,
            cacheRoot = temp.resolve("cache"),
        )

        val summaries = List(8) { async(Dispatchers.Default) { service.fetchModule(ordersKey) } }.awaitAll()

        assertEquals(1, converter.targetDirs.size, "the module was converted more than once")
        assertEquals(1, summaries.count { !it.fromCache }, "more than one caller did the work")
        assertEquals(1, summaries.map { it.copy(fromCache = false) }.distinct().size)
    }

    /** The lock is per module, not global: distinct modules must still convert independently. */
    @Test
    fun concurrentFetchesOfDifferentModulesBothConvert() = runBlocking {
        val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(
                listOf(
                    ordersModule(preConverted("orders_fmb.xml")),
                    ScannedModule(key = utilsKey, preConvertedPath = preConverted("utils.pld").toString()),
                )
            ),
            converter = converter,
            cacheRoot = temp.resolve("cache"),
        )

        listOf(ordersKey, utilsKey)
            .map { key -> async(Dispatchers.Default) { service.fetchModule(key) } }
            .awaitAll()

        assertEquals(2, converter.targetDirs.size)
    }

    @Test
    fun fetchIsFingerprintIdempotent() = runTest {
        val file = preConverted("orders_fmb.xml")
        val service = serviceFor(ordersModule(file))

        assertFalse(service.fetchModule(ordersKey).fromCache)
        assertTrue(service.fetchModule(ordersKey).fromCache)

        // A content change invalidates the warm hit and re-fetches.
        file.writeText("<Module changed='true'/>")
        assertFalse(service.fetchModule(ordersKey).fromCache)
    }

    /** `--converted-dir` is the directory the converter writes into, not a later destination. */
    @Test
    fun convertedDirIsHandedToTheConverterAsItsOutputDirectory() = runTest {
        val convertedDir = temp.resolve("forms-xml")
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(listOf(ordersModule(preConverted("orders_fmb.xml")))),
            cacheRoot = temp.resolve("cache"),
            converter = converter,
            convertedDir = convertedDir,
        )

        service.fetchModule(ordersKey)

        assertEquals(listOf(convertedDir.toString()), converter.targetDirs)
    }

    /** Without it, each module still converts inside its own cache entry. */
    @Test
    fun withoutAConvertedDirTheConverterWritesIntoTheModulesCacheEntry() = runTest {
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(listOf(ordersModule(preConverted("orders_fmb.xml")))),
            cacheRoot = temp.resolve("cache"),
            converter = converter,
        )

        service.fetchModule(ordersKey)

        assertEquals(
            listOf(temp.resolve("cache").resolve("ORDERS.fmb").resolve("converted").toString()),
            converter.targetDirs,
        )
    }

    @Test
    fun readBeforeFetchTellsModelToFetch() = runTest {
        val service = serviceFor(ordersModule(preConverted("orders_fmb.xml")))
        val error = assertFailsWith<ModuleNotFetchedException> { service.overview(ordersKey) }
        assertTrue(error.message!!.contains("fetch_module"))
    }

    @Test
    fun changedSourceMakesReadsStaleUntilRefetch() = runTest {
        val file = preConverted("orders_fmb.xml")
        val service = serviceFor(ordersModule(file))
        service.fetchModule(ordersKey)

        file.writeText("<Module edited='yes'/>")
        val error = assertFailsWith<ModuleStaleException> { service.overview(ordersKey) }
        assertTrue(error.message!!.contains("fetch_module"))

        service.fetchModule(ordersKey)
        service.overview(ordersKey) // heals
    }

    @Test
    fun listModulesReportsStatusPerModule() = runTest {
        val ordersFile = preConverted("orders_fmb.xml")
        val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)
        val service = serviceFor(
            ordersModule(ordersFile),
            ScannedModule(key = utilsKey, preConvertedPath = preConverted("utils.pld", "PROCEDURE x").toString()),
        )
        service.fetchModule(ordersKey)
        ordersFile.writeText("<Module edited='yes'/>")

        val statuses = service.listModules().modules.associate { it.module to it.status }
        assertEquals(ModuleStatus.STALE, statuses[ordersKey])
        assertEquals(ModuleStatus.NOT_CACHED, statuses[utilsKey])
    }

    @Test
    fun resolveModuleHandlesBareNamesAndAmbiguity() = runTest {
        val service = serviceFor(
            ordersModule(preConverted("orders_fmb.xml")),
            ScannedModule(
                key = ModuleKey.of("orders", ModuleType.LIBRARY),
                preConvertedPath = preConverted("orders.pld", "PROCEDURE x").toString(),
            ),
            ScannedModule(
                key = ModuleKey.of("utils", ModuleType.LIBRARY),
                preConvertedPath = preConverted("utils2.pld", "PROCEDURE x").toString(),
            ),
        )

        assertEquals(ModuleKey.of("utils", ModuleType.LIBRARY), service.resolveModule("utils"))
        assertEquals(ordersKey, service.resolveModule("ORDERS.fmb"))

        val ambiguous = assertFailsWith<IllegalArgumentException> { service.resolveModule("orders") }
        assertTrue(ambiguous.message!!.contains("ORDERS.fmb"))
        assertTrue(ambiguous.message!!.contains("ORDERS.pll"))

        val unknown = assertFailsWith<IllegalArgumentException> { service.resolveModule("nope") }
        assertTrue(unknown.message!!.contains("list_modules"))
    }
}
