package app.oreshkov.oracleformsmcp.convert

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlinx.coroutines.test.runTest

class ExternalToolTest {

    private val temp: Path = Files.createTempDirectory("external-tool-test")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun script(batchLines: List<String>, shellLines: List<String>): List<String> =
        listOf(FakeOracleHome.stubScript(temp, "tool", batchLines, shellLines).toString())

    /**
     * The drain is given far longer than the child sleeps, so the call returns early only if the
     * timeout killed the child holding the pipe — not merely the wrapper, and not by giving up on
     * the drain.
     */
    @Test
    fun aTimeoutKillsTheChildHoldingTheOutputPipe() = runTest(timeout = 45.seconds) {
        val command = script(
            listOf("echo started") + FakeOracleHome.sleepingBatch(60),
            listOf("echo started") + FakeOracleHome.sleepingShell(60),
        )

        val (result, elapsed) = measureTimedValue {
            ExternalTool.run(command, temp, timeout = 1.seconds, drainGrace = 60.seconds)
        }

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue("started" in result.output, result.output)
        assertTrue(elapsed < 15.seconds, "returned after $elapsed")
    }

    /**
     * A run that exited on its own is not killed, but a background child it left behind can hold the
     * pipe open for as long as it likes; that is not waited for past the drain grace.
     */
    @Test
    fun aBackgroundChildHoldingThePipeIsNotWaitedFor() = runTest(timeout = 45.seconds) {
        val command = script(
            listOf("start \"\" /b ping -n 31 127.0.0.1 >nul", "echo done"),
            listOf("sleep 30 &", "echo done"),
        )

        val (result, elapsed) = measureTimedValue {
            ExternalTool.run(command, temp, timeout = 20.seconds, drainGrace = 1.seconds)
        }

        assertEquals(0, result.exitCode)
        assertTrue("done" in result.output, result.output)
        assertTrue(elapsed < 15.seconds, "returned after $elapsed")
    }
}
