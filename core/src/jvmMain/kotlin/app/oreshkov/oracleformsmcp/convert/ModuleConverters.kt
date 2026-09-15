package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Picks the converter for the environment. */
public object ModuleConverters {

    /**
     * In precedence order: [CustomCommandModuleConverter] when [convertCommand] is a non-blank
     * command line, [OracleToolsModuleConverter] when [oracleHome] is, else
     * [PreConvertedCopyConverter]. An explicitly configured command wins over `ORACLE_HOME` — an
     * operator who names a converter means it, even on a machine that also has a Forms
     * installation.
     *
     * A malformed or stale command, or a bad `ORACLE_HOME`, fails lazily at first conversion (with
     * a clear message), not at startup, so cached modules stay readable.
     */
    public fun forEnvironment(
        oracleHome: String?,
        formsDir: Path,
        timeout: Duration = 120.seconds,
        convertCommand: String? = null,
    ): ModuleConverter = forEnvironment(oracleHome, formsDir, timeout, convertCommand, compileCommand = null)

    /**
     * As the overload above, but a non-blank [compileCommand] takes `.pll` libraries away from
     * whichever converter that would pick, and runs them through their own command line instead.
     * Every other module type is unaffected, so for a `.pll` the precedence is: [compileCommand],
     * then [convertCommand], then [oracleHome], then copy-mode.
     *
     * A separate overload rather than one more defaulted parameter, which would change the JVM
     * signature of the existing function.
     */
    public fun forEnvironment(
        oracleHome: String?,
        formsDir: Path,
        timeout: Duration,
        convertCommand: String?,
        compileCommand: String?,
    ): ModuleConverter {
        val base = when {
            !convertCommand.isNullOrBlank() ->
                CustomCommandModuleConverter(convertCommand, formsDir, timeout)
            oracleHome.isNullOrBlank() -> PreConvertedCopyConverter()
            else -> OracleToolsModuleConverter(Path.of(oracleHome), formsDir, timeout)
        }
        if (compileCommand.isNullOrBlank()) return base
        val libraries = CustomCommandModuleConverter(compileCommand, formsDir, timeout, ConverterOption.COMPILE)
        return ByModuleTypeConverter(mapOf(ModuleType.LIBRARY to libraries), base)
    }
}
