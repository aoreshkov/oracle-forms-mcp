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
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
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

    /**
     * `frmf2xml` writes into its cwd, which with `--converted-dir` is shared by every module — so a
     * sibling module's newer file must not be mistaken for this run's output.
     */
    @Test
    fun prefersTheCanonicalNameOverANewerSiblingInASharedOutputDirectory() = runTest {
        val fixture = copyFixture("orders_fmb.xml", temp)
        FakeOracleHome.copyingTool(oracleHome, "frmf2xml", fixture, "orders_fmb.xml")
        targetDir.createDirectories()
        targetDir.resolve("other_fmb.xml").apply {
            writeText("<OtherModule/>")
            // Unambiguously the newest match, without making the test wait for the clock to tick.
            setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(60)))
        }

        val output = converter().convert(
            ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
        )

        assertEquals("orders_fmb.xml", Path.of(output).name)
        assertTrue(Path.of(output).readText().contains("FormModule"))
    }

    /**
     * The module converted a moment ago left its file in the shared directory, inside the mtime
     * slack. A run that wrote nothing must fail rather than take it.
     */
    @Test
    fun doesNotTakeASiblingModulesOutputWhenFrmf2xmlWritesNothing() = runTest {
        FakeOracleHome.failingTool(oracleHome, "frmf2xml", "FRM-99999: boom")
        targetDir.createDirectories()
        copyFixture("dupes_fmb.xml", targetDir).setLastModifiedTime(FileTime.from(Instant.now()))

        val error = assertFailsWith<ConversionFailedException> {
            converter().convert(
                ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
            )
        }

        assertTrue("no output file" in error.message!!, error.message)
    }

    /** ...nor one this run wrote itself, when it is named as another module's text form. */
    @Test
    fun doesNotTakeAFileFrmf2xmlWroteUnderAnotherModulesName() = runTest {
        FakeOracleHome.copyingTool(oracleHome, "frmf2xml", copyFixture("dupes_fmb.xml", temp), "dupes_fmb.xml")

        val error = assertFailsWith<ConversionFailedException> {
            converter().convert(
                ModuleKey.of("orders", ModuleType.FORM), fakeBinary("ORDERS.fmb").toString(), targetDir.toString(),
            )
        }

        assertTrue("another module's text form" in error.message!!, error.message)
    }

    /** ...and the same for a library: another library's fresh `.pld` is not this run's dump. */
    @Test
    fun doesNotTakeASiblingLibraryDumpWhenFrmcmpWritesNothing() = runTest {
        FakeOracleHome.failingTool(oracleHome, "frmcmp_batch", "PDE-PLI018: boom")
        targetDir.createDirectories()
        targetDir.resolve("misc.pld").writeText("PROCEDURE other IS BEGIN NULL; END;")

        val error = assertFailsWith<ConversionFailedException> {
            converter().convert(
                ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
            )
        }

        assertTrue("no output file" in error.message!!, error.message)
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

    /**
     * The tool's real work runs in a child of its wrapper script and holds the output pipe, so the
     * call must return within seconds of the timeout — not when the child finally exits. The real
     * time limit on `runTest` turns a regression into a failure rather than a hung build.
     */
    @Test
    fun hangingToolIsKilledOnTimeout() = runTest(timeout = 45.seconds) {
        FakeOracleHome.sleepingTool(oracleHome, "frmf2xml", seconds = 60)
        val binary = fakeBinary("ORDERS.fmb")

        val elapsed = measureTime {
            assertFailsWith<ConversionTimeoutException> {
                converter(timeoutSeconds = 1).convert(
                    ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString(),
                )
            }
        }

        assertTrue(elapsed < 15.seconds, "returned after $elapsed")
    }

    /** What keeps the `.pld` out of the forms directory is `Output_File=`; this stub honours nothing else. */
    @Test
    fun convertsLibraryToTheGivenOutputFile() = runTest {
        val fixture = copyFixture("utils.pld", temp)
        FakeOracleHome.stubTool(
            oracleHome,
            "frmcmp_batch",
            // cmd splits `Output_File=path` at the `=` unless the argument was quoted for a space.
            batchLines = listOf(
                "set \"out=\"",
                ":next",
                "if \"%~1\"==\"\" goto done",
                "set \"arg=%~1\"",
                "if /I \"%arg:~0,12%\"==\"Output_File=\" set \"out=%arg:~12%\"",
                "if /I \"%arg%\"==\"Output_File\" set \"out=%~2\"",
                "shift",
                "goto next",
                ":done",
                "if not defined out exit /b 0",
            ) + FakeOracleHome.freshCopyBatch("$fixture", "%out%"),
            shellLines = listOf(
                "for a in \"\$@\"; do",
                "  case \"\$a\" in Output_File=*) cp \"$fixture\" \"\${a#Output_File=}\" ;; esac",
                "done",
            ),
        )

        val output = converter().convert(
            ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
        )

        assertEquals(targetDir.resolve("utils.pld").toAbsolutePath(), Path.of(output))
        assertTrue(Path.of(output).readText().contains("PROCEDURE log_message"))
    }

    /** A `.pld` left by an earlier run is not this run's output, whatever the exit code says. */
    @Test
    fun rejectsALeftoverLibraryDumpFromAnEarlierRun() = runTest {
        FakeOracleHome.failingTool(oracleHome, "frmcmp_batch", "PDE-PLI018: boom")
        targetDir.createDirectories()
        targetDir.resolve("utils.pld").apply {
            writeText("PROCEDURE stale IS BEGIN NULL; END;")
            setLastModifiedTime(FileTime.from(Instant.now().minusSeconds(60)))
        }

        val error = assertFailsWith<ConversionFailedException> {
            converter().convert(
                ModuleKey.of("utils", ModuleType.LIBRARY), fakeBinary("UTILS.pll").toString(), targetDir.toString(),
            )
        }

        assertTrue("no output file" in error.message!!, error.message)
        assertTrue("PDE-PLI018: boom" in error.message!!, error.message)
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
