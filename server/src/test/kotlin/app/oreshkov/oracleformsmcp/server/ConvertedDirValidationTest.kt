package app.oreshkov.oracleformsmcp.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue

/**
 * `--converted-dir` is written into under the very names the scanner reads pre-converted modules
 * by, so the one directory it must never be is the forms directory — under any lexical spelling of
 * its path, and through any link to it.
 */
class ConvertedDirValidationTest {

    private val temp: Path = Files.createTempDirectory("converted-dir-validation-test")
    private val formsDir: Path = temp.resolve("forms").createDirectories()
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun rejected(dir: Path): String =
        assertFailsWith<IllegalArgumentException>(dir.toString()) { validateConvertedDir(dir, formsDir) }.message.orEmpty()

    @Test
    fun theFormsDirectoryItselfIsRejected() {
        assertContains(rejected(formsDir), "must not be the forms directory")
    }

    /** An alias that only normalising reveals is the same directory, and is refused the same way. */
    @Test
    fun anAliasOfTheFormsDirectoryIsRejected() {
        temp.resolve("forms").resolve("sub").createDirectories()
        assertContains(rejected(formsDir.resolve(".")), "must not be the forms directory")
        assertContains(rejected(formsDir.resolve("sub").resolve("..")), "must not be the forms directory")
        assertContains(rejected(temp.resolve("other").resolve("..").resolve("forms")), "must not be the forms directory")
    }

    /**
     * A link is another spelling no normalising reveals: the output would still land in the forms
     * directory, under the names the scanner reads. A symlink where the OS allows one, else (on
     * Windows without the privilege) a junction, which needs none.
     */
    @Test
    fun aLinkToTheFormsDirectoryIsRejected() {
        val link = temp.resolve("forms-link")
        val linked = runCatching { Files.createSymbolicLink(link, formsDir) }.isSuccess ||
            (isWindows && ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), formsDir.toString())
                .redirectErrorStream(true).start().waitFor() == 0)
        assumeTrue("this host can create neither a symlink nor a junction", linked && link.isDirectory())
        assertContains(rejected(link), "must not be the forms directory")
    }

    @Test
    fun anExistingFileIsRejected() {
        val file = temp.resolve("forms-xml").also { it.writeText("not a directory") }
        assertContains(rejected(file), "existing file")
    }

    /** The scanner does not recurse, so a subdirectory holds nothing it reads as a module. */
    @Test
    fun aSubdirectoryOfTheFormsDirectoryIsAccepted() {
        val sub = formsDir.resolve("xml")
        assertEquals(sub.toAbsolutePath().normalize(), validateConvertedDir(sub, formsDir))
    }

    /** It is created at the first conversion, so not existing yet is fine — and it comes back normalised. */
    @Test
    fun aDirectoryThatDoesNotExistYetIsAcceptedNormalised() {
        val later = temp.resolve("not-yet").resolve(".").resolve("forms-xml")
        assertEquals(temp.resolve("not-yet").resolve("forms-xml").toAbsolutePath().normalize(), validateConvertedDir(later, formsDir))
    }
}
