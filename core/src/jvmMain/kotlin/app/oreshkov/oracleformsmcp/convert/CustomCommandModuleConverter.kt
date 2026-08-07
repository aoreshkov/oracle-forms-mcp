package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ModuleConverter] shelling out to a site-supplied command instead of Oracle's `frmf2xml` —
 * for installations that wrap the Forms tools with their own environment setup, logon handling,
 * or an entirely different converter.
 *
 * **Calling convention.** [commandSpec] is a full command line, not just an executable: it is
 * split into an argv list by [ConvertCommandSpec] (a quoted string, or a JSON array), the module's
 * absolute path is substituted for `{}` — or appended when the spec has no `{}` — and the process
 * is spawned directly with that list, never through a shell, with the working directory set to the
 * module's cache directory. That mirrors how `frmf2xml` is driven: the command is expected to write
 * its output *into the working directory*, in the text forms the parser consumes — XML for
 * fmb/mmb/olb and a `.pld` dump for pll.
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
    private val commandSpec: String,
    /** Appended to `FORMS_PATH` so the command can resolve libraries next to the module. */
    private val formsDir: Path? = null,
    private val timeout: Duration = 120.seconds,
) : ModuleConverter {

    override val description: String = "Custom conversion command ($commandSpec)"

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
        val source = Path.of(sourcePath)
        val target = Path.of(targetDir).createDirectories()
        // Already-converted input (directory had only the text form) needs a plain copy.
        if (ConvertedFiles.isConverted(source)) {
            return ConvertedFiles.copyInto(source, target).toString()
        }
        val template = resolveCommand()
        val argv = ConvertCommandSpec.argvFor(template, source.toAbsolutePath().toString())
        val toolName = displayName(template.first())
        val startedAt = System.currentTimeMillis()
        val result = try {
            ExternalTool.run(
                command = argv,
                workingDir = target,
                timeout = timeout,
                extraEnv = formsPathEnv(formsDir),
            )
        } catch (e: IOException) {
            // A bare program name is left for the OS to find on PATH, so "not there" surfaces only
            // now. On Windows this also covers the JDK's refusal to pass unquotable arguments to a
            // .bat/.cmd file.
            throw ConverterNotFoundException(
                "--convert-command is set to '$commandSpec' but '${template.first()}' could not " +
                    "be started: ${e.message}. Give the full path to the executable or script " +
                    "(a bare name is only found on PATH), or drop the flag to fall back to " +
                    "ORACLE_HOME (or to pre-converted files next to the modules).",
            )
        }
        val output = ConversionOutput.check(key, toolName, result, startedAt, timeout) {
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

    /**
     * Parses [commandSpec] and makes its program absolute. Absolutising matters because the child
     * runs in the module's cache directory: on Unix the exec happens after the chdir, so a relative
     * program would be looked up there. A program that is not an existing file is left untouched
     * for the OS to resolve on `PATH` — that is how `wine`, `docker`, or `python3` are named.
     */
    private fun resolveCommand(): List<String> {
        val template = ConvertCommandSpec.parse(commandSpec)
        val program = template.first()
        val asFile = try {
            Path.of(program).takeIf { it.isRegularFile() }
        } catch (_: Exception) {
            null
        }
        if (asFile != null) return listOf(asFile.toAbsolutePath().toString()) + template.drop(1)
        if (isPathLike(program)) {
            throw ConverterNotFoundException(
                "--convert-command is set to '$commandSpec' but its program '$program' is not an " +
                    "existing file. Point it at the converter script, or drop the flag to fall " +
                    "back to ORACLE_HOME (or to pre-converted files next to the modules).",
            )
        }
        return template
    }

    /** A program carrying a separator is meant as a path; a bare name is a `PATH` lookup. */
    private fun isPathLike(program: String): Boolean = program.contains('/') || program.contains('\\')

    /** Last path segment of [program], for messages — without going through `Path` parsing. */
    private fun displayName(program: String): String =
        program.substringAfterLast('/').substringAfterLast('\\')
}
