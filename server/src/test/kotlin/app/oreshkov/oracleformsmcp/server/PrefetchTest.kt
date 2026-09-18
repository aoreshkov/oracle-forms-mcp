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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `--prefetch` is what makes `search_modules` cover a directory instead of reporting three thousand
 * modules as `skippedNotCached`. It has to filter the way `list_modules` does, survive a module
 * that will not convert, and cost next to nothing the second time.
 */
class PrefetchTest {

    private val temp: Path = Files.createTempDirectory("prefetch-test")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun module(name: String, type: ModuleType = ModuleType.FORM, exists: Boolean = true): ScannedModule {
        val file = temp.resolve("${name.lowercase()}${type.convertedSuffix}")
        if (exists) file.writeText("<Module/>")
        return ScannedModule(key = ModuleKey.of(name, type), preConvertedPath = file.toString())
    }

    @Test
    fun fetchesWhatMatchesRecordsFailuresAndGoesOn() = runTest {
        val service = fakeService(
            scanner = FakeScanner(
                listOf(
                    module("ORDERS"),
                    module("ORDER_LINES"),
                    module("ORDER_BROKEN", exists = false),
                    module("CLAIMS"),
                    module("ORDER_LIB", ModuleType.LIBRARY),
                ),
            ),
            cacheRoot = temp.resolve("cache"),
        )
        val seen = mutableListOf<Pair<Int, Int>>()
        val outcomes = service.prefetch(pattern = "order", type = ModuleType.FORM) { done, total, _ ->
            seen += done to total
        }

        assertEquals(listOf("ORDERS", "ORDER_BROKEN", "ORDER_LINES"), outcomes.map { it.module.name })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen, "each outcome is reported as it lands")
        val broken = outcomes.single { it.module.name == "ORDER_BROKEN" }
        assertNull(broken.summary)
        assertNotNull(broken.error)
        assertTrue(outcomes.filter { it.error == null }.all { it.summary?.fromCache == false })

        val again = service.prefetch(pattern = "order", type = ModuleType.FORM)
        assertTrue(
            again.filter { it.error == null }.all { it.summary?.fromCache == true },
            "a rerun finds what the first run fetched already current",
        )
    }

    @Test
    fun noPatternMeansEveryModule() = runTest {
        val service = fakeService(
            scanner = FakeScanner(listOf(module("A"), module("B", ModuleType.MENU))),
            cacheRoot = temp.resolve("cache"),
        )
        assertEquals(2, service.prefetch().size)
    }
}
