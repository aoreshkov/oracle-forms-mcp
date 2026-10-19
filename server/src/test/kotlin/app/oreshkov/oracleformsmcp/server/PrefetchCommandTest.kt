package app.oreshkov.oracleformsmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.record
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `--prefetch` end to end, through the real program: copy-mode over the sample forms, so no Oracle
 * installation is needed (the injected environment has no `ORACLE_HOME`).
 */
class PrefetchCommandTest {

    private val temp: Path = Files.createTempDirectory("prefetch-command")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val modules = listOf("orders_fmb.xml", "entry_fmb.xml", "utils.pld")

    init {
        modules.forEach { name ->
            val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
            resource.use { Files.copy(it, formsDir.resolve(name)) }
        }
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun prefetch(vararg extra: String) =
        program(emptyMap<String, String>()::get)
            .record("$formsDir", "--cache-dir", "${temp.resolve("cache")}", *extra)
            .assertPipeSafe()

    @Test
    fun theSummaryIsOnStdoutAndEachModuleOnStderr() {
        val result = prefetch("--prefetch-all")
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("Prefetched 3 module(s): 3 fetched, 0 already current, 0 failed.\n", result.stdout)
        assertContains(result.stderr, "[3/3] ")
        assertContains(result.stderr, ": fetched")
    }

    @Test
    fun jsonGivesAMachineReadableSummaryAndARerunFindsEverythingCurrent() {
        assertEquals(ExitStatus.OK, prefetch("--prefetch-all").status)
        val rerun = prefetch("--prefetch-all", "--json")
        assertEquals(ExitStatus.OK, rerun.status, rerun.stderr)
        assertContains(rerun.stderr, "[3/3] ", message = "progress stays on stderr under --json")
        val summary = Json.parseToJsonElement(rerun.stdout).jsonObject
        assertEquals(3, summary.getValue("modules").jsonPrimitive.int)
        assertEquals(0, summary.getValue("fetched").jsonPrimitive.int)
        assertEquals(3, summary.getValue("current").jsonPrimitive.int)
        assertEquals(0, summary.getValue("failed").jsonArray.size)
    }

    @Test
    fun aTypeAloneLimitsTheRunToThatType() {
        val result = prefetch("--prefetch-type", "library", "--json")
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals(1, Json.parseToJsonElement(result.stdout).jsonObject.getValue("modules").jsonPrimitive.int)
    }
}
