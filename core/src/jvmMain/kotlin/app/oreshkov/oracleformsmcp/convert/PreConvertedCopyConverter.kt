package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.PreConvertedFileMissingException
import app.oreshkov.oracleformsmcp.model.ModuleKey
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
                    ?: throw PreConvertedFileMissingException(
                        "ORACLE_HOME is not set and no pre-converted " +
                            "'${source.name.dropLast(key.type.extension.length + 1)}${key.type.convertedSuffix}' " +
                            "exists next to ${source.name} in ${source.parent}. Either set ORACLE_HOME " +
                            "so the server can run frmf2xml/frmcmp itself, or convert the module " +
                            "manually and place the result in the forms directory.",
                    )
            }
            ConvertedFiles.copyInto(converted, Path.of(targetDir)).toAbsolutePath().toString()
        }
}
