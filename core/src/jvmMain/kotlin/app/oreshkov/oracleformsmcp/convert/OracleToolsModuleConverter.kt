package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConversionFailedException
import app.oreshkov.oracleformsmcp.core.ConversionTimeoutException
import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ModuleConverter] shelling out to the Oracle Forms tools under `<ORACLE_HOME>/bin`:
 * `frmf2xml` for fmb/mmb/olb (writes `<basename>_fmb.xml` into the process **cwd**, so it runs
 * with cwd = the module's `converted/` cache dir) and `frmcmp_batch Script=YES` for pll.
 *
 * Tool presence is validated lazily at first conversion, so a server with a stale `ORACLE_HOME`
 * still starts and serves already-cached modules. Forms tools have unreliable exit codes, so
 * success is judged by the output file: it must exist, be non-empty, and be newer than the
 * invocation.
 */
public class OracleToolsModuleConverter(
    private val oracleHome: Path,
    /** Appended to `FORMS_PATH` so frmcmp can resolve attached libraries next to the module. */
    private val formsDir: Path? = null,
    private val timeout: Duration = 120.seconds,
) : ModuleConverter {

    private val log = Logger.withTag("OracleToolsModuleConverter")

    override val description: String = "Oracle tools conversion (ORACLE_HOME=$oracleHome)"

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
        val source = Path.of(sourcePath)
        val target = Path.of(targetDir).createDirectories()
        // Already-converted input (directory had only the text form) needs a plain copy.
        if (ConvertedFiles.isConverted(source)) {
            return ConvertedFiles.copyInto(source, target).toString()
        }
        return when (key.type) {
            ModuleType.LIBRARY -> convertLibrary(key, source, target)
            else -> convertToXml(key, source, target)
        }.toAbsolutePath().toString()
    }

    private suspend fun convertToXml(key: ModuleKey, source: Path, target: Path): Path {
        val tool = resolveTool("frmf2xml")
        val startedAt = System.currentTimeMillis()
        val result = ExternalTool.run(
            command = listOf(tool.toString(), source.toAbsolutePath().toString(), "OVERWRITE=YES", "USE_PROPERTY_IDS=NO"),
            workingDir = target,
            timeout = timeout,
            extraEnv = formsPathEnv(),
        )
        return checkOutput(key, tool, result, startedAt) {
            // frmf2xml derives the output name from the input basename; casing varies, so glob.
            newestMatching(target, startedAt) { it.endsWith(key.type.convertedSuffix, ignoreCase = true) }
        }
    }

    private suspend fun convertLibrary(key: ModuleKey, source: Path, target: Path): Path {
        val tool = resolveTool("frmcmp_batch", "frmcmp")
        val outputFile = target.resolve(source.name.dropLast(4).lowercase() + ".pld")
        val startedAt = System.currentTimeMillis()
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
            extraEnv = formsPathEnv(),
        )
        return checkOutput(key, tool, result, startedAt) {
            // Some frmcmp versions ignore Output_File and write into the cwd instead.
            outputFile.takeIf { it.isRegularFile() && it.fileSize() > 0 }
                ?: newestMatching(target, startedAt) { it.endsWith(".pld", ignoreCase = true) }
        }
    }

    /**
     * Resolves the first existing executable among per-OS candidates of [names] under
     * `<ORACLE_HOME>/bin`. `frmcmp_batch` is preferred over `frmcmp` because the batch variant is
     * headless (plain frmcmp may open a GUI on Windows even with `Batch=YES`).
     */
    private fun resolveTool(vararg names: String): Path {
        val bin = oracleHome.resolve("bin")
        val candidates = names.flatMap { listOf("$it.bat", "$it.exe", it) }
        for (candidate in candidates) {
            val path = bin.resolve(candidate)
            if (path.isRegularFile()) return path
        }
        throw ConverterNotFoundException(
            "ORACLE_HOME is set to '$oracleHome' but none of ${candidates.joinToString()} " +
                "was found in its bin directory. Fix ORACLE_HOME, or unset it and place " +
                "pre-converted files next to the modules instead.",
        )
    }

    private inline fun checkOutput(
        key: ModuleKey,
        tool: Path,
        result: ExternalTool.Result,
        startedAt: Long,
        findOutput: () -> Path?,
    ): Path {
        if (result.timedOut) {
            throw ConversionTimeoutException(
                "Converting $key with ${tool.name} exceeded ${timeout.inWholeSeconds}s and was killed. " +
                    "Output tail:\n${result.output}",
            )
        }
        val output = findOutput()
        if (output == null || !output.isRegularFile() || output.fileSize() == 0L) {
            throw ConversionFailedException(
                "Converting $key with ${tool.name} produced no output file " +
                    "(exit code ${result.exitCode}). Output tail:\n${result.output}",
            )
        }
        if (result.exitCode != 0) {
            log.w { "$tool exited with ${result.exitCode} for $key but produced $output; using it" }
        }
        // `startedAt` guards against picking up a leftover of a previous failed run.
        check(output.getLastModifiedTime().toMillis() >= startedAt - STALE_OUTPUT_GRACE_MILLIS) {
            "Converted file $output predates the conversion run"
        }
        return output
    }

    private fun newestMatching(dir: Path, startedAt: Long, predicate: (String) -> Boolean): Path? =
        dir.takeIf { it.exists() }
            ?.listDirectoryEntries()
            ?.filter { it.isRegularFile() && predicate(it.name) }
            ?.filter { it.getLastModifiedTime().toMillis() >= startedAt - STALE_OUTPUT_GRACE_MILLIS }
            ?.maxByOrNull { it.getLastModifiedTime().toMillis() }

    private fun formsPathEnv(): Map<String, String> {
        val dir = formsDir ?: return emptyMap()
        val existing = System.getenv("FORMS_PATH")
        val value = if (existing.isNullOrBlank()) dir.toString() else existing + File.pathSeparator + dir
        return mapOf("FORMS_PATH" to value)
    }

    private companion object {
        /** Filesystem mtime granularity slack (FAT stores 2s resolution). */
        const val STALE_OUTPUT_GRACE_MILLIS: Long = 2_000
    }
}
