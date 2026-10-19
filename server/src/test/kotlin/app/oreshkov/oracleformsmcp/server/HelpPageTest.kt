package app.oreshkov.oracleformsmcp.server

import app.oreshkov.clikit.HelpRenderer
import app.oreshkov.clikit.testing.GoldenFiles
import java.nio.file.Path
import kotlin.test.Test

/**
 * The help page is the operator's whole manual for the flags, so a change to it is pinned in a
 * golden file and shows up as a reviewed diff. It is rendered at a fixed 80 columns, so the file
 * does not depend on the terminal that runs the test. Regenerate with `-Pgolden.update=true` and
 * read the diff before committing it.
 *
 * Only the help page: the tree has no `completion` command, so the completion scripts
 * `GoldenFiles.assertSurface` would also pin are output the server never produces.
 */
class HelpPageTest {

    private val golden = GoldenFiles(Path.of("src", "test", "resources", "golden"), "golden.update")

    @Test
    fun theHelpPageMatchesItsGoldenFile() {
        val path = listOf(commandTree)
        golden.assertMatches(GoldenFiles.helpFileName(path), HelpRenderer().render(path))
        golden.assertNoOrphans(setOf(GoldenFiles.helpFileName(path)))
    }
}
