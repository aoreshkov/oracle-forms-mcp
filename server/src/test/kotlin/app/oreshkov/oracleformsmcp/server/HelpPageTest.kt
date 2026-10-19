package app.oreshkov.oracleformsmcp.server

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The help page is the operator's whole manual for the flags, so a change to it is pinned in a
 * golden file and shows up as a reviewed diff. Regenerate with `-Pgolden.update=true` and read the
 * diff before committing it.
 */
class HelpPageTest {

    @Test
    fun theHelpPageMatchesItsGoldenFile() {
        val file = GOLDEN_DIR.resolve("help-root.txt")
        val actual = USAGE.replace("\r\n", "\n") + "\n"
        if (System.getProperty(UPDATE_PROPERTY).toBoolean()) {
            file.parent.createDirectories()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("missing golden file $file; generate it with -P$UPDATE_PROPERTY=true")
        assertEquals(file.readText().replace("\r\n", "\n"), actual, "the help page changed; regenerate with -P$UPDATE_PROPERTY=true and review the diff")
    }

    private companion object {
        val GOLDEN_DIR: Path = Path.of("src", "test", "resources", "golden")
        const val UPDATE_PROPERTY = "golden.update"
    }
}
