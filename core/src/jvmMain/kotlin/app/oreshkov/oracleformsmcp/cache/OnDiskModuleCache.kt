@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.oracleformsmcp.cache

import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import co.touchlab.kermit.Logger
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Retry budget for the atomic index replace; see `OnDiskModuleCache.replaceAtomically`. */
private const val ATOMIC_MOVE_ATTEMPTS = 20
private const val ATOMIC_MOVE_RETRY_DELAY_MS = 5L

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
            val target = dir.resolve(CacheLayout.INDEX_FILE)
            // Write-then-rename. Tool handlers run concurrently (MCP SDK 0.15+), so a reader can
            // hit the index file while a fetch is writing it; a partial file would surface as the
            // corrupt-index miss in [get] and silently discard a good entry.
            val tmp = Files.createTempFile(dir, "index", ".json.tmp")
            try {
                tmp.writeText(json.encodeToString(index))
                replaceAtomically(tmp, target)
            } finally {
                tmp.deleteIfExists()
            }
        }

    /**
     * Renames [tmp] over [target] without ever exposing a moment where [target] is missing or
     * half-written.
     *
     * Windows fails an atomic replace with an access error while any reader holds [target] open,
     * and those readers are frequent now that tool handlers run concurrently. The naive recovery —
     * a plain [StandardCopyOption.REPLACE_EXISTING] move — is delete-then-rename on Windows, which
     * is precisely the torn state being avoided: a concurrent [get] sees no file and reports a
     * cache miss. Reader handles are short (one `readText`), so retry instead, and reserve the
     * non-atomic move for filesystems that genuinely cannot do atomic renames.
     */
    private suspend fun replaceAtomically(tmp: Path, target: Path) {
        var lastFailure: Throwable? = null
        repeat(ATOMIC_MOVE_ATTEMPTS) { attempt ->
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: AtomicMoveNotSupportedException) {
                log.d(e) { "Atomic move unsupported at $target; falling back to a plain replace" }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: IOException) {
                lastFailure = e
                delay(ATOMIC_MOVE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw IOException("Could not replace $target after $ATOMIC_MOVE_ATTEMPTS attempts", lastFailure)
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
