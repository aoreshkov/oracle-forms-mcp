package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `list_modules` is the entry point to every other tool, and on a real forms directory (thousands
 * of modules) the unfiltered, unpaged answer used to be roughly six times Claude Code's 25,000-token
 * tool-output cap — so discovery was the one call that could not succeed. These tests hold the
 * three properties that fixes it: a bounded first response, filters that select before the
 * filesystem is touched, and a cursor that walks the whole set exactly once.
 */
class ListModulesTest {

    private val temp: Path = Files.createTempDirectory("list-modules-test")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    /** [count] scanned modules whose files never need to exist — nothing here reads them. */
    private fun synthetic(count: Int, type: ModuleType = ModuleType.FORM): List<ScannedModule> =
        (0 until count).map { i ->
            val name = "MOD%04d".format(i)
            ScannedModule(
                key = ModuleKey.of(name, type),
                preConvertedPath = temp.resolve("${name.lowercase()}${type.convertedSuffix}").toString(),
            )
        }

    private fun serviceFor(modules: List<ScannedModule>): FormsService =
        fakeService(scanner = FakeScanner(modules), cacheRoot = temp.resolve("cache"))

    /** A module whose pre-converted file really exists, so it can be fetched into the cache. */
    private fun realModule(name: String, type: ModuleType = ModuleType.FORM): ScannedModule {
        val file = temp.resolve("${name.lowercase()}${type.convertedSuffix}")
        file.writeText("<Module/>")
        return ScannedModule(key = ModuleKey.of(name, type), preConvertedPath = file.toString())
    }

    @Test
    fun aLargeDirectoryAnswersWithOneBoundedPage() = runTest {
        val service = serviceFor(synthetic(5_000))

        val result = service.listModules()

        assertEquals(DEFAULT_MODULE_PAGE, result.returned)
        assertEquals(DEFAULT_MODULE_PAGE, result.modules.size)
        assertEquals(5_000, result.total)
        assertTrue(result.truncated)
        assertNotNull(result.nextCursor)
        assertEquals(mapOf(ModuleStatus.NOT_CACHED to 5_000), result.countsByStatus)
    }

    /**
     * The response the client actually receives must fit the tool-output budget. 25,000 tokens is
     * roughly 100 KB; the default page is held well inside that so later field additions (phases 1
     * and 2 add inheritance pointers and resource URIs) still have room.
     */
    @Test
    fun theDefaultPageStaysFarUnderTheToolOutputBudget() = runTest {
        val service = serviceFor(synthetic(5_000))

        val serialized = toolJson.encodeToString(ModuleList.serializer(), service.listModules())

        assertTrue(serialized.length < 50_000, "default page serialized to ${serialized.length} chars")
    }

