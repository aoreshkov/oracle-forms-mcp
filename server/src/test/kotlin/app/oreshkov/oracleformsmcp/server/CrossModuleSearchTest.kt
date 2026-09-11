package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.dto.ModuleSearchHit
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.tools.toolJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
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
 * `search_modules` — the questions a per-module search cannot answer.
 *
 * Tracing one form always leaves the module it was scoped to: a modal picker is opened by whoever
 * calls it, the value it returns travels through a `:GLOBAL`, and its toolbar is defined in a third
 * form entirely. Each of those used to mean fetching candidate modules one at a time, which on a
 * real forms directory of a few thousand is not a strategy at all.
 *
 * The fixtures model exactly that shape: `ENTRY` calls `PICKER`, both touch
 * `:GLOBAL.picked_ref`, and both subclass `TOOLBAR`'s `BAR` block.
 */
class CrossModuleSearchTest {

    private val temp: Path = Files.createTempDirectory("cross-module-search")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val entry = ModuleKey.of("entry", ModuleType.FORM)
    private val picker = ModuleKey.of("picker", ModuleType.FORM)
    private val toolbar = ModuleKey.of("toolbar", ModuleType.FORM)

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
        // ORDERS is copied but never fetched: every test therefore also exercises the reporting of
        // a module the search could not reach.
        listOf("entry_fmb.xml", "picker_fmb.xml", "toolbar_fmb.xml", "orders_fmb.xml").forEach(::copyFixture)
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    private suspend fun fetchAll() {
        listOf(entry, picker, toolbar).forEach { service.fetchModule(it) }
    }

    private fun List<ModuleSearchHit>.modules(): List<ModuleKey> = map { it.module }.distinct()

    /**
     * *What calls this form?* — the first question a modal window raises, and the one a search
     * scoped to the modal's own module can never answer. The call is written `call_form('picker')`
     * in lowercase while the module is `PICKER`, which is why matching is case-insensitive by
     * default: Forms code names the same module three ways in the same code base.
     */
    @Test
    fun whatCallsThisFormIsOneCallAcrossEveryCachedModule() = runTest {
        fetchAll()

        val result = service.searchModules(query = "PICKER")

        assertEquals(listOf(entry), result.hits.modules(), "only ENTRY calls the picker")
        val hit = result.hits.single()
        assertEquals("ENTRY.fmb", hit.moduleSpec, "a hit names its module the way every other tool takes it")
        assertTrue(hit.snippet.contains("call_form('picker'"), hit.snippet)
        assertTrue(hit.path.startsWith("plsql/"), hit.path)
        assertEquals(3, hit.line, "the hit is a line of the extracted PL/SQL, not of the whole module")
        assertNotNull(hit.uri, "a hit that cannot be opened is half a fact")
        assertFalse(result.truncated)
        assertNull(result.nextCursor)
    }

    /** *What else touches this global?* — the value a modal hands back travels through one. */
    @Test
    fun aGlobalVariableIsTracedToEveryModuleThatTouchesIt() = runTest {
        fetchAll()

        val result = service.searchModules(query = ":GLOBAL.picked_ref")

        assertEquals(listOf(entry, picker), result.hits.modules())
        assertEquals(3, result.hits.count { it.module == entry }, "ENTRY clears it, calls, then reads it back")
        assertEquals(1, result.hits.count { it.module == picker }, "PICKER writes it before exiting")
        assertEquals(3, result.scannedModules)
        assertEquals(3, result.cachedModules)
    }

    /**
     * *Which modules subclass this shared block?* — Forms records that as a `ParentFilename`
     * attribute of the converted XML, so it is the one of the three questions that belongs in the
     * `xml` scope. The pointers `get_block` reports per module are the same fact from the other end.
     */
    @Test
    fun subclassConsumersOfASharedBlockAreFoundInTheXmlScope() = runTest {
        fetchAll()

        val result = service.searchModules(query = "ParentFilename=\"toolbar.fmb\"", scope = "xml")

        assertEquals(listOf(entry, picker), result.hits.modules())
        assertTrue(result.hits.all { it.path.startsWith("converted/") }, result.hits.map { it.path }.toString())
        assertTrue(result.hits.all { it.uri == "oracleforms://${it.module}/converted" }, "hits must be openable")
    }

