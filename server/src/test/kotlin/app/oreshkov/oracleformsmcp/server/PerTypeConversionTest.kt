package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `--compile-command` alone converts `.pll` libraries while forms stay in copy-mode, so whether a
 * module's binary or its pre-converted text form is consumed — and fingerprinted — has to be decided
 * per module type. Decided once for the whole server, a copy-mode form would be fingerprinted
 * against its `.fmb` and never go stale when its XML is re-exported.
 */
class PerTypeConversionTest {

    private val temp: Path = Files.createTempDirectory("per-type-conversion-test")
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun file(name: String, content: String): Path = temp.resolve(name).also { it.writeText(content) }

    private val ordersBinary = file("ORDERS.fmb", "binary form")
    private val ordersXml = file("orders_fmb.xml", "<Module/>")
    private val utilsBinary = file("UTILS.pll", "binary library")

    /** Converts libraries from their binary, and only copies everything else. */
    private val service = fakeService(
        scanner = FakeScanner(
            listOf(
                ScannedModule(ordersKey, binaryPath = ordersBinary.toString(), preConvertedPath = ordersXml.toString()),
                ScannedModule(utilsKey, binaryPath = utilsBinary.toString()),
            ),
        ),
        cacheRoot = temp.resolve("cache"),
        converter = CopyingConverter(binaryTypes = setOf(ModuleType.LIBRARY)),
    )

    @Test
    fun aCopyModeFormIsFingerprintedAgainstItsTextForm() = runTest {
        service.fetchModule(ordersKey)

        assertEquals(ordersXml.toString(), service.index(ordersKey).sourceFile)

        // Touching the binary is not a change to what was served; re-exporting the XML is.
        ordersBinary.writeText("binary form, rebuilt")
        assertEquals(ModuleStatus.CACHED, statusOf(ordersKey))
        ordersXml.writeText("<Module reexported='yes'/>")
        assertEquals(ModuleStatus.STALE, statusOf(ordersKey))
    }

    @Test
    fun aConvertedLibraryIsFingerprintedAgainstItsBinary() = runTest {
        service.fetchModule(utilsKey)

        assertEquals(utilsBinary.toString(), service.index(utilsKey).sourceFile)
        utilsBinary.writeText("binary library, recompiled")
        assertEquals(ModuleStatus.STALE, statusOf(utilsKey))
    }

    @Test
    fun listModulesReportsConversionWhenAnyTypeConverts() = runTest {
        assertTrue(service.listModules().oracleHomeConversion)
    }

    private suspend fun statusOf(key: ModuleKey): ModuleStatus =
        service.listModules().modules.single { it.module == key }.status
}
