package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.core.ConversionFailedException
import app.oreshkov.oracleformsmcp.core.ConversionTimeoutException
import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class OracleToolsModuleConverterTest {

    private val temp: Path = Files.createTempDirectory("oracle-tools-test")
    private val oracleHome: Path = temp.resolve("oracle-home")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val targetDir: Path = temp.resolve("target")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun converter(timeoutSeconds: Int = 30) =
        OracleToolsModuleConverter(oracleHome, formsDir, timeoutSeconds.seconds)

    private fun fakeBinary(name: String): Path = formsDir.resolve(name).also { it.writeText("binary") }

    @Test
    fun convertsFormViaFrmf2xml() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        FakeOracleHome.copyingTool(oracleHome, "frmf2xml", fixture, "orders_fmb.xml")
        val binary = fakeBinary("ORDERS.fmb")

        val output = converter().convert(
            ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString(),
        )

        assertEquals("orders_fmb.xml", Path.of(output).name)
        assertTrue(Path.of(output).readText().contains("FormModule"))
    }

    @Test
    fun convertsLibraryViaFrmcmp() = runTest {
        val fixture = copyFixture("utils.pld", temp)
        FakeOracleHome.copyingTool(oracleHome, "frmcmp_batch", fixture, "utils.pld")
        val binary = fakeBinary("UTILS.pll")

        val output = converter().convert(
            ModuleKey.of("utils", ModuleType.LIBRARY), binary.toString(), targetDir.toString(),
        )

        assertEquals("utils.pld", Path.of(output).name)
        assertTrue(Path.of(output).readText().contains("PROCEDURE log_message"))
    }

    @Test
    fun missingToolReportsClearError() = runTest {
        FakeOracleHome.binDir(oracleHome) // bin exists but is empty
        val binary = fakeBinary("ORDERS.fmb")

        val error = assertFailsWith<ConverterNotFoundException> {
            converter().convert(ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString())
        }
        assertTrue(error.message!!.contains("ORACLE_HOME"))
        assertTrue(error.message!!.contains("frmf2xml"))
    }

    @Test
    fun failingToolSurfacesOutputTail() = runTest {
        FakeOracleHome.failingTool(oracleHome, "frmf2xml", "FRM-99999: boom")
        val binary = fakeBinary("ORDERS.fmb")

        val error = assertFailsWith<ConversionFailedException> {
            converter().convert(ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString())
        }
        assertTrue(error.message!!.contains("FRM-99999: boom"))
    }

    @Test
    fun hangingToolIsKilledOnTimeout() = runTest {
        FakeOracleHome.sleepingTool(oracleHome, "frmf2xml", seconds = 20)
        val binary = fakeBinary("ORDERS.fmb")

        assertFailsWith<ConversionTimeoutException> {
            converter(timeoutSeconds = 1).convert(
                ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString(),
            )
        }
    }

    @Test
    fun alreadyConvertedSourceIsJustCopied() = runTest {
        // No tools needed at all when the scanner only found the text form.
        val preConverted = copyFixture("orders_fmb.xml", formsDir)

        val output = converter().convert(
            ModuleKey.of("orders", ModuleType.FORM), preConverted.toString(), targetDir.toString(),
        )
        assertTrue(Path.of(output).readText().contains("FormModule"))
    }
}
