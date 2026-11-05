package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModuleConverter] shelling out to the Oracle Forms tools: `frmf2xml` for fmb/mmb/olb and
 * `frmcmp_batch Script=YES` for pll. They are looked for under `<ORACLE_HOME>/bin`, then under
 * `<FORMS_INSTANCE>/bin`, which is the only place a Linux Forms 12c+ install has `frmf2xml` (as
 * `frmf2xml.sh`).
 *
 * Where `frmf2xml` writes `<basename>_fmb.xml` depends on the install: into its cwd on Windows,
 * next to its input on Linux 14.1.2. So it is given a copy of the module in a staging directory
 * inside the target, run with that directory as cwd — the output lands there either way, and
 * never among the sources, which are often a read-only working copy. The result is then moved
 * into the target: the server's `--converted-dir` when configured, else the module's `converted`
 * cache dir.
 *
 * Tool presence is validated lazily at first conversion, so a server with a stale `ORACLE_HOME`
 * still starts and serves already-cached modules. Forms tools have unreliable exit codes, so
 * success is judged by the output file: it must exist, be non-empty, and have been written by
 * this invocation ([OutputRun]).
 */
public class OracleToolsModuleConverter(
    private val oracleHome: Path,
    /** Appended to `FORMS_PATH` so the tools can resolve attached libraries next to the module. */
    private val formsDir: Path?,
    private val timeout: Duration,
    /** The Forms instance (`FORMS_INSTANCE`), whose `bin` is searched after `ORACLE_HOME`'s. */
    private val formsInstance: Path?,
) : ModuleConverter {

    /** As the primary constructor, with the Forms instance taken from `FORMS_INSTANCE`. */
    public constructor(
        oracleHome: Path,
        formsDir: Path? = null,
        timeout: Duration = 120.seconds,
    ) : this(oracleHome, formsDir, timeout, formsInstanceFromEnvironment())

    override val description: String = "Oracle tools conversion (ORACLE_HOME=$oracleHome)"

    override fun convertsBinary(type: ModuleType): Boolean = true

    // The copy and the output scans are blocking file IO, which the server's handler dispatcher
    // (Dispatchers.Default) must not be tied up with.
    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String =
        withContext(Dispatchers.IO) {
            val source = Path.of(sourcePath)
            val target = Path.of(targetDir).createDirectories()
            // Already-converted input (directory had only the text form) needs a plain copy.
            if (ConvertedFiles.isConverted(source)) {
                return@withContext ConvertedFiles.copyInto(source, target).toString()
            }
            when (key.type) {
                ModuleType.LIBRARY -> convertLibrary(key, source, target)
                else -> convertToXml(key, source, target)
            }.toAbsolutePath().toString()
        }

    private suspend fun convertToXml(key: ModuleKey, source: Path, target: Path): Path {
        val tool = resolveTool("frmf2xml")
        // Unique per run: with --converted-dir the target is shared by every module.
        val staging = Files.createTempDirectory(target, STAGING_PREFIX)
        try {
            // Under its own name, which is what frmf2xml derives the output's from.
            val input = Files.copy(source, staging.resolve(source.name))
            val run = OutputRun.begin(staging)
            val result = ExternalTool.run(
                command = listOf(tool.toString(), input.toAbsolutePath().toString(), "OVERWRITE=YES", "USE_PROPERTY_IDS=NO"),
                workingDir = staging,
                timeout = timeout,
                // The copy has left the module's directory, so that directory goes on FORMS_PATH
                // even when the forms dir is not known.
                extraEnv = toolEnv(formsDir ?: source.parent),
            )
            val produced = checkOutput(key, tool, result, run) {
                // frmf2xml derives the output name from the input basename, so it *is* the canonical
                // one, in whatever casing. Any other `*_fmb.xml` names another module's text form.
                run.canonical(key)
            }
            return Files.move(produced, target.resolve(produced.name), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // A tool that outlived its kill may still hold a file open on Windows; the leftover is
            // harmless, as no lookup matches a staging directory's name.
            if (!staging.toFile().deleteRecursively()) log.w { "Could not remove the staging directory $staging" }
        }
    }

    private suspend fun convertLibrary(key: ModuleKey, source: Path, target: Path): Path {
        val tool = resolveTool("frmcmp_batch", "frmcmp")
        val outputFile = target.resolve(source.name.dropLast(4).lowercase() + ".pld")
        val run = OutputRun.begin(target)
        val result = ExternalTool.run(
            command = listOf(
                tool.toString(),
                "Module=${source.toAbsolutePath()}",
                "Module_Type=LIBRARY",
                "Script=YES",
                "Batch=YES",
                "Logon=NO",
                "Output_File=${outputFile.toAbsolutePath()}",
            ),
            workingDir = target,
            timeout = timeout,
            extraEnv = toolEnv(formsDir),
        )
        return checkOutput(key, tool, result, run) {
            // Output_File is what keeps the .pld out of the forms directory: without it frmcmp
            // writes next to the *module*, whatever its cwd. Some versions are reported to write
            // into the cwd even when it is given, hence the fallback — under the module's own name,
            // as any other `.pld` names another library's dump.
            outputFile.takeIf { run.produced(it) && it.fileSize() > 0 }
                ?: run.canonical(key)
        }
    }

    private fun toolEnv(formsPath: Path?): Map<String, String> = formsPathEnv(formsPath) + headlessEnv()

    /**
     * Resolves the first existing executable among per-OS candidates of [names], under
     * `<ORACLE_HOME>/bin` and then `<FORMS_INSTANCE>/bin`. `frmcmp_batch` is preferred over
     * `frmcmp` because the batch variant is headless (plain frmcmp may open a GUI on Windows even
     * with `Batch=YES`). Within a directory a binary wins over the `.sh` wrapper of the same name.
     */
    private fun resolveTool(vararg names: String): Path {
        val dirs = listOfNotNull(oracleHome.resolve("bin"), formsInstance?.resolve("bin"))
        val candidates = names.flatMap { name -> TOOL_SUFFIXES.map { name + it } }
        for (dir in dirs) {
            for (candidate in candidates) {
                val path = dir.resolve(candidate)
                if (path.isRegularFile()) return path
            }
        }
        val instanceAdvice = if (formsInstance == null) {
            " On Linux, Forms 12c and later keep frmf2xml.sh only in the Forms instance's bin " +
                "directory: set FORMS_INSTANCE to that instance."
        } else {
            ""
        }
        throw ConverterNotFoundException(
            "ORACLE_HOME is set to '$oracleHome' but none of ${candidates.joinToString()} " +
                "was found in ${dirs.joinToString(" or ")}.$instanceAdvice Fix ORACLE_HOME, or " +
                "unset it and place pre-converted files next to the modules instead.",
        )
    }

    private fun checkOutput(
        key: ModuleKey,
        tool: Path,
        result: ExternalTool.Result,
        run: OutputRun,
        findOutput: () -> Path?,
    ): Path = ConversionOutput.check(key, tool.name, result, run, timeout, findOutput = findOutput)

    internal companion object {
        /** Name prefix of the per-run directory `frmf2xml` converts in. */
        const val STAGING_PREFIX: String = ".frmf2xml-"

        /** The terminal Forms assumes when none is set: its resource files ship for `vt220`. */
        const val DEFAULT_TERM: String = "vt220"

        private val log = Logger.withTag("OracleToolsModuleConverter")

        private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private val TOOL_SUFFIXES: List<String> =
            if (isWindows) listOf(".bat", ".cmd", ".exe", "") else listOf("", ".sh")

        fun formsInstanceFromEnvironment(): Path? =
            System.getenv("FORMS_INSTANCE")?.takeIf { it.isNotBlank() }?.let(Path::of)

        /**
         * What the tools need to run without a terminal or a display, for each variable [inherited]
         * lacks: an MCP host may start the server with a minimal environment. Without `TERM`,
         * `frmcmp_batch` fails with `FRM-91500` — and with `TERM=dumb` too, the value a host that
         * captures a child's output often sets, so `dumb` counts as missing. Without a display,
         * `frmf2xml` fails with "Failed to create new forms context" unless `FORMS_API_TK_BYPASS`
         * is set — Oracle's script sets it only on AIX. Any other value already set, even `FALSE`,
         * is left alone. Windows needs neither.
         */
        fun headlessEnv(
            inherited: Map<String, String> = System.getenv(),
            windows: Boolean = isWindows,
        ): Map<String, String> {
            if (windows) return emptyMap()
            return buildMap {
                if (isUnusableTerm(inherited["TERM"])) put("TERM", DEFAULT_TERM)
                if (inherited["FORMS_API_TK_BYPASS"].isNullOrBlank()) put("FORMS_API_TK_BYPASS", "TRUE")
            }
        }

        /** A `TERM` Forms cannot run under: unset, blank, or `dumb`, which has no resource file. */
        fun isUnusableTerm(term: String?): Boolean =
            term.isNullOrBlank() || term.trim().equals("dumb", ignoreCase = true)
    }
}
