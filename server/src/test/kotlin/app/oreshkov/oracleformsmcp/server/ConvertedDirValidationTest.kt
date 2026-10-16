package app.oreshkov.oracleformsmcp.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `--converted-dir` is written into under the very names the scanner reads pre-converted modules
 * by, so the one directory it must never be is the forms directory — under any lexical spelling of
 * its path. The check normalises, it does not resolve links: a symlink or junction to the forms
 * directory is not caught here.
 */
class ConvertedDirValidationTest {

    private val temp: Path = Files.createTempDirectory("converted-dir-validation-test")
    private val formsDir: Path = temp.resolve("forms").createDirectories()

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
