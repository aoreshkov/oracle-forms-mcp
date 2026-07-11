package app.oreshkov.oracleformsmcp.convert

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs one external process with merged, bounded output capture and a hard timeout.
 * stdout+stderr are drained concurrently so a chatty tool can never dead-lock on a full pipe.
 */
internal object ExternalTool {

    private const val MAX_CAPTURED_LINES = 200
    private const val MAX_LINE_LENGTH = 500

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
    ): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .also { it.environment().putAll(extraEnv) }
            .start()

        val captured = ArrayDeque<String>()
        val drainer = launch {
            runInterruptible {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(captured) {
                        if (captured.size >= MAX_CAPTURED_LINES) captured.removeFirst()
                        captured.addLast(line.take(MAX_LINE_LENGTH))
                    }
                }
            }
        }

        val finished = try {
            runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        } catch (t: Throwable) {
            process.destroyForcibly()
            throw t
        }
        if (!finished) process.destroyForcibly()
        drainer.join()

        val output = synchronized(captured) { captured.joinToString("\n") }
        Result(
            exitCode = if (finished) process.exitValue() else null,
            output = output,
            timedOut = !finished,
        )
    }
}
