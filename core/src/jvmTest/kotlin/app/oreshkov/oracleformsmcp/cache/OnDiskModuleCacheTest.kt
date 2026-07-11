package app.oreshkov.oracleformsmcp.cache

import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class OnDiskModuleCacheTest {

    private val root: Path = Files.createTempDirectory("forms-cache-test")
    private val cache = OnDiskModuleCache(root)

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun index(key: ModuleKey) = ModuleIndex(
        key = key,
        sourceFile = "C:/forms/$key",
        fingerprint = ModuleFingerprint(1, 2, "abc"),
        convertedFile = "converted/x.xml",
        parsedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun roundTripsAnIndex() = runTest {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        cache.putIndex(index(key))
        assertEquals(index(key), cache.get(key))
        assertEquals(listOf(key), cache.list())
        assertTrue(cache.size() > 0)
    }

    @Test
    fun missOnUnknownKey() = runTest {
        assertNull(cache.get(ModuleKey.of("nope", ModuleType.MENU)))
    }

    @Test
    fun corruptIndexDegradesToMiss() = runTest {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        CacheLayout.moduleDir(root, key).createDirectories()
            .resolve(CacheLayout.INDEX_FILE).writeText("{ not json !!")
        assertNull(cache.get(key))
    }

    @Test
    fun clearRemovesEntry() = runTest {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        cache.putIndex(index(key))
        cache.clear(key)
        assertNull(cache.get(key))
        assertEquals(emptyList(), cache.list())
    }

    @Test
    fun listIgnoresForeignDirectories() = runTest {
        root.resolve("not-a-module").createDirectories()
        cache.putIndex(index(ModuleKey.of("utils", ModuleType.LIBRARY)))
        assertEquals(listOf(ModuleKey.of("utils", ModuleType.LIBRARY)), cache.list())
    }
}
