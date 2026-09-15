package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.core.ConversionFailedException
import app.oreshkov.oracleformsmcp.core.ConversionTimeoutException
import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun compileConverter(command: String) =
        CustomCommandModuleConverter(command, formsDir, 30.seconds, ConverterOption.COMPILE)

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

    /**
     * In the one output directory `--converted-dir` gives every module, a sibling module's file may
     * well be the newest match — so Oracle's naming for *this* module wins over the newest-file glob.
     */
    @Test
    fun prefersTheCanonicalNameOverANewerSiblingInASharedOutputDirectory() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        val script = copyingScript(fixture, "orders_fmb.xml")
        targetDir.createDirectories()
        targetDir.resolve("other_fmb.xml").apply {
            writeText("<OtherModule/>")
            // Unambiguously the newest match, without making the test wait for the clock to tick.
            setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(60)))
        }

        val output = convertOrders(converter(script))

        assertEquals("orders_fmb.xml", output.name)
        assertTrue(output.readText().contains("FormModule"))
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
        assertFalse("next to the module" in error.message!!)
    }

    /** `{out}` is how a tool that ignores its working directory is told where the text form goes. */
    @Test
    fun writesToTheOutputPlaceholderPath() = runTest {
        val fixture = copyFixture("utils.pld", temp)
        val record = scriptDir.resolve("args.txt")
        val script = FakeOracleHome.stubScript(
            scriptDir,
            "compile",
            // Writes only to the path it is given — never to its working directory.
            batchLines = listOf("echo %* >\"$record\"", "copy /Y \"$fixture\" \"%~2\" >nul"),
            shellLines = listOf("echo \"\$@\" >\"$record\"", "cp \"$fixture\" \"\$2\""),
        )
        val module = fakeBinary("UTILS.pll")

        val output = compileConverter("$script --out {out} --in {}").convert(
            ModuleKey.of("utils", ModuleType.LIBRARY), module.toString(), targetDir.toString(),
        )

        val expected = targetDir.resolve("utils.pld").toAbsolutePath()
        assertEquals(expected, Path.of(output))
        assertEquals("--out $expected --in $module", record.readText().replace("\"", "").trim())
    }

    /**
     * The regression canary for real `frmcmp`: given no output file it writes the `.pld` next to the
     * *module*, whatever its working directory. The failure has to say where the file went and how
     * to fix the command — and leave the file alone, since the forms directory is the operator's.
     */
    @Test
    fun namesAStrayOutputWrittenNextToTheModule() = runTest {
        val fixture = copyFixture("utils.pld", temp)
        val script = FakeOracleHome.stubScript(
            scriptDir,
            "compile",
            batchLines = listOf("copy /Y \"$fixture\" \"%~dp1UTILS.pld\" >nul"),
            shellLines = listOf("cp \"$fixture\" \"\$(dirname \"\$1\")/UTILS.pld\""),
        )

        val error = assertFailsWith<ConversionFailedException> {
            compileConverter(script.toString()).convert(
                ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
            )
        }

        val stray = formsDir.resolve("UTILS.pld")
        assertTrue("next to the module" in error.message!!, error.message)
        assertTrue(stray.toString() in error.message!!, error.message)
        assertTrue("Output_File={out}" in error.message!!)
        assertTrue("--compile-command" in error.message!!)
        assertTrue(stray.exists())
    }

    /** Dropping `--compile-command` hands `.pll` to the other converters, not straight to ORACLE_HOME. */
    @Test
    fun compileOptionMessagesNameTheCompileFlag() = runTest {
        val missing = scriptDir.resolve("nope")

        val error = assertFailsWith<ConverterNotFoundException> {
            compileConverter("$missing --xml").convert(
                ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
            )
        }

        assertTrue("--compile-command is set" in error.message!!, error.message)
        assertTrue("with --convert-command" in error.message!!, error.message)
        assertFalse("fall back to ORACLE_HOME" in error.message!!, error.message)
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
