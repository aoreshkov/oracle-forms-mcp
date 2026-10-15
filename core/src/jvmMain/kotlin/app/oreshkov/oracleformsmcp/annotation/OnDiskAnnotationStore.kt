package app.oreshkov.oracleformsmcp.annotation

import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.core.AnnotationsFormatTooNewException
import app.oreshkov.oracleformsmcp.core.AnnotationsUnreadableException
import app.oreshkov.oracleformsmcp.io.moveRetrying
import app.oreshkov.oracleformsmcp.io.writeTextAtomically
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.CURRENT_ANNOTATIONS_FORMAT_VERSION
import app.oreshkov.oracleformsmcp.model.ModuleAnnotations
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.Relation
import co.touchlab.kermit.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AnnotationStore] backed by one JSON file per [ModuleKey] (`<root>/<NAME.ext>.json`) holding a
 * serialized [ModuleAnnotations]. The [root] is intentionally distinct from the module cache tree
 * (see [defaultRoot]) so annotations outlive `fetch_module` re-conversions and per-module cache
 * eviction.
 *
 * Writes are read-modify-write, serialized by a [Mutex] so concurrent tool calls in the same
 * process cannot lose an update, and each one replaces the file atomically, so a concurrent read
 * or a crash never sees half of one.
 *
 * Reads and writes treat an undecodable file differently. A read degrades it to "no annotations"
 * rather than failing a view. A write cannot: it rewrites the whole file from what it read, so a
 * lenient read would replace every stored annotation with the one being added — and annotations
 * are asserted, so nothing could rebuild them. The write path therefore moves such a file aside
 * and fails the call with [AnnotationsUnreadableException], and refuses outright to rewrite a file
 * a newer build wrote ([AnnotationsFormatTooNewException]).
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
                val current = readForWrite(module)
                val next = current.copy(
                    annotations = current.annotations.filterNot { it.id == id },
                    relations = current.relations.filterNot { it.id == id },
                )
                val removed = next.annotations.size != current.annotations.size ||
                    next.relations.size != current.relations.size
                if (removed) write(module, next)
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
        withContext(Dispatchers.IO) { write(module, transform(readForWrite(module))) }
    }

    /**
     * Reads for a view (not locked): the mutex serializes writers, and each write replaces the
     * file atomically, so a concurrent read sees a whole file. An undecodable one reads as empty.
     */
    private fun read(module: ModuleKey): ModuleAnnotations {
        val file = fileFor(module)
        if (!file.exists()) return ModuleAnnotations(module)
        return runCatching { json.decodeFromString<ModuleAnnotations>(file.readText()) }
            .onFailure { log.w(it) { "Corrupt annotations for $module at $file; treating as empty" } }
            .getOrDefault(ModuleAnnotations(module))
    }

    /**
     * Reads for a write, under [writeMutex]. Fails instead of degrading: the caller rewrites the
     * whole file from what this returns. The stamp is checked before the full decode, because a
     * newer build's file may fail to decode here for the very reason it must not be touched.
     * An IO failure propagates as it is — the file may be fine, so nothing is moved.
     *
     * The file's location, not the key written inside it, says which module it belongs to: a
     * copied or renamed file would otherwise send the rewrite to a file nobody checked.
     */
    private suspend fun readForWrite(module: ModuleKey): ModuleAnnotations {
        val file = fileFor(module)
        if (!file.exists()) return ModuleAnnotations(module)
        val text = file.readText()
        val stamp = runCatching { json.decodeFromString<FormatStamp>(text).formatVersion }.getOrNull()
        if (stamp != null && stamp > CURRENT_ANNOTATIONS_FORMAT_VERSION) {
            throw AnnotationsFormatTooNewException(module, stamp, CURRENT_ANNOTATIONS_FORMAT_VERSION)
        }
        val decoded = try {
            json.decodeFromString<ModuleAnnotations>(text)
        } catch (e: IllegalArgumentException) {
            val keptAs = file.resolveSibling("${file.name}.corrupt-${System.currentTimeMillis()}")
            moveRetrying(file, keptAs)
            log.w(e) { "Unreadable annotations for $module; moved aside to $keptAs" }
            throw AnnotationsUnreadableException(module, keptAs.name)
        }
        return decoded.copy(module = module)
    }

    private suspend fun write(module: ModuleKey, value: ModuleAnnotations) {
        root.createDirectories()
        val stamped = value.copy(module = module, formatVersion = CURRENT_ANNOTATIONS_FORMAT_VERSION)
        writeTextAtomically(fileFor(module), json.encodeToString(stamped))
    }

    /** Just the stamp of a stored file, readable whatever else the file holds. */
    @Serializable
    private data class FormatStamp(val formatVersion: Int = 0)

    public companion object {
        /**
         * Default store root: a sibling `annotations/` subtree under the app's data directory,
         * reusing [OnDiskModuleCache.defaultCacheRoot]'s platform base. It sits outside every
         * per-module cache directory, so nothing the module cache writes or evicts touches it.
         */
        public fun defaultRoot(): Path = OnDiskModuleCache.defaultCacheRoot().resolve("annotations")
    }
}
