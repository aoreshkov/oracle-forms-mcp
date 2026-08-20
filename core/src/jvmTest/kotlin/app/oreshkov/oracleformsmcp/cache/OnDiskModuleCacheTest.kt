package app.oreshkov.oracleformsmcp.cache

import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /**
     * Tool handlers run concurrently (MCP SDK 0.15+), so a read can land mid-write. With a plain
     * `writeText` the reader sees a truncated file and [OnDiskModuleCache.get] degrades it to a
     * miss, silently discarding a valid entry. The two indexes differ hugely in size so a partial
     * write of the large one would be unmistakable.
     */
    @Test
    fun putIndexIsAtomicUnderConcurrentReads() = runBlocking {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        val small = index(key)
        val large = index(key).copy(sourceFile = "C:/forms/" + "x".repeat(200_000))
        cache.putIndex(small)

        val writer = launch(Dispatchers.IO) {
            repeat(60) { cache.putIndex(if (it % 2 == 0) large else small) }
        }
        val reads = mutableListOf<ModuleIndex?>()
        while (writer.isActive) reads += cache.get(key)
        writer.join()

        assertTrue(reads.size > 10, "expected the reader to observe many writes, got ${reads.size}")
        // Never a miss, and never a torn document: every read is one of the two whole indexes.
        reads.forEachIndexed { i, read ->
            assertNotNull(read, "read #$i saw a partially written index")
            assertTrue(read == small || read == large, "read #$i saw a torn index")
        }
    }

    @Test
    fun putIndexLeavesNoTempFiles() = runTest {
        val key = ModuleKey.of("orders", ModuleType.FORM)
        cache.putIndex(index(key))
        assertEquals(
            listOf(CacheLayout.INDEX_FILE),
            CacheLayout.moduleDir(root, key).listDirectoryEntries().map { it.name },
        )
    }
}
