package app.oreshkov.oracleformsmcp.annotation

import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.ModuleAnnotations
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.Relation
import co.touchlab.kermit.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [AnnotationStore] backed by one JSON file per [ModuleKey] (`<root>/<NAME.ext>.json`) holding a
 * serialized [ModuleAnnotations]. The [root] is intentionally distinct from the module cache tree
 * (see [defaultRoot]) so annotations outlive `fetch_module` re-conversions and per-module cache
 * eviction. Corrupt files degrade to "no annotations" rather than failing a read.
 *
 * Writes are read-modify-write, serialized by a [Mutex] so concurrent tool calls in the same
 * process cannot lose an update.
 */
public class OnDiskAnnotationStore(
    private val root: Path = defaultRoot(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) : AnnotationStore {

    private val log = Logger.withTag("OnDiskAnnotationStore")
    private val writeMutex = Mutex()

    private fun fileFor(module: ModuleKey): Path = root.resolve("$module.json")

    override suspend fun forModule(module: ModuleKey): ModuleAnnotations =
        withContext(Dispatchers.IO) { read(module) }

    override suspend fun addAnnotation(annotation: Annotation): Unit =
        update(annotation.target.module) { current ->
            current.copy(
                annotations = current.annotations.filterNot { it.id == annotation.id } + annotation,
            )
        }

    override suspend fun addRelation(relation: Relation): Unit =
        update(relation.from.module) { current ->
            current.copy(
                relations = current.relations.filterNot { it.id == relation.id } + relation,
            )
        }

    override suspend fun remove(module: ModuleKey, id: String): Boolean =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = read(module)
                val next = current.copy(
                    annotations = current.annotations.filterNot { it.id == id },
                    relations = current.relations.filterNot { it.id == id },
                )
                val removed = next.annotations.size != current.annotations.size ||
                    next.relations.size != current.relations.size
                if (removed) write(next)
                removed
            }
        }

    override suspend fun clear(module: ModuleKey): Unit =
        writeMutex.withLock {
            withContext(Dispatchers.IO) { fileFor(module).deleteIfExists() }
        }

    private suspend fun update(
        module: ModuleKey,
        transform: (ModuleAnnotations) -> ModuleAnnotations,
    ): Unit = writeMutex.withLock {
        withContext(Dispatchers.IO) { write(transform(read(module))) }
    }

    /** Reads (not locked): the mutex serializes writers; a concurrent read sees a whole file. */
    private fun read(module: ModuleKey): ModuleAnnotations {
        val file = fileFor(module)
        if (!file.exists()) return ModuleAnnotations(module)
        return runCatching { json.decodeFromString<ModuleAnnotations>(file.readText()) }
            .onFailure { log.w(it) { "Corrupt annotations for $module at $file; treating as empty" } }
            .getOrDefault(ModuleAnnotations(module))
    }

    private fun write(value: ModuleAnnotations) {
        root.createDirectories()
        fileFor(value.module).writeText(json.encodeToString(value))
    }

    public companion object {
        /**
         * Default store root: a sibling `annotations/` subtree under the app's data directory,
         * reusing [OnDiskModuleCache.defaultCacheRoot]'s platform base. It sits outside every
         * per-module cache directory, so nothing the module cache writes or evicts touches it.
         */
        public fun defaultRoot(): Path = OnDiskModuleCache.defaultCacheRoot().resolve("annotations")
    }
}
