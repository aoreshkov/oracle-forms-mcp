package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.ModuleParser
import app.oreshkov.oracleformsmcp.io.Fingerprints
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.ModuleAnnotations
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.Relation
import app.oreshkov.oracleformsmcp.model.ScannedModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.time.Instant

/*
 * Offline collaborators for server tests: a scanner serving a canned module list, a converter
 * that just copies, a parser returning a canned index, and an in-memory cache.
 */

internal class FakeScanner(var modules: List<ScannedModule> = emptyList()) : FormsDirectoryScanner {
    override suspend fun scan(): List<ScannedModule> = modules
}

internal class CopyingConverter : ModuleConverter {
    override val description: String = "fake copy converter"
    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
        val source = Path.of(sourcePath)
        val target = Path.of(targetDir).createDirectories().resolve(source.name)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target.toString()
    }
}

internal class FakeParser(
    var onParse: (ModuleKey, String) -> ModuleIndex = { key, converted -> minimalIndex(key, converted) },
) : ModuleParser {
    override fun parse(key: ModuleKey, convertedFile: String, moduleCacheDir: String): ModuleIndex =
        onParse(key, convertedFile)
}

internal class InMemoryCache(private val root: Path) : ModuleCache {
    val indexes = linkedMapOf<ModuleKey, ModuleIndex>()
    override suspend fun get(key: ModuleKey): ModuleIndex? = indexes[key]
    override suspend fun putIndex(index: ModuleIndex) { indexes[index.key] = index }
    override suspend fun list(): List<ModuleKey> = indexes.keys.toList()
    override suspend fun clear(key: ModuleKey) { indexes.remove(key) }
    override suspend fun size(): Long = 0
    override fun moduleDir(key: ModuleKey): String =
        root.resolve(key.toString()).toString()
}

internal class InMemoryAnnotationStore : AnnotationStore {
    private val byModule = linkedMapOf<ModuleKey, ModuleAnnotations>()

    private fun current(module: ModuleKey) = byModule[module] ?: ModuleAnnotations(module)

    override suspend fun addAnnotation(annotation: Annotation) {
        val module = annotation.target.module
        val cur = current(module)
        byModule[module] = cur.copy(
            annotations = cur.annotations.filterNot { it.id == annotation.id } + annotation,
        )
    }

    override suspend fun addRelation(relation: Relation) {
        val module = relation.from.module
        val cur = current(module)
        byModule[module] = cur.copy(relations = cur.relations.filterNot { it.id == relation.id } + relation)
    }

    override suspend fun forModule(module: ModuleKey): ModuleAnnotations = current(module)

    override suspend fun remove(module: ModuleKey, id: String): Boolean {
        val cur = current(module)
        val next = cur.copy(
            annotations = cur.annotations.filterNot { it.id == id },
            relations = cur.relations.filterNot { it.id == id },
        )
        val removed = next.annotations.size != cur.annotations.size || next.relations.size != cur.relations.size
        byModule[module] = next
        return removed
    }

    override suspend fun clear(module: ModuleKey) {
        byModule.remove(module)
    }
}

internal fun minimalIndex(key: ModuleKey, sourceFile: String): ModuleIndex = ModuleIndex(
    key = key,
    sourceFile = sourceFile,
    fingerprint = Fingerprints.of(Path.of(sourceFile)),
    convertedFile = Path.of(sourceFile).name,
    parsedAt = Instant.fromEpochMilliseconds(0),
)

/** A [FormsService] wired entirely to fakes — no external processes, no real parsing. */
internal fun fakeService(
    scanner: FakeScanner = FakeScanner(),
    cacheRoot: Path = Files.createTempDirectory("fake-service-cache"),
    oracleConversion: Boolean = false,
    annotationStore: AnnotationStore = InMemoryAnnotationStore(),
): FormsService = FormsService(
    scanner = scanner,
    converter = CopyingConverter(),
    parser = FakeParser(),
    cache = InMemoryCache(cacheRoot),
    annotationStore = annotationStore,
    formsDir = Path.of("."),
    oracleConversion = oracleConversion,
)
