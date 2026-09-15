package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
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
 * absolute path is substituted for `{}` — or appended when the spec has no `{}` — the canonical
 * output path is substituted for `{out}` (never appended), and the process is spawned directly with
 * that list, never through a shell, with the working directory set to the directory the text form
 * is kept in (the server's `--converted-dir` when configured, else the module's cache directory).
 * That mirrors how `frmf2xml` is driven: the command is expected to write its output *into the
 * working directory* — or to `{out}`, for a tool such as `frmcmp` that writes next to the module
 * unless told otherwise — in the text forms the parser consumes: XML for fmb/mmb/olb and a `.pld`
 * dump for pll.
 *
 * The command is **operator configuration** (`--convert-command`, or `--compile-command` for `.pll`
 * libraries), never something a tool caller can choose: the model supplies only a module name,
 * which is resolved against the scanned forms directory before it ever reaches here.
 *
 * Like [OracleToolsModuleConverter], the command is validated lazily at first conversion so a
 * server configured with a stale path still starts and serves already-cached modules, and success
 * is judged by the output file rather than the exit code.
 */
public class CustomCommandModuleConverter internal constructor(
    private val commandSpec: String,
    /** Appended to `FORMS_PATH` so the command can resolve libraries next to the module. */
    private val formsDir: Path?,
    private val timeout: Duration,
    /** The setting [commandSpec] came from — what every message about a broken command names. */
    private val option: ConverterOption,
) : ModuleConverter {

    public constructor(
        commandSpec: String,
        formsDir: Path? = null,
        timeout: Duration = 120.seconds,
    ) : this(commandSpec, formsDir, timeout, ConverterOption.CONVERT)

    override val description: String = "Custom conversion command ($commandSpec)"

    override fun convertsBinary(type: ModuleType): Boolean = true

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
        val source = Path.of(sourcePath)
        val target = Path.of(targetDir).createDirectories()
        // Already-converted input (directory had only the text form) needs a plain copy.
        if (ConvertedFiles.isConverted(source)) {
            return ConvertedFiles.copyInto(source, target).toString()
        }
        val template = resolveCommand()
        val argv = ConvertCommandSpec.argvFor(
            template,
            sourcePath = source.toAbsolutePath().toString(),
            outputPath = target.resolve(key.convertedFileName).toAbsolutePath().toString(),
        )
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
                "${option.flag} is set to '$commandSpec' but '${template.first()}' could not " +
                    "be started: ${e.message}. Give the full path to the executable or script " +
                    "(a bare name is only found on PATH), or drop the flag to ${option.fallback}.",
            )
        }
        val output = ConversionOutput.check(
            key, toolName, result, startedAt, timeout,
            hint = { strayOutputHint(key, source, target, startedAt) },
        ) {
            // Oracle's own naming (`orders_fmb.xml`) is unambiguous even in a directory shared by
            // every module, so try it first; fall back to any file of the right format, since a
            // custom converter is under no obligation to copy frmf2xml's basename mangling and the
            // parser dispatches on the extension alone.
            ConversionOutput.canonical(target, key, startedAt)
                ?: ConversionOutput.newestMatching(target, startedAt) {
                    it.endsWith(key.type.convertedSuffix, ignoreCase = true)
                }
                ?: ConversionOutput.newestMatching(target, startedAt) {
                    it.endsWith(fallbackExtension(key), ignoreCase = true)
                }
        }
        return output.toAbsolutePath().toString()
    }

    /**
     * When a run produced nothing where it was expected, but did write [key]'s canonical text form
     * next to the module: what it did and how to fix the command. That is exactly what `frmcmp` does
     * when it is given no output file, and the stray file matters beyond this failure — a `.pld` or
     * `*_fmb.xml` in the forms directory is read as a pre-converted module. It is named, never
     * deleted: the forms directory is the operator's.
     */
    private fun strayOutputHint(key: ModuleKey, source: Path, target: Path, startedAt: Long): String? {
        val moduleDir = source.toAbsolutePath().parent ?: return null
        if (moduleDir == target.toAbsolutePath()) return null
        val stray = ConversionOutput.canonical(moduleDir, key, startedAt) ?: return null
        val out = ConvertCommandSpec.OUTPUT_PLACEHOLDER
        return "It wrote $stray next to the module instead. The command has to write into its " +
            "working directory or to $out: add $out where it takes its output file (for frmcmp, " +
            "Output_File=$out) to ${option.flag}, and remove $stray, which would otherwise be read " +
            "as a pre-converted module."
    }

    /** The bare format extension of [key]'s text form — `.xml` for the XML kinds, `.pld` for pll. */
    private fun fallbackExtension(key: ModuleKey): String =
        key.type.convertedSuffix.substringAfterLast('.').let { ".$it" }

    /**
     * Parses [commandSpec] and makes its program absolute. Absolutising matters because the child
     * runs in the output directory: on Unix the exec happens after the chdir, so a relative program
     * would be looked up there. A program that is not an existing file is left untouched for the OS
     * to resolve on `PATH` — that is how `wine`, `docker`, or `python3` are named.
     */
    private fun resolveCommand(): List<String> {
        val template = ConvertCommandSpec.parse(commandSpec, option)
        val program = template.first()
        val asFile = try {
            Path.of(program).takeIf { it.isRegularFile() }
        } catch (_: Exception) {
            null
        }
        if (asFile != null) return listOf(asFile.toAbsolutePath().toString()) + template.drop(1)
        if (isPathLike(program)) {
            throw ConverterNotFoundException(
                "${option.flag} is set to '$commandSpec' but its program '$program' is not an " +
                    "existing file. Point it at the converter script, or drop the flag to " +
                    "${option.fallback}.",
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