    /** The default scope is the extracted PL/SQL, so an XML-only query finds nothing until asked. */
    @Test
    fun theDefaultScopeDoesNotSearchTheRawXml() = runTest {
        fetchAll()

        assertEquals(emptyList(), service.searchModules(query = "ParentFilename").hits)
        assertTrue(service.searchModules(query = "ParentFilename", scope = "all").hits.isNotEmpty())
    }

    /**
     * The one thing a cross-module hit list cannot show is the module it never read. A search that
     * covered a third of the directory looks exactly like one that found nothing, so the modules it
     * could not reach are counted and the hint names the call that adds them.
     */
    @Test
    fun modulesThatWereNeverFetchedAreCountedRatherThanSilentlyAbsent() = runTest {
        service.fetchModule(entry) // PICKER, TOOLBAR and ORDERS stay un-fetched

        val result = service.searchModules(query = ":GLOBAL.picked_ref")

        assertEquals(listOf(entry), result.hits.modules())
        assertEquals(1, result.cachedModules)
        assertEquals(1, result.scannedModules)
        assertEquals(3, result.skippedNotCached)
        val hint = assertNotNull(result.hint, "coverage that is not total must say so")
        assertTrue(hint.contains("3 matching module(s) are not cached"), hint)
        assertTrue(hint.contains("fetch_module"), "the hint must name the call that widens the search: $hint")
        assertTrue(hint.contains("list_modules(status=\"not_cached\")"), hint)
    }

    /**
     * A module whose `.fmb` changed since it was indexed is skipped, not searched: its cached text
     * describes a form that no longer exists. One stale module must not fail a search over the
     * others either — that is why this path reports instead of throwing the way `index` does.
     */
    @Test
    fun aStaleModuleIsSkippedAndSaysSoInsteadOfFailingTheWholeSearch() = runTest {
        fetchAll()
        formsDir.resolve("picker_fmb.xml").appendText("<!-- edited after indexing -->")

        val result = service.searchModules(query = ":GLOBAL.picked_ref")

        assertEquals(listOf(entry), result.hits.modules(), "PICKER's cached text no longer describes its source")
        assertEquals(1, result.skippedStale)
        assertEquals(2, result.scannedModules)
        val hint = assertNotNull(result.hint)
        assertTrue(hint.contains("1 cached module(s) changed on disk"), hint)
        assertTrue(hint.contains("fetch_module"), hint)
    }

    @Test
    fun modulePatternNarrowsTheModulesThatAreReadAtAll() = runTest {
        fetchAll()

        val result = service.searchModules(query = ":GLOBAL", modulePattern = "pick")

        assertEquals(listOf(picker), result.hits.modules())
        assertEquals(1, result.cachedModules, "'modulePattern' selects the searchable universe")
        assertEquals(1, result.scannedModules)
        assertEquals(0, result.skippedNotCached, "ORDERS does not match the pattern, so it is not missing")
    }

