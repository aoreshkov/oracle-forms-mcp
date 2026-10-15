package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConversionFailedException
import app.oreshkov.oracleformsmcp.core.ConversionTimeoutException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readAttributes
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
     * Validates [run] of [toolName] for [key] and returns the file [findOutput] located, or
     * throws with a message naming the fix. A non-zero exit code is only a warning when an output
     * file was still produced. [hint] is asked only when nothing was produced, and what it returns
     * is added to that message — for a converter that can tell *where* the output went instead.
     */
    fun check(
        key: ModuleKey,
        toolName: String,
        result: ExternalTool.Result,
        run: OutputRun,
        timeout: Duration,
        hint: () -> String? = { null },
        findOutput: () -> Path?,
    ): Path {
        if (result.timedOut) {
            throw ConversionTimeoutException(
                "Converting $key with $toolName exceeded ${timeout.inWholeSeconds}s and was killed. " +
                    "Output tail:\n${result.output}",
            )
        }
        val found = findOutput()?.takeIf { it.isRegularFile() && it.fileSize() > 0L }
        // A file this run did not write is a leftover of an earlier one, not its output — the
        // finders already skip those, so this is the last line, not the only one.
        val output = found?.takeIf { run.produced(it) }
        if (output == null) {
            val leftover = found?.let { " $it was not written by this run: it is a leftover of an earlier one." }.orEmpty()
            val foreign = run.asAnotherModule(key)?.let {
                " It did write $it, but that name is another module's text form, so it is not taken " +
                    "for $key: have the command write ${key.convertedFileName}."
            }.orEmpty()
            val why = hint()?.let { " $it" }.orEmpty()
            throw ConversionFailedException(
                "Converting $key with $toolName produced no output file " +
                    "(exit code ${result.exitCode}).$leftover$foreign$why Output tail:\n${result.output}",
            )
        }
        if (result.exitCode != 0) {
            log.w { "$toolName exited with ${result.exitCode} for $key but produced $output; using it" }
        }
        return output
    }

    /** Whether [file] is a regular file written at or after [startedAt], within the mtime slack. */
    fun isFresh(file: Path, startedAt: Long): Boolean =
        file.isRegularFile() && file.getLastModifiedTime().toMillis() >= startedAt - STALE_GRACE_MILLIS

    /** Newest regular file in [dir] whose name satisfies [predicate] and that [keep] accepts. */
    fun newestMatching(dir: Path, predicate: (String) -> Boolean, keep: (Path) -> Boolean): Path? =
        dir.takeIf { it.exists() }
            ?.listDirectoryEntries()
            ?.filter { predicate(it.name) && keep(it) }
            ?.maxByOrNull { it.getLastModifiedTime().toMillis() }

    /**
     * [key]'s canonically named file in [dir], written at or after [startedAt] — for looking where
     * a run might have strayed to, outside the directory it was given. Inside that directory,
     * [OutputRun.canonical] is the stricter question.
     */
    fun canonical(dir: Path, key: ModuleKey, startedAt: Long): Path? =
        newestMatching(dir, { it.equals(key.convertedFileName, ignoreCase = true) }) { isFresh(it, startedAt) }
}

/**
 * One converter run in [dir]: when it started, and what [dir] already held at that moment.
 *
 * What a run *produced* is what it created or changed, and freshness alone cannot tell that. In the
 * one directory `--converted-dir` gives every module, the module converted a moment ago left its
 * file well inside the mtime slack, and a run that wrote nothing would take it — the server then
 * indexes that file as this module and renames it out from under its owner. A killed run's partial
 * output and this module's own previous text form are the same trap. So a file is this run's only
 * when it is fresh *and* [dir] did not already hold it with the same mtime and size.
 */
internal class OutputRun private constructor(
    val dir: Path,
    val startedAt: Long,
    /** Keyed by file name as the directory listing spells it, which is the on-disk casing. */
    private val before: Map<String, FileStamp>,
) {
    /**
     * Whether this run wrote [file], a file in [dir]: fresh, and not one [dir] already held
     * unchanged. A file that vanishes while being looked at was not produced.
     *
     * The price of comparing stamps: on a filesystem with coarse mtimes (FAT's 2 s), a rewrite
     * inside the same tick that also keeps the size reads as unchanged. That fails the
     * conversion loudly, and the next fetch succeeds — never the reverse, which is the point.
     */
    fun produced(file: Path): Boolean {
        val stamp = FileStamp.of(file) ?: return false
        if (stamp.modified.toMillis() < startedAt - ConversionOutput.STALE_GRACE_MILLIS) return false
        // The real path's name, not the one asked about: on a case-insensitive volume a path
        // spelled `utils.pld` addresses the file the listing recorded as `UTILS.pld`.
        val onDisk = runCatching { file.toRealPath().name }.getOrDefault(file.name)
        return before[onDisk] != stamp
    }

    /** Newest file in [dir] that this run produced and whose name satisfies [predicate]. */
    fun newestProduced(predicate: (String) -> Boolean): Path? =
        ConversionOutput.newestMatching(dir, predicate, ::produced)

    /**
     * [key]'s canonically named output, when this run produced it — tried before anything else.
     * Matched case-insensitively, as the tools vary the casing.
     */
    fun canonical(key: ModuleKey): Path? =
        newestProduced { it.equals(key.convertedFileName, ignoreCase = true) }

    /**
     * The newest file this run produced whose name satisfies [predicate] and is *no* module's
     * text form, for converters that name their output freely. Asked only after [canonical]
     * missed, so a name like `stdlib_fmb.xml` or `misc.pld` belongs to another module by
     * construction — a wrapper that re-exported a dependency on the way to failing must not
     * have that file taken for this module's and renamed away from its owner.
     */
    fun freelyNamed(predicate: (String) -> Boolean): Path? =
        newestProduced { predicate(it) && ModuleType.matchConverted(it) == null }

    /** A file this run produced under another module's text-form name, for the failure message. */
    fun asAnotherModule(key: ModuleKey): Path? = newestProduced {
        ModuleType.matchConverted(it) != null && !it.equals(key.convertedFileName, ignoreCase = true)
    }

    /** Full-precision mtime plus size: a rewrite changes at least one of them. */
    private data class FileStamp(val modified: FileTime, val size: Long) {
        companion object {
            /** `null` when [file] is no longer there, or is not a regular file. */
            fun of(file: Path): FileStamp? = try {
                file.readAttributes<BasicFileAttributes>()
                    .takeIf { it.isRegularFile }
                    ?.let { FileStamp(it.lastModifiedTime(), it.size()) }
            } catch (_: NoSuchFileException) {
                null
            }
        }
    }

    companion object {
        /** Starts a run in [dir], which must exist: call it immediately before launching the tool. */
        fun begin(dir: Path): OutputRun {
            val startedAt = System.currentTimeMillis()
            val before = dir.listDirectoryEntries()
                .mapNotNull { file -> FileStamp.of(file)?.let { file.name to it } }
                // Only a file that could pass for fresh needs remembering — an older one fails
                // produced() anyway — which keeps this small in a directory of thousands of modules.
                .filter { (_, stamp) -> stamp.modified.toMillis() >= startedAt - ConversionOutput.STALE_GRACE_MILLIS }
                .toMap()
            return OutputRun(dir, startedAt, before)
        }
    }
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
