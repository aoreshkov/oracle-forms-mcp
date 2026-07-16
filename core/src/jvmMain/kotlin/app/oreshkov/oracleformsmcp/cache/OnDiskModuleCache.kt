@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.oracleformsmcp.cache

import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Shared on-disk layout under the cache root, used by cache, converter, and parser so a
 * conversion lands directly in its cached location:
 * `<root>/<NAME.ext>/{converted/, plsql/triggers/, plsql/program-units/, plsql/menu-items/, index.json}`.
 * One directory per [ModuleKey] canonical form (`ORDERS.fmb`) keeps the tree flat and
 * human-browsable.
 */
internal object CacheLayout {
    const val INDEX_FILE: String = "index.json"
    const val CONVERTED_DIR: String = "converted"
    const val PLSQL_DIR: String = "plsql"
    const val TRIGGERS_DIR: String = "triggers"
    const val PROGRAM_UNITS_DIR: String = "program-units"

    fun moduleDir(root: Path, key: ModuleKey): Path = root.resolve(key.toString())
}

/**
 * [ModuleCache] backed by a plain directory tree (see [CacheLayout]). All IO runs on
 * [Dispatchers.IO]; corrupt cache entries degrade to a miss instead of failing.
 */
public class OnDiskModuleCache(
    private val root: Path = defaultCacheRoot(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) : ModuleCache {

    private val log = Logger.withTag("OnDiskModuleCache")

    override suspend fun get(key: ModuleKey): ModuleIndex? =
        withContext(Dispatchers.IO) {
            val file = CacheLayout.moduleDir(root, key).resolve(CacheLayout.INDEX_FILE)
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString<ModuleIndex>(file.readText()) }
                .onFailure { log.w(it) { "Corrupt index for $key at $file; treating as miss" } }
                .getOrNull()
        }

    override suspend fun putIndex(index: ModuleIndex): Unit =
        withContext(Dispatchers.IO) {
            val dir = CacheLayout.moduleDir(root, index.key).createDirectories()
            dir.resolve(CacheLayout.INDEX_FILE).writeText(json.encodeToString(index))
        }

    override suspend fun list(): List<ModuleKey> =
        withContext(Dispatchers.IO) {
            if (!root.exists()) return@withContext emptyList()
            root.listDirectoryEntries()
                .filter { it.isDirectory() }
                .mapNotNull { ModuleKey.parseOrNull(it.name) }
                .sortedBy { it.toString() }
        }

    override suspend fun clear(key: ModuleKey): Unit =
        withContext(Dispatchers.IO) {
            CacheLayout.moduleDir(root, key).deleteRecursively()
        }

    override suspend fun size(): Long =
        withContext(Dispatchers.IO) {
            if (!root.exists()) return@withContext 0L
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
            }
        }

    override fun moduleDir(key: ModuleKey): String =
        CacheLayout.moduleDir(root, key).toAbsolutePath().normalize().toString()

    public companion object {
        /**
         * Platform cache dir + `oracle-forms-mcp`: `%LOCALAPPDATA%` on Windows,
         * `~/Library/Caches` on macOS, `$XDG_CACHE_HOME` (or `~/.cache`) elsewhere.
         */
        public fun defaultCacheRoot(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            val base = when {
                os.contains("win") ->
                    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: Path.of(home, "AppData", "Local")
                os.contains("mac") -> Path.of(home, "Library", "Caches")
                else ->
                    System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: Path.of(home, ".cache")
            }
            return base.resolve("oracle-forms-mcp")
        }
    }
}