    @Test
    fun theCursorWalksEveryHitExactlyOnceInModuleFileLineOrder() = runTest {
        fetchAll()
        val whole = service.searchModules(query = ":GLOBAL").hits
        assertTrue(whole.size >= 4, "the fixtures must offer enough hits to page through")

        val seen = mutableListOf<ModuleSearchHit>()
        var cursor: String? = null
        do {
            val page = service.searchModules(query = ":GLOBAL", maxResults = 1, cursor = cursor)
            assertTrue(page.hits.size <= 1)
            seen += page.hits
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(whole, seen, "one hit per page must reproduce the whole result, in the same order")
        assertEquals(
            seen.sortedWith(compareBy({ it.module.toString() }, { it.path }, { it.line })),
            seen,
            "the order a cursor resumes into must be deterministic",
        )
    }

    /**
     * A page that fills exactly at its cap cannot claim the scan was exhaustive: whether the
     * modules after it hold more hits is only knowable by reading them, which is what the cap
     * stopped. So it offers a cursor, and the page that follows closes the walk — the honest order
     * of those two, rather than a `truncated: false` that has not been established.
     */
    @Test
    fun aPageThatFillsAtTheCapOffersACursorAndTheNextPageClosesTheWalk() = runTest {
        fetchAll()
        val total = service.searchModules(query = ":GLOBAL.picked_ref").hits.size

        val exact = service.searchModules(query = ":GLOBAL.picked_ref", maxResults = total)
        assertEquals(total, exact.hits.size)
        assertTrue(exact.truncated)

        val beyond = service.searchModules(
            query = ":GLOBAL.picked_ref",
            maxResults = total,
            cursor = assertNotNull(exact.nextCursor),
        )

        assertEquals(emptyList(), beyond.hits)
        assertFalse(beyond.truncated)
        assertNull(beyond.nextCursor)
    }

    /** A search whose every hit fits in the page reports itself complete, with nothing to follow. */
    @Test
    fun aSearchThatFitsInOnePageReportsItselfComplete() = runTest {
        fetchAll()

        val result = service.searchModules(query = ":GLOBAL.picked_ref")

        assertEquals(4, result.hits.size)
        assertFalse(result.truncated)
        assertNull(result.nextCursor)
    }

    /**
     * A cursor is a position in one result set. Carried over to a different query it would name a
     * page of something else, so it is bound to the arguments it was minted for and says so.
     */
    @Test
    fun aCursorFromADifferentSearchIsRefusedRatherThanSilentlyContinued() = runTest {
        fetchAll()
        val first = service.searchModules(query = ":GLOBAL", maxResults = 1)
        val cursor = assertNotNull(first.nextCursor)

        // The same arguments advance by one hit...
        val second = service.searchModules(query = ":GLOBAL", maxResults = 1, cursor = cursor)
        assertEquals(first.hits.single().path, second.hits.single().path)
        assertTrue(second.hits.single().line > first.hits.single().line)

        // ...while a different query is refused rather than served a page of the wrong result set.
        val failure = assertFailsWith<IllegalArgumentException> {
            service.searchModules(query = "call_form", maxResults = 1, cursor = cursor)
        }

        assertTrue(failure.message!!.contains("different search"), failure.message!!)
        assertTrue(failure.message!!.contains("omit 'cursor'"), "the message must name the way out")
    }

    @Test
    fun aCursorFromSomewhereElseSaysHowToRecover() = runTest {
        fetchAll()

        val failure = assertFailsWith<IllegalArgumentException> {
            service.searchModules(query = ":GLOBAL", cursor = "not-a-cursor")
        }

        assertTrue(failure.message!!.contains("nextCursor"), failure.message!!)
        assertTrue(failure.message!!.contains("omit 'cursor'"), failure.message!!)
    }

    @Test
    fun aRegexQueryIsSupportedAndABrokenOneSaysHowToRecover() = runTest {
        fetchAll()

        val result = service.searchModules(query = """call_form\('[a-z]+'""", regex = true)
        assertEquals(listOf(entry), result.hits.modules())

        val failure = assertFailsWith<IllegalArgumentException> {
            service.searchModules(query = "call_form(", regex = true)
        }
        assertTrue(failure.message!!.contains("Invalid regex 'query' for search_modules"), failure.message!!)
        assertTrue(failure.message!!.contains("substring"), "the message must name the way out")
    }

    @Test
    fun caseSensitivityCanBeDemandedWhenItMatters() = runTest {
        fetchAll()

        assertEquals(emptyList(), service.searchModules(query = "CALL_FORM", ignoreCase = false).hits)
        assertEquals(listOf(entry), service.searchModules(query = "CALL_FORM").hits.modules())
    }

    /** An unknown scope is an argument error that lists what is accepted, not a silent empty page. */
    @Test
    fun anUnknownScopeIsRejectedWithTheAcceptedValues() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            service.searchModules(query = ":GLOBAL", scope = "everything")
        }

        assertTrue(failure.message!!.contains("plsql"), failure.message!!)
    }

    /**
     * The response a client receives has to fit the tool-output budget: 25,000 tokens is roughly
     * 100 KB, and this is the tool whose reach is the whole cache.
     */
    @Test
    fun aFullPageStaysWellUnderTheToolOutputBudget() = runTest {
        fetchAll()

        val page = service.searchModules(query = "=", scope = "all", maxResults = MAX_SEARCH_HITS)

        val serialized = toolJson.encodeToString(page)
        assertTrue(serialized.length < 100_000, "a full page serialized to ${serialized.length} chars")
    }
}
