package app.oreshkov.oracleformsmcp.convert

import co.touchlab.kermit.Logger
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs one external process with merged, bounded output capture and a hard timeout.
 * stdout+stderr are drained concurrently so a chatty tool can never dead-lock on a full pipe.
 *
 * The timeout bounds the whole call, not just the wait for the direct child. Every converter runs
 * under a wrapper — a `.bat` under cmd.exe, a shell script, wine, docker — whose own child does the
 * work and inherits the output pipe, so killing the wrapper alone leaves a reader that never sees
 * end of output, and the caller holds the module's fetch lock (and `--converted-dir`'s shared one)
 * for as long as it waits. Hence the tree kill, and a drain that is waited on for [DRAIN_GRACE] and
 * then abandoned rather than joined.
 */
internal object ExternalTool {

    private const val MAX_CAPTURED_LINES = 200
    private const val MAX_LINE_LENGTH = 500

    /** How long the output is drained once the process has ended before the rest is abandoned. */
    val DRAIN_GRACE: Duration = 5.seconds

    private val log = Logger.withTag("ExternalTool")

    data class Result(
        /** `null` when the process was killed on timeout. */
        val exitCode: Int?,
        /** Last [MAX_CAPTURED_LINES] lines of merged stdout+stderr. */
        val output: String,
        val timedOut: Boolean,
    )

    suspend fun run(
        command: List<String>,
        workingDir: Path,
        timeout: Duration,
        extraEnv: Map<String, String> = emptyMap(),
        drainGrace: Duration = DRAIN_GRACE,
    ): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .also { it.environment().putAll(extraEnv) }
            .start()

        val captured = ArrayDeque<String>()
        // A plain thread, not a child coroutine: a blocked pipe read cannot be interrupted, and a
        // child coroutine stuck in one would keep this scope from completing at all.
        val drainer = thread(isDaemon = true, name = "external-tool-output") {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(captured) {
                        if (captured.size >= MAX_CAPTURED_LINES) captured.removeFirst()
                        captured.addLast(line.take(MAX_LINE_LENGTH))
                    }
                }
            } catch (_: IOException) {
                // The stream was closed under the read after the drain was abandoned.
            }
        }

        val finished = try {
            runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        } catch (t: Throwable) {
            killTree(process)
            abandonDrain(process, drainer)
            throw t
        }
        if (!finished) killTree(process)
        drainer.join(drainGrace.inWholeMilliseconds)
        if (drainer.isAlive) {
            // A process that escaped the tree kill — or a background child of a run that exited
            // normally — still holds the pipe. It is not ours to wait for.
            log.w { "Output of ${command.first()} still open $drainGrace after it ended; no longer reading it" }
            abandonDrain(process, drainer)
        }

        val output = synchronized(captured) { captured.joinToString("\n") }
        Result(
            exitCode = if (finished) process.exitValue() else null,
            output = output,
            timedOut = !finished,
        )
    }

    /**
     * Kills [process] and every process it started. The tree is listed before anything is killed:
     * once the wrapper is gone its children re-parent and are no longer its descendants. The wrapper
     * goes first so it cannot start another child after the list was taken.
     */
    private fun killTree(process: Process) {
        val descendants = process.descendants().toList()
        process.destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
    }

    /**
     * Ends [drainer]'s read by closing the pipe under it. The close runs on its own daemon thread:
     * closing a pipe handle that another thread is blocked reading is not guaranteed to return on
     * every platform, and the caller must not trade one indefinite wait for another.
     */
    private fun abandonDrain(process: Process, drainer: Thread) {
        if (!drainer.isAlive) return
        thread(isDaemon = true, name = "external-tool-output-close") {
            try {
                process.inputStream.close()
            } catch (_: IOException) {
                // Nothing left to release.
            }
        }
    }
}
