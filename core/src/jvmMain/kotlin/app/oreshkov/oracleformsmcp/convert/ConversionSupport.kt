package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConversionFailedException
import app.oreshkov.oracleformsmcp.core.ConversionTimeoutException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * Shared plumbing for converters that shell out to an external tool: judging success from the
 * output file rather than the exit code, and locating that file in the target directory.
 *
 * Forms-era tools have unreliable exit codes — [OracleToolsModuleConverter] and
 * [CustomCommandModuleConverter] both judge a run by what it produced.
 */
internal object ConversionOutput {

    /** Filesystem mtime granularity slack (FAT stores 2s resolution). */
    const val STALE_GRACE_MILLIS: Long = 2_000

    private val log = Logger.withTag("ConversionOutput")

    /**
     * Validates the run of [toolName] for [key] and returns the file [findOutput] located, or
     * throws with a message naming the fix. A non-zero exit code is only a warning when an output
     * file was still produced.
     */
    fun check(
        key: ModuleKey,
        toolName: String,
        result: ExternalTool.Result,
        startedAt: Long,
        timeout: Duration,
        findOutput: () -> Path?,
    ): Path {
        if (result.timedOut) {
            throw ConversionTimeoutException(
                "Converting $key with $toolName exceeded ${timeout.inWholeSeconds}s and was killed. " +
                    "Output tail:\n${result.output}",
            )
        }
        val output = findOutput()
        if (output == null || !output.isRegularFile() || output.fileSize() == 0L) {
            throw ConversionFailedException(
                "Converting $key with $toolName produced no output file " +
                    "(exit code ${result.exitCode}). Output tail:\n${result.output}",
            )
        }
        if (result.exitCode != 0) {
            log.w { "$toolName exited with ${result.exitCode} for $key but produced $output; using it" }
        }
        // `startedAt` guards against picking up a leftover of a previous failed run.
        check(output.getLastModifiedTime().toMillis() >= startedAt - STALE_GRACE_MILLIS) {
            "Converted file $output predates the conversion run"
        }
        return output
    }

    /** Newest regular file in [dir] whose name satisfies [predicate], written at or after [startedAt]. */
    fun newestMatching(dir: Path, startedAt: Long, predicate: (String) -> Boolean): Path? =
        dir.takeIf { it.exists() }
            ?.listDirectoryEntries()
            ?.filter { it.isRegularFile() && predicate(it.name) }
            ?.filter { it.getLastModifiedTime().toMillis() >= startedAt - STALE_GRACE_MILLIS }
            ?.maxByOrNull { it.getLastModifiedTime().toMillis() }
}

/**
 * `FORMS_PATH` with [formsDir] appended, so a converter can resolve libraries attached next to the
 * module. Empty when [formsDir] is null — the child then inherits the server's own environment.
 */
internal fun formsPathEnv(formsDir: Path?): Map<String, String> {
    val dir = formsDir ?: return emptyMap()
    val existing = System.getenv("FORMS_PATH")
    val value = if (existing.isNullOrBlank()) dir.toString() else existing + File.pathSeparator + dir
    return mapOf("FORMS_PATH" to value)
}
