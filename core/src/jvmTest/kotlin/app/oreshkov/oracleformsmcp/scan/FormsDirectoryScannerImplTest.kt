package app.oreshkov.oracleformsmcp.scan

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class FormsDirectoryScannerImplTest {

    private val dir: Path = Files.createTempDirectory("forms-dir-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun pairsBinariesWithPreConvertedSiblings() = runTest {
        dir.resolve("ORDERS.fmb").writeText("binary")
        dir.resolve("orders_fmb.xml").writeText("<Module/>")
        dir.resolve("utils.pld").writeText("PROCEDURE p IS BEGIN NULL; END;")
        dir.resolve("readme.txt").writeText("ignore me")

        val scanned = FormsDirectoryScannerImpl(dir).scan()
        assertEquals(2, scanned.size)

        val orders = scanned.first { it.key == ModuleKey.of("orders", ModuleType.FORM) }
        assertNotNull(orders.binaryPath)
        assertNotNull(orders.preConvertedPath)

        val utils = scanned.first { it.key == ModuleKey.of("utils", ModuleType.LIBRARY) }
        assertNull(utils.binaryPath)
        assertNotNull(utils.preConvertedPath)
    }

    @Test
    fun emptyForMissingDirectory() = runTest {
        assertEquals(emptyList(), FormsDirectoryScannerImpl(dir.resolve("nope")).scan())
    }

    @Test
    fun distinguishesSameNameAcrossTypes() = runTest {
        dir.resolve("ORDERS.fmb").writeText("binary")
        dir.resolve("ORDERS.pll").writeText("binary")

        val keys = FormsDirectoryScannerImpl(dir).scan().map { it.key }
        assertEquals(
            listOf(ModuleKey.of("orders", ModuleType.FORM), ModuleKey.of("orders", ModuleType.LIBRARY)),
            keys,
        )
    }
}
