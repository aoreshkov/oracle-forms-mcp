package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the converter precedence: an explicit command wins, then ORACLE_HOME, then copy-mode — and a
 * `.pll` library goes to `--compile-command` first when one is set. One test per row of the
 * three-setting matrix.
 */
class ModuleConvertersTest {

    private val formsDir = Path.of("forms")

    private fun pick(oracleHome: String? = null, convert: String? = null, compile: String? = null): ModuleConverter =
        ModuleConverters.forEnvironment(oracleHome, formsDir, 120.seconds, convert, compile)

    /** The converter a module of [type] reaches, whether or not [converter] routes by type. */
    private fun reached(converter: ModuleConverter, type: ModuleType): ModuleConverter =
        (converter as? ByModuleTypeConverter)?.delegateFor(type) ?: converter

    @Test
    fun picksCopyModeWithoutAnyConfiguration() {
        assertIs<PreConvertedCopyConverter>(
            ModuleConverters.forEnvironment(oracleHome = null, formsDir = formsDir),
        )
    }

    @Test
    fun picksOracleToolsWhenOnlyOracleHomeIsSet() {
        assertIs<OracleToolsModuleConverter>(
            ModuleConverters.forEnvironment(oracleHome = "/opt/oracle", formsDir = formsDir),
        )
    }

    @Test
    fun picksTheCustomCommandWhenOnlyItIsSet() {
        assertIs<CustomCommandModuleConverter>(
            ModuleConverters.forEnvironment(
                oracleHome = null, formsDir = formsDir, convertCommand = "/usr/local/bin/conv",
            ),
        )
    }

    /** An operator who names a converter means it, even on a machine with a Forms installation. */
    @Test
    fun theCustomCommandOutranksOracleHome() {
        assertIs<CustomCommandModuleConverter>(
            ModuleConverters.forEnvironment(
                oracleHome = "/opt/oracle", formsDir = formsDir, convertCommand = "/usr/local/bin/conv",
            ),
        )
    }

    /** A launcher template that always passes the flag must not break the ORACLE_HOME fallback. */
    @Test
    fun aBlankCommandIsTreatedAsUnset() {
        assertIs<OracleToolsModuleConverter>(
            ModuleConverters.forEnvironment(
                oracleHome = "/opt/oracle", formsDir = formsDir, convertCommand = "   ",
            ),
        )
        assertIs<PreConvertedCopyConverter>(
            ModuleConverters.forEnvironment(oracleHome = null, formsDir = formsDir, convertCommand = ""),
        )
    }

    /** Without a compile command nothing is wrapped: existing configurations are unchanged. */
    @Test
    fun noCompileCommandLeavesEveryConverterUnwrapped() {
        assertIs<PreConvertedCopyConverter>(pick())
        assertIs<OracleToolsModuleConverter>(pick(oracleHome = "/opt/oracle"))
        assertIs<CustomCommandModuleConverter>(pick(convert = "/usr/local/bin/conv"))
        assertIs<CustomCommandModuleConverter>(pick(oracleHome = "/opt/oracle", convert = "/usr/local/bin/conv"))
        assertIs<OracleToolsModuleConverter>(pick(oracleHome = "/opt/oracle", compile = "  "))
    }

    @Test
    fun aCompileCommandAloneConvertsLibrariesAndLeavesFormsToCopyMode() {
        val converter = pick(compile = "/opt/frmcmp.sh")

        assertIs<CustomCommandModuleConverter>(reached(converter, ModuleType.LIBRARY))
        assertIs<PreConvertedCopyConverter>(reached(converter, ModuleType.FORM))
        assertEquals(true, converter.convertsBinary(ModuleType.LIBRARY))
        assertEquals(false, converter.convertsBinary(ModuleType.FORM))
    }

    @Test
    fun aCompileCommandWithOracleHomeLeavesFormsToFrmf2xml() {
        val converter = pick(oracleHome = "/opt/oracle", compile = "/opt/frmcmp.sh")

        assertIs<CustomCommandModuleConverter>(reached(converter, ModuleType.LIBRARY))
        assertIs<OracleToolsModuleConverter>(reached(converter, ModuleType.FORM))
    }

    @Test
    fun aCompileCommandWithAConvertCommandSplitsByType() {
        val converter = pick(convert = "/opt/f2xml.sh", compile = "/opt/frmcmp.sh")

        assertEquals("Custom conversion command (/opt/frmcmp.sh)", reached(converter, ModuleType.LIBRARY).description)
        assertEquals("Custom conversion command (/opt/f2xml.sh)", reached(converter, ModuleType.FORM).description)
    }

    @Test
    fun aCompileCommandOutranksBothOtherSettingsForLibrariesOnly() {
        val converter = pick(oracleHome = "/opt/oracle", convert = "/opt/f2xml.sh", compile = "/opt/frmcmp.sh")

        assertEquals("Custom conversion command (/opt/frmcmp.sh)", reached(converter, ModuleType.LIBRARY).description)
        ModuleType.entries.filter { it != ModuleType.LIBRARY }.forEach {
            assertEquals("Custom conversion command (/opt/f2xml.sh)", reached(converter, it).description, "$it")
        }
    }

    /** Copy-mode is the one converter that cannot read a binary — every row says so per type. */
    @Test
    fun convertsBinaryIsFalseExactlyWhereCopyModeServes() {
        assertEquals(false, pick().convertsBinary(ModuleType.LIBRARY))
        assertEquals(true, pick(oracleHome = "/opt/oracle").convertsBinary(ModuleType.MENU))
        assertEquals(true, pick(convert = "/opt/f2xml.sh").convertsBinary(ModuleType.OBJECT_LIBRARY))
    }
}
