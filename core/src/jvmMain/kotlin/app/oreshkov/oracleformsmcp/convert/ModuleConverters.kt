package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Picks the converter for the environment. */
public object ModuleConverters {

    /**
     * [OracleToolsModuleConverter] when [oracleHome] is a non-blank path, else
     * [PreConvertedCopyConverter]. A bad `ORACLE_HOME` fails lazily at first conversion (with a
     * clear message), not at startup, so cached modules stay readable.
     */
    public fun forEnvironment(
        oracleHome: String?,
        formsDir: Path,
        timeout: Duration = 120.seconds,
    ): ModuleConverter = when {
        oracleHome.isNullOrBlank() -> PreConvertedCopyConverter()
        else -> OracleToolsModuleConverter(Path.of(oracleHome), formsDir, timeout)
    }
}