    @Test
    fun theCursorWalksEveryModuleExactlyOnceInKeyOrder() = runTest {
        val service = serviceFor(synthetic(1_234))

        val seen = mutableListOf<ModuleKey>()
        var cursor: String? = null
        do {
            val page = service.listModules(limit = 500, cursor = cursor)
            assertEquals(page.modules.size, page.returned)
            seen += page.modules.map { it.module }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(1_234, seen.size)
        assertEquals(seen.distinct(), seen, "a module appeared on two pages")
        assertEquals(seen.sortedBy { it.toString() }, seen, "pages are not in canonical key order")
    }

    /** The last page must close the walk: no `truncated`, no cursor to follow. */
    @Test
    fun theFinalPageReportsNoContinuation() = runTest {
        val service = serviceFor(synthetic(120))

        val second = service.listModules(cursor = assertNotNull(service.listModules().nextCursor))

        assertEquals(20, second.returned)
        assertFalse(second.truncated)
        assertNull(second.nextCursor)
    }

    @Test
    fun limitIsClampedToTheMaximumPageSize() = runTest {
        val service = serviceFor(synthetic(2_000))

        assertEquals(MAX_MODULE_PAGE, service.listModules(limit = 10_000).returned)
        assertEquals(1, service.listModules(limit = 0).returned, "a non-positive limit still returns a page")
    }

    @Test
    fun patternMatchesNamesCaseInsensitivelyAsASubstring() = runTest {
        val service = serviceFor(listOf(realModule("SALES_ORDERS"), realModule("SALES_INVOICES"), realModule("ORDERS")))

        val result = service.listModules(pattern = "invoices")

        assertEquals(listOf("SALES_INVOICES"), result.modules.map { it.name })
        assertEquals(1, result.total)
    }

    @Test
    fun patternIsARegexWhenAsked() = runTest {
        val service = serviceFor(listOf(realModule("SALES_ORDERS"), realModule("SALES_INVOICES"), realModule("ORDERS")))

        assertEquals(
            listOf("SALES_INVOICES", "SALES_ORDERS"),
            service.listModules(pattern = "^SALES_", regex = true).modules.map { it.name },
        )
    }

    @Test
    fun aBrokenRegexSaysHowToRecover() = runTest {
        val service = serviceFor(synthetic(3))

        val failure = assertFailsWith<IllegalArgumentException> {
            service.listModules(pattern = "SALES_[", regex = true)
        }

        assertTrue(failure.message!!.contains("regex"), failure.message!!)
        assertTrue(failure.message!!.contains("substring"), "the message must name the way out")
    }

    @Test
    fun typeSelectsOneModuleKind() = runTest {
        val service = serviceFor(
            listOf(realModule("ORDERS"), realModule("ORDERS", ModuleType.LIBRARY), realModule("MENU", ModuleType.MENU)),
        )

        val result = service.listModules(type = ModuleType.LIBRARY)

        assertEquals(listOf(ModuleKey.of("ORDERS", ModuleType.LIBRARY)), result.modules.map { it.module })
    }

    /**
     * A `status` filter narrows the rows but must not blind the caller to the rest: the summary
     * still describes the whole name/type-filtered set.
     */
    @Test
    fun statusFiltersRowsWhileCountsStillCoverEveryMatch() = runTest {
        val fetched = realModule("SALES_ORDERS")
        val service = serviceFor(listOf(fetched, realModule("SALES_INVOICES"), realModule("ORDERS")))
        service.fetchModule(fetched.key)

        val result = service.listModules(pattern = "SALES_", status = ModuleStatus.CACHED)

        assertEquals(listOf("SALES_ORDERS"), result.modules.map { it.name })
        assertEquals(1, result.total, "'total' is the size of the filtered result set")
        assertEquals(
            mapOf(ModuleStatus.CACHED to 1, ModuleStatus.NOT_CACHED to 1),
            result.countsByStatus,
            "counts must describe the name/type match, before the status filter",
        )
    }

    @Test
    fun everyRowCarriesTheFlatNameBesideTheCanonicalKey() = runTest {
        val service = serviceFor(listOf(realModule("SALES_ORDERS")))

        val row = service.listModules().modules.single()

        assertEquals("SALES_ORDERS", row.name)
        assertEquals(ModuleKey.of("SALES_ORDERS", ModuleType.FORM), row.module)
        assertNotNull(row.sizeBytes)
        assertNotNull(row.lastModified)
    }

    @Test
    fun aCursorFromSomewhereElseSaysHowToRecover() = runTest {
        val service = serviceFor(synthetic(3))

        val failure = assertFailsWith<IllegalArgumentException> { service.listModules(cursor = "not-a-cursor") }

        assertTrue(failure.message!!.contains("nextCursor"), failure.message!!)
        assertTrue(failure.message!!.contains("omit"), "the message must name the way out")
    }

    /** Cache entries whose source vanished stay listed, in the same ordered universe as the rest. */
    @Test
    fun orphanedCacheEntriesAreListedAsSourceMissing() = runTest {
        val orphan = realModule("GONE")
        val remaining = realModule("ORDERS")
        val scanner = FakeScanner(listOf(orphan, remaining))
        val service = fakeService(scanner = scanner, cacheRoot = temp.resolve("cache"))
        service.fetchModule(orphan.key)
        scanner.modules = listOf(remaining) // the .fmb left the forms directory

        val result = service.listModules()

        assertEquals(
            mapOf("GONE" to ModuleStatus.SOURCE_MISSING, "ORDERS" to ModuleStatus.NOT_CACHED),
            result.modules.associate { it.name to it.status },
        )
    }
}
