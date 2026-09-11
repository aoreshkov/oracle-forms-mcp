package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.dto.ModuleSearchHit
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
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
 * `search_modules` over a cache larger than one call may read.
 *
 * A cross-module search has two ways to run away on a real forms directory, and only one of them is
 * the number of hits: a query that matches *nothing* would read every converted file in the cache —
 * megabytes per form, thousands of forms — before answering at all. Both bounds are therefore
 * enforced, reported, and resumable, which is what these tests hold. `list_modules` learned the
 * same lesson; see [ListModulesTest].
 */
class SearchModulesBoundsTest {

    private val temp: Path = Files.createTempDirectory("search-modules-bounds")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private val cache = InMemoryCache(temp.resolve("cache"))

    /**
     * [count] cached modules, each of whose converted text holds exactly one occurrence of
     * `NEEDLE`. Parsed by [FakeParser] — the scan reads the converted file, not the index, so no
     * real Forms XML is needed to fill the cache.
     */
    private suspend fun serviceWith(count: Int): FormsService {
        val modules = (0 until count).map { i ->
            val name = "MOD%04d".format(i)
            val file = temp.resolve("${name.lowercase()}_fmb.xml")
            file.writeText("<Module>\n  <!-- NEEDLE -->\n</Module>\n")
            ScannedModule(key = ModuleKey.of(name, ModuleType.FORM), preConvertedPath = file.toString())
        }
        val service = FormsService(
            scanner = FakeScanner(modules),
            converter = CopyingConverter(),
            // The cache-relative path of the text form the converter wrote, as a real index carries it.
            parser = FakeParser { key, converted ->
                minimalIndex(key, converted).copy(convertedFile = "converted/${Path.of(converted).name}")
            },
            cache = cache,
            annotationStore = InMemoryAnnotationStore(),
            formsDir = temp,
            binaryConversion = false,
        )
        modules.forEach { service.fetchModule(it.key) }
        return service
    }

    /**
     * The case that has no hits to bound it. The scan stops at the module budget and says so, so a
     * fruitless search over a large cache costs one bounded call instead of reading the whole thing.
     */
    @Test
    fun aQueryThatMatchesNothingStillStopsAtTheModuleBudget() = runTest {
        val service = serviceWith(MAX_MODULES_PER_SEARCH + 50)

        val first = service.searchModules(query = "ABSENT", scope = "xml")

        assertEquals(emptyList(), first.hits)
        assertEquals(MAX_MODULES_PER_SEARCH + 50, first.cachedModules)
        assertEquals(MAX_MODULES_PER_SEARCH, first.scannedModules, "one call reads at most the budget")
        assertTrue(first.truncated, "an unfinished scan is never reported as an exhaustive one")
        val cursor = assertNotNull(first.nextCursor)
        assertTrue(assertNotNull(first.hint).contains("nextCursor"), first.hint!!)

        val second = service.searchModules(query = "ABSENT", scope = "xml", cursor = cursor)

        assertEquals(50, second.scannedModules, "the second call finishes the remaining modules")
        assertFalse(second.truncated)
        assertNull(second.nextCursor)
    }

    @Test
    fun aPageIsCappedAtMaxResultsAndResumesAtTheNextModule() = runTest {
        val service = serviceWith(MAX_SEARCH_HITS + 20)

        val first = service.searchModules(query = "NEEDLE", scope = "xml", maxResults = MAX_SEARCH_HITS)

        assertEquals(MAX_SEARCH_HITS, first.hits.size)
        assertTrue(first.truncated)

        val second = service.searchModules(
            query = "NEEDLE",
            scope = "xml",
            maxResults = MAX_SEARCH_HITS,
            cursor = assertNotNull(first.nextCursor),
        )

        assertEquals(20, second.hits.size)
        assertFalse(second.truncated)
        assertEquals(
            (0 until MAX_SEARCH_HITS + 20).map { "MOD%04d.fmb".format(it) },
            (first.hits + second.hits).map { it.module.toString() },
            "the two pages must cover every module once, in key order",
        )
    }

    @Test
    fun maxResultsIsClampedToTheCeiling() = runTest {
        val service = serviceWith(MAX_SEARCH_HITS + 20)

        assertEquals(
            MAX_SEARCH_HITS,
            service.searchModules(query = "NEEDLE", scope = "xml", maxResults = 10_000).hits.size,
        )
        assertEquals(
            1,
            service.searchModules(query = "NEEDLE", scope = "xml", maxResults = 0).hits.size,
            "a non-positive page size still returns a page",
        )
    }

    /**
     * Paging is keyset-based on the module key, so a module that leaves the cache between two pages
     * costs its own hits and nothing else — the walk does not skip or repeat its neighbours.
     */
    @Test
    fun aModuleEvictedBetweenPagesDoesNotDerailTheWalk() = runTest {
        val service = serviceWith(30)
        val first = service.searchModules(query = "NEEDLE", scope = "xml", maxResults = 10)
        val cursor = assertNotNull(first.nextCursor)
        cache.clear(ModuleKey.of("MOD0010", ModuleType.FORM))

        val second = service.searchModules(query = "NEEDLE", scope = "xml", maxResults = 30, cursor = cursor)

        assertEquals(
            (11 until 30).map { "MOD%04d.fmb".format(it) },
            second.hits.map { it.module.toString() },
            "the evicted module is missing; every other module is still visited exactly once",
        )
        assertFalse(second.truncated)
    }

    /**
     * The bound that matters to a client is bytes, not rows: Claude Code rejects an MCP tool result
     * over 25,000 tokens (~100 KB). So the page that must fit is the *worst* one — every hit's
     * snippet at its character cap — not the small one the fixtures produce.
     */
    @Test
    fun theWorstCasePageStillFitsTheToolOutputBudget() = runTest {
        val service = serviceWith(MAX_SEARCH_HITS)
        // Overwrite each converted text form with a line far longer than the snippet cap.
        cache.list().forEach { key ->
            Path.of(cache.moduleDir(key)).resolve("converted").toFile().listFiles()?.forEach { file ->
                file.writeText("NEEDLE${"x".repeat(1_000)}\n")
            }
        }

        val page = service.searchModules(query = "NEEDLE", scope = "xml", maxResults = MAX_SEARCH_HITS)

        assertEquals(MAX_SEARCH_HITS, page.hits.size)
        assertTrue(page.hits.all { it.snippet.length == 200 }, "snippets are capped, not whole lines")
        val serialized = toolJson.encodeToString(page)
        assertTrue(serialized.length < 100_000, "the worst-case page serialized to ${serialized.length} chars")
    }

    /** Every hit stays addressable, however many modules the scan crossed. */
    @Test
    fun everyHitCarriesTheUriThatOpensIt() = runTest {
        val service = serviceWith(3)

        val hits: List<ModuleSearchHit> = service.searchModules(query = "NEEDLE", scope = "xml").hits

        assertEquals(3, hits.size)
        assertEquals(
            listOf(
                "oracleforms://MOD0000.fmb/converted",
                "oracleforms://MOD0001.fmb/converted",
                "oracleforms://MOD0002.fmb/converted",
            ),
            hits.map { it.uri },
        )
        assertTrue(hits.all { it.line == 2 && it.snippet == "<!-- NEEDLE -->" }, hits.toString())
    }
}
