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

    private fun converter(command: String, timeoutSeconds: Int = 30) =
        CustomCommandModuleConverter(command, formsDir, timeoutSeconds.seconds)

    private fun converter(command: Path, timeoutSeconds: Int = 30) =
        converter(command.toString(), timeoutSeconds)

    private fun fakeBinary(name: String): Path = formsDir.resolve(name).also { it.writeText("binary") }

    private suspend fun convertOrders(converter: CustomCommandModuleConverter): Path =
        Path.of(
            converter.convert(
                ModuleKey.of("orders", ModuleType.FORM),
                fakeBinary("ORDERS.fmb").toString(),
                targetDir.toString(),
            ),
        )

    /** A stub converter that drops [fixture] into its working directory under [outputName]. */
    private fun copyingScript(fixture: Path, outputName: String): Path = FakeOracleHome.stubScript(
        scriptDir,
        "convert",
        batchLines = listOf("copy /Y \"$fixture\" \"%CD%\\$outputName\" >nul"),
        shellLines = listOf("cp \"$fixture\" \"\$PWD/$outputName\""),
    )

    /** As [copyingScript], but also records the argument list it was invoked with. */
    private fun argumentRecordingScript(fixture: Path, dir: Path = scriptDir): Path =
        FakeOracleHome.stubScript(
            dir,
            "convert",
            batchLines = listOf(
                "echo %* >\"%CD%\\args.txt\"",
                "copy /Y \"$fixture\" \"%CD%\\orders_fmb.xml\" >nul",
            ),
            shellLines = listOf(
                "echo \"\$@\" >\"\$PWD/args.txt\"",
                "cp \"$fixture\" \"\$PWD/orders_fmb.xml\"",
            ),
        )

    /** The recorded argv, quote-stripped: `cmd.exe` echoes the quoting `ProcessBuilder` applied. */
    private fun recordedArguments(): String =
        targetDir.resolve("args.txt").readText().replace("\"", "").trim()

    private fun jsonArrayOf(vararg argv: String): String =
        argv.joinToString(prefix = "[", postfix = "]") { "\"${it.replace("\\", "\\\\")}\"" }

    @Test
    fun convertsFormWithTheConfiguredCommand() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        val script = copyingScript(fixture, "orders_fmb.xml")

        val output = convertOrders(converter(script))

        assertEquals("orders_fmb.xml", output.name)
        assertTrue(output.readText().contains("FormModule"))
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

        assertEquals("ORDERS.xml", convertOrders(converter(script)).name)
    }

    /** The whole point of the option: a wrapper is a command *with parameters*, not a bare path. */
    @Test
    fun passesTheConfiguredArgumentsAndAppendsTheModulePath() = runTest {
        val script = argumentRecordingScript(copyFixture("orders_fmb.xml", temp))

        convertOrders(converter("$script -xml OVERWRITE=YES"))

        assertEquals("-xml OVERWRITE=YES ${fakeBinary("ORDERS.fmb")}", recordedArguments())
    }

    @Test
    fun substitutesTheModulePathAtThePlaceholder() = runTest {
        val script = argumentRecordingScript(copyFixture("orders_fmb.xml", temp))

        convertOrders(converter("$script --in {} --xml"))

        assertEquals("--in ${fakeBinary("ORDERS.fmb")} --xml", recordedArguments())
    }

    @Test
    fun acceptsACommandGivenAsAJsonArray() = runTest {
        val script = argumentRecordingScript(copyFixture("orders_fmb.xml", temp))

        convertOrders(converter(jsonArrayOf(script.toString(), "--in={}", "--xml")))

        assertEquals("--in=${fakeBinary("ORDERS.fmb")} --xml", recordedArguments())
    }

    /** A quoted program with spaces stays one argument rather than becoming program + argument. */
    @Test
    fun runsAQuotedProgramPathThatContainsSpaces() = runTest {
        val script = argumentRecordingScript(
            copyFixture("orders_fmb.xml", temp),
            dir = temp.resolve("program files"),
        )

        convertOrders(converter("\"$script\" --xml"))

        assertEquals("--xml ${fakeBinary("ORDERS.fmb")}", recordedArguments())
    }

    /** Configurations written against the older "one executable" contract keep working unquoted. */
    @Test
    fun runsAnUnquotedProgramPathThatContainsSpaces() = runTest {
        val script = argumentRecordingScript(
            copyFixture("orders_fmb.xml", temp),
            dir = temp.resolve("program files"),
        )

        convertOrders(converter(script))

        assertEquals(fakeBinary("ORDERS.fmb").toString(), recordedArguments())
    }

    @Test
    fun failsWithAnActionableMessageWhenTheCommandDoesNotExist() = runTest {
        val missing = scriptDir.resolve("nope")

        val error = assertFailsWith<ConverterNotFoundException> {
            convertOrders(converter("$missing --xml"))
        }

        assertTrue("--convert-command" in error.message!!)
        assertTrue("ORACLE_HOME" in error.message!!)
    }

    /** A bare program name is a PATH lookup; failing to find it must not surface as a raw IO error. */
    @Test
    fun failsWithAnActionableMessageWhenTheProgramIsNotOnThePath() = runTest {
        val error = assertFailsWith<ConverterNotFoundException> {
            convertOrders(converter("ofmcp-no-such-program --xml"))
        }

        assertTrue("--convert-command" in error.message!!)
        assertTrue("PATH" in error.message!!)
    }

    @Test
    fun failsWithAnActionableMessageOnAMalformedCommand() = runTest {
        val error = assertFailsWith<ConverterNotFoundException> {
            convertOrders(converter("\"$scriptDir/conv.bat --xml"))
        }

        assertTrue("unterminated" in error.message!!)
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

        val error = assertFailsWith<ConversionFailedException> { convertOrders(converter(script)) }

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
            convertOrders(converter(script, timeoutSeconds = 1))
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
