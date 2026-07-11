package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.SourceRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PldParserTest {

    private val cacheDir: Path = Files.createTempDirectory("pld-parser-test")
    private val parser = FormsModuleParser()

    @AfterTest
    fun cleanup() {
        cacheDir.toFile().deleteRecursively()
    }

    private fun parseFixture() = parser.parse(
        ModuleKey.of("utils", ModuleType.LIBRARY),
        copyFixture("utils.pld", cacheDir.resolve("converted").createDirectories()).toString(),
        cacheDir.toString(),
    )

    private fun readRef(ref: SourceRef): String =
        cacheDir.resolve(ref.file).readLines().subList(ref.startLine - 1, ref.endLine).joinToString("\n")

    @Test
    fun splitsUnitsAtColumnZeroHeaders() {
        val units = parseFixture().programUnits
        assertEquals(
            listOf(
                "LOG_MESSAGE" to ProgramUnitType.PROCEDURE,
                "ADD_NUMBERS" to ProgramUnitType.FUNCTION,
                "PKG_UTIL" to ProgramUnitType.PACKAGE_SPEC,
                "PKG_UTIL" to ProgramUnitType.PACKAGE_BODY,
            ),
            units.map { it.name to it.unitType },
        )
    }

    @Test
    fun unitRangesSliceTheirFullBodies() {
        val units = parseFixture().programUnits
        val logMessage = readRef(assertNotNull(units[0].textRef))
        assertTrue(logMessage.startsWith("PROCEDURE log_message"))
        assertTrue(logMessage.trimEnd().endsWith("END;"))
        // The PROCEDURE inside a string literal did not start a new unit.
        assertTrue(logMessage.contains("'PROCEDURE inside a string literal'"))

        val packageBody = readRef(assertNotNull(units[3].textRef))
        assertTrue(packageBody.startsWith("PACKAGE BODY pkg_util"))
        // Indented nested procedure stayed inside the body.
        assertTrue(packageBody.contains("PROCEDURE noop IS"))
    }

    @Test
    fun lineCountsExcludeTrailingSeparators() {
        val units = parseFixture().programUnits
        assertEquals(5, units[0].lineCount) // header + BEGIN + 2 body lines + END;
        assertEquals(4, units[1].lineCount)
    }
}
