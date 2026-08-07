package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ModuleConverter] shelling out to a site-supplied command instead of Oracle's `frmf2xml` —
 * for installations that wrap the Forms tools with their own environment setup, logon handling,
 * or an entirely different converter.
 *
 * **Calling convention.** The command is run as `<command> <absolute-source-path>` with the
 * working directory set to the module's cache directory, mirroring how `frmf2xml` is driven; it
 * is expected to write its output *into that working directory*. It must produce the same text
 * forms the parser consumes: XML for fmb/mmb/olb and a `.pld` dump for pll. No arguments beyond
 * the source path are passed, and the command is never run through a shell — it is spawned
 * directly with an argv list, so nothing in a module's path can be reinterpreted as a shell
 * metacharacter.
 *
 * The command is **operator configuration** (`--convert-command`), never something a tool caller
 * can choose: the model supplies only a module name, which is resolved against the scanned forms
 * directory before it ever reaches here.
 *
 * Like [OracleToolsModuleConverter], the command is validated lazily at first conversion so a
 * server configured with a stale path still starts and serves already-cached modules, and success
 * is judged by the output file rather than the exit code.
 */
public class CustomCommandModuleConverter(
    private val command: Path,
    /** Appended to `FORMS_PATH` so the command can resolve libraries next to the module. */
    private val formsDir: Path? = null,
    private val timeout: Duration = 120.seconds,
) : ModuleConverter {

    override val description: String = "Custom conversion command ($command)"

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
        val source = Path.of(sourcePath)
        val target = Path.of(targetDir).createDirectories()
        // Already-converted input (directory had only the text form) needs a plain copy.
        if (ConvertedFiles.isConverted(source)) {
            return ConvertedFiles.copyInto(source, target).toString()
        }
        val tool = resolveCommand()
        val startedAt = System.currentTimeMillis()
        val result = ExternalTool.run(
            command = listOf(tool.toString(), source.toAbsolutePath().toString()),
            workingDir = target,
            timeout = timeout,
            extraEnv = formsPathEnv(formsDir),
        )
        val output = ConversionOutput.check(key, tool.name, result, startedAt, timeout) {
            // Prefer Oracle's own naming (`orders_fmb.xml`), but accept any file of the right
            // format: a custom converter is under no obligation to copy frmf2xml's basename
            // mangling, and the parser dispatches on the extension alone.
            ConversionOutput.newestMatching(target, startedAt) {
                it.endsWith(key.type.convertedSuffix, ignoreCase = true)
            } ?: ConversionOutput.newestMatching(target, startedAt) {
                it.endsWith(fallbackExtension(key), ignoreCase = true)
            }
        }
        return output.toAbsolutePath().toString()
    }

    /** The bare format extension of [key]'s text form — `.xml` for the XML kinds, `.pld` for pll. */
    private fun fallbackExtension(key: ModuleKey): String =
        key.type.convertedSuffix.substringAfterLast('.').let { ".$it" }

    private fun resolveCommand(): Path {
        if (!command.isRegularFile()) {
            throw ConverterNotFoundException(
                "--convert-command is set to '$command' but that is not an existing file. " +
                    "Point it at the converter script, or drop the flag to fall back to " +
                    "ORACLE_HOME (or to pre-converted files next to the modules).",
            )
        }
        return command.toAbsolutePath()
    }
}
