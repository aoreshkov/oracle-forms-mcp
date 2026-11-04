package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A cache entry is current as long as its *source file* is unchanged, which says nothing about
 * whether the conversion that produced it was right: `frmf2xml` without the forms directory on
 * `FORMS_PATH` succeeds and drops every inherited object. Once the setup is fixed, only `force`
 * gets the correct result in without deleting the cache entry by hand (#121).
 */
class ForceFetchTest {

    private val temp: Path = Files.createTempDirectory("force-fetch-test")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun module(name: String): ScannedModule {
        val file = temp.resolve("${name.lowercase()}${ModuleType.FORM.convertedSuffix}")
        file.writeText("<Module/>")
        return ScannedModule(key = ModuleKey.of(name, ModuleType.FORM), preConvertedPath = file.toString())
    }

    @Test
    fun forceConvertsACurrentModuleAgainAndServesTheNewResult() = runTest {
        val converter = CopyingConverter()
        // What the conversion yields changes while the source file does not — a fixed setup.
        var setup = "broken"
        val parser = FakeParser { key, converted -> minimalIndex(key, converted).copy(formsVersion = setup) }
        val service = fakeService(
            scanner = FakeScanner(listOf(module("ORDERS"))),
            cacheRoot = temp.resolve("cache"),
            converter = converter,
            parser = parser,
        )
        val key = ModuleKey.of("ORDERS", ModuleType.FORM)

        assertEquals("broken", service.fetchModule(key).formsVersion)
        setup = "fixed"

        val warm = service.fetchModule(key)
        assertTrue(warm.fromCache)
        assertEquals("broken", warm.formsVersion, "unforced, an unchanged module keeps its first result")
        assertEquals(1, converter.targetDirs.size)

        val forced = service.fetchModule(key, force = true)
        assertFalse(forced.fromCache)
        assertEquals("fixed", forced.formsVersion)
        assertEquals(2, converter.targetDirs.size, "forced, the module is converted again, not only re-parsed")

        assertTrue(service.fetchModule(key).fromCache, "the forced result is the new current entry")
    }

    // That annotations survive a forced fetch is pinned on real fixtures, in
    // FormsServiceIntegrationTest.annotationsAttachToElementsSurviveReindexAndFlagDrift.

    @Test
    fun aForcedPrefetchConvertsEveryMatchingModuleAgain() = runTest {
        val converter = CopyingConverter()
        val service = fakeService(
            scanner = FakeScanner(listOf(module("ORDERS"), module("ORDER_LINES"), module("CLAIMS"))),
            cacheRoot = temp.resolve("cache"),
            converter = converter,
        )
        service.prefetch()
        assertEquals(3, converter.targetDirs.size)

        val forced = service.prefetch(pattern = "order", force = true)

        assertTrue(forced.all { it.error == null && it.summary?.fromCache == false })
        assertEquals(5, converter.targetDirs.size, "only the two matching modules are converted again")
        assertNull(forced.find { it.module.name == "CLAIMS" })
    }
}
