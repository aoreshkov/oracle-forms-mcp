package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * `read_source`'s `file` argument is a cache-relative path taken verbatim from the caller, and the
 * containment check in `resolveRef` is the only thing between it and the host file system.
 *
 * Each case plants a real file at the place the escaping path would land, so a weakened check is
 * not a different error but a successful read of that file — which is what the test would see.
 */
class ReadSourceContainmentTest {

    private val temp: Path = Files.createTempDirectory("read-source-containment-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)

    init {
        copyFixture("orders_fmb.xml")
        copyFixture("utils.pld")
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    private fun service(cache: OnDiskModuleCache, convertedDir: Path? = null) = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = cache,
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        convertedDir = convertedDir,
    )

    /** Writes a file where [escape], resolved against [root], lands — the file a broken guard would serve. */
    private fun plantAt(root: Path, escape: String): Path {
        val target = root.resolve(escape).normalize()
        check(!target.startsWith(root.normalize())) { "'$escape' must leave $root for the case to mean anything" }
        target.parent.createDirectories()
        target.writeText("OUTSIDE THE CACHE")
        return target
    }

    private suspend fun assertRejected(service: FormsService, file: String) {
        val failure = assertFailsWith<IllegalArgumentException>(file) { service.readSource(ordersKey, file) }
        assertContains(failure.message.orEmpty(), "escapes", message = file)
    }

    @Test
    fun aPathLeavingTheModuleEntryIsRejected() = runTest {
        val cache = OnDiskModuleCache(temp.resolve("cache"))
        val service = service(cache)
        service.fetchModule(ordersKey)
        val moduleDir = Path.of(cache.moduleDir(ordersKey))

        val outside = plantAt(moduleDir, "../../outside.txt")
        assertRejected(service, "../../outside.txt")
        assertRejected(service, outside.toString())
        assertEquals("OUTSIDE THE CACHE", Files.readString(outside), "the planted file must be untouched")
    }

    /** Leaving the entry is an escape even when the path lands inside the cache, in another module's. */
    @Test
    fun aPathIntoAnotherModulesEntryIsRejected() = runTest {
        val cache = OnDiskModuleCache(temp.resolve("cache"))
        val service = service(cache)
        service.fetchModule(ordersKey)
        service.fetchModule(utilsKey)
        val utilsDir = Path.of(cache.moduleDir(utilsKey)).fileName

        assertRejected(service, "../$utilsDir/converted/utils.pld")
    }

    @Test
    fun aConvertedPathLeavingTheConvertedDirIsRejected() = runTest {
        val cache = OnDiskModuleCache(temp.resolve("cache"))
        val service = service(cache)
        service.fetchModule(ordersKey)
        plantAt(Path.of(cache.moduleDir(ordersKey)).resolve("converted"), "../../outside.txt")

        assertRejected(service, "converted/../../outside.txt")
    }

    /**
     * A relocated `converted/` is checked against the directory it was relocated to — the prefix
     * swaps the root, and the swapped root needs the same containment as the one it replaced.
     */
    @Test
    fun aConvertedPathLeavingARelocatedConvertedDirIsRejected() = runTest {
        val convertedDir = Files.createDirectories(temp.resolve("xml").resolve("forms"))
        val service = service(OnDiskModuleCache(temp.resolve("cache-relocated")), convertedDir)
        service.fetchModule(ordersKey)
        val outside = plantAt(convertedDir, "../../outside.txt")

        assertRejected(service, "converted/../../outside.txt")
        assertRejected(service, outside.toString())
        // The text form itself is still served from there: the guard rejects escapes, not the root.
        assertContains(service.readSource(ordersKey, "converted/orders_fmb.xml", 1, 1).text, "<")
    }
}
