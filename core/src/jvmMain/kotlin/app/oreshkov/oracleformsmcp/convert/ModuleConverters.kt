package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Picks the converter for the environment. */
public object ModuleConverters {

    /**
     * In precedence order: [CustomCommandModuleConverter] when [convertCommand] is a non-blank
     * path, [OracleToolsModuleConverter] when [oracleHome] is, else [PreConvertedCopyConverter].
     * An explicitly configured command wins over `ORACLE_HOME` — an operator who names a converter
     * means it, even on a machine that also has a Forms installation.
     *
     * A bad command or `ORACLE_HOME` fails lazily at first conversion (with a clear message), not
     * at startup, so cached modules stay readable.
     */
    public fun forEnvironment(
        oracleHome: String?,
        formsDir: Path,
        timeout: Duration = 120.seconds,
        convertCommand: String? = null,
    ): ModuleConverter = when {
        !convertCommand.isNullOrBlank() ->
            CustomCommandModuleConverter(Path.of(convertCommand), formsDir, timeout)
        oracleHome.isNullOrBlank() -> PreConvertedCopyConverter()
        else -> OracleToolsModuleConverter(Path.of(oracleHome), formsDir, timeout)
    }
}
