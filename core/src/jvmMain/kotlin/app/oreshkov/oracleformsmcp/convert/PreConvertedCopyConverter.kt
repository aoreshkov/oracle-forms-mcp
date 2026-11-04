package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.PreConvertedFileMissingException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Path
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModuleConverter] for the no-`ORACLE_HOME` mode: never converts, only copies an
 * already-converted text form into the cache. Accepts either the pre-converted file itself or a
 * binary module (in which case its converted sibling is looked up case-insensitively).
 */
public class PreConvertedCopyConverter : ModuleConverter {

    override val description: String = "copy of pre-converted files (ORACLE_HOME not set)"

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String =
        withContext(Dispatchers.IO) {
            val source = Path.of(sourcePath)
            val converted = when {
                ConvertedFiles.isConverted(source) -> source
                else -> ConvertedFiles.findSibling(source, key.type)
                    ?: throw PreConvertedFileMissingException(missingMessage(key.type, source))
            }
            ConvertedFiles.copyInto(converted, Path.of(targetDir)).toAbsolutePath().toString()
        }

    internal companion object {
        /**
         * Names every way out, the site's own command first: it is often the easiest one, and a
         * message that lists only `ORACLE_HOME` leaves it undiscovered. A `.pll` is pointed at
         * [ConverterOption.COMPILE], because the usual convert command is built on `frmf2xml`,
         * which rejects libraries.
         */
        fun missingMessage(type: ModuleType, source: Path): String {
            val expected = source.name.dropLast(type.extension.length + 1) + type.convertedSuffix
            val option = if (type == ModuleType.LIBRARY) ConverterOption.COMPILE else ConverterOption.CONVERT
            val command = if (type == ModuleType.LIBRARY) {
                "a command that compiles the library to a .pld (e.g. frmcmp_batch with " +
                    "Module_Type=LIBRARY Script=YES Output_File={out}; {} is the module path)"
            } else {
                "a command that converts the module to XML (e.g. frmf2xml; {} is the module path)"
            }
            return "No converter is configured for ${source.name}: ORACLE_HOME is not set, " +
                "${option.flag} (${option.envVar}) is not set, and no pre-converted '$expected' " +
                "exists next to it in ${source.parent}. Any one of these fixes it, and each is " +
                "done by whoever runs the server, not through a tool call: set ${option.flag} or ${option.envVar} to $command; " +
                "or set ORACLE_HOME so the server can run frmf2xml/frmcmp itself; or convert the " +
                "module by hand and place '$expected' in the forms directory. Then call " +
                "fetch_module again."
        }
    }
}
