package app.oreshkov.oracleformsmcp.convert

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs

/** Pins the converter precedence: an explicit command wins, then ORACLE_HOME, then copy-mode. */
class ModuleConvertersTest {

    private val formsDir = Path.of("forms")

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
}
