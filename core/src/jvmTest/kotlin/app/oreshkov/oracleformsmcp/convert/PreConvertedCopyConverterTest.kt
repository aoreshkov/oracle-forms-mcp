package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.core.PreConvertedFileMissingException
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
import kotlinx.coroutines.test.runTest

class PreConvertedCopyConverterTest {

    private val temp: Path = Files.createTempDirectory("copy-converter-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val targetDir: Path = temp.resolve("target")
    private val converter = PreConvertedCopyConverter()

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun copiesThePreConvertedFileItself() = runTest {
        val source = copyFixture("utils.pld", formsDir)
        val output = converter.convert(
            ModuleKey.of("utils", ModuleType.LIBRARY), source.toString(), targetDir.toString(),
        )
        assertEquals("utils.pld", Path.of(output).name)
        assertTrue(Path.of(output).readText().contains("PROCEDURE log_message"))
    }

    @Test
    fun findsConvertedSiblingOfABinaryCaseInsensitively() = runTest {
        val binary = formsDir.resolve("ORDERS.fmb").also { it.writeText("binary") }
        copyFixture("orders_fmb.xml", formsDir, fileName = "ORDERS_FMB.XML")

        val output = converter.convert(
            ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString(),
        )
        assertTrue(Path.of(output).readText().contains("FormModule"))
    }

    @Test
    fun missingPreConvertedFileTellsHowToFix() = runTest {
        val binary = formsDir.resolve("ORDERS.fmb").also { it.writeText("binary") }

        val error = assertFailsWith<PreConvertedFileMissingException> {
            converter.convert(ModuleKey.of("orders", ModuleType.FORM), binary.toString(), targetDir.toString())
        }
        assertTrue(error.message!!.contains("ORACLE_HOME"))
        assertTrue(error.message!!.contains("orders_fmb.xml", ignoreCase = true))
    }
}
