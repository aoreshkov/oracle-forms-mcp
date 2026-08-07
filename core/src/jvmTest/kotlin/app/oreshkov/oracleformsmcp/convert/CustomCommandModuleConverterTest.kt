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

class CustomCommandModuleConverterTest {

    private val temp: Path = Files.createTempDirectory("custom-command-test")
    private val scriptDir: Path = temp.resolve("scripts")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val targetDir: Path = temp.resolve("target")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun converter(command: Path, timeoutSeconds: Int = 30) =
        CustomCommandModuleConverter(command, formsDir, timeoutSeconds.seconds)

    private fun fakeBinary(name: String): Path = formsDir.resolve(name).also { it.writeText("binary") }

    /** A stub converter that drops [fixture] into its working directory under [outputName]. */
    private fun copyingScript(fixture: Path, outputName: String): Path = FakeOracleHome.stubScript(
        scriptDir,
        "convert",
        batchLines = listOf("copy /Y \"$fixture\" \"%CD%\\$outputName\" >nul"),
        shellLines = listOf("cp \"$fixture\" \"\$PWD/$outputName\""),
    )

    @Test
    fun convertsFormWithTheConfiguredCommand() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        val script = copyingScript(fixture, "orders_fmb.xml")

        val output = converter(script).convert(
            ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
        )

        assertEquals("orders_fmb.xml", Path.of(output).name)
        assertTrue(Path.of(output).readText().contains("FormModule"))
    }

    @Test
    fun convertsLibraryWithTheConfiguredCommand() = runTest {
        val fixture = copyFixture("utils.pld", temp)
        val script = copyingScript(fixture, "utils.pld")

        val output = converter(script).convert(
            ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
        )

        assertEquals("utils.pld", Path.of(output).name)
    }

    /** A custom converter need not copy frmf2xml's `_fmb` basename mangling. */
    @Test
    fun acceptsOutputThatDoesNotUseTheOracleSuffix() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        val script = copyingScript(fixture, "ORDERS.xml")

        val output = converter(script).convert(
            ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
        )

        assertEquals("ORDERS.xml", Path.of(output).name)
    }

    @Test
    fun failsWithAnActionableMessageWhenTheCommandDoesNotExist() = runTest {
        val missing = scriptDir.resolve("nope")

        val error = assertFailsWith<ConverterNotFoundException> {
            converter(missing).convert(
                ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
            )
        }

        assertTrue("--convert-command" in error.message!!)
        assertTrue("ORACLE_HOME" in error.message!!)
    }

    /** Exit codes are unreliable in this family of tools — success is judged by the output file. */
    @Test
    fun failsWhenTheCommandProducesNothing() = runTest {
        val script = FakeOracleHome.stubScript(
            scriptDir,
            "convert",
            batchLines = listOf("echo nothing to do", "exit /b 0"),
            shellLines = listOf("echo nothing to do", "exit 0"),
        )

        val error = assertFailsWith<ConversionFailedException> {
            converter(script).convert(
                ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
            )
        }

        assertTrue("no output file" in error.message!!)
    }

    @Test
    fun killsACommandThatOverrunsTheTimeout() = runTest {
        val script = FakeOracleHome.stubScript(
            scriptDir,
            "convert",
            batchLines = listOf("ping -n 20 127.0.0.1 >nul"),
            shellLines = listOf("sleep 19"),
        )

        assertFailsWith<ConversionTimeoutException> {
            converter(script, timeoutSeconds = 1).convert(
                ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
            )
        }
    }

    /** A directory holding only the text form needs no converter at all. */
    @Test
    fun copiesAlreadyConvertedInputWithoutRunningTheCommand() = runTest {
        val fixture = copyFixture("orders_fmb.xml", formsDir)
        val missing = scriptDir.resolve("never-invoked")

        val output = converter(missing).convert(
            ModuleKey.of("orders", ModuleType.FORM), fixture.toString(), targetDir.toString(),
        )

        assertEquals("orders_fmb.xml", Path.of(output).name)
    }
}
