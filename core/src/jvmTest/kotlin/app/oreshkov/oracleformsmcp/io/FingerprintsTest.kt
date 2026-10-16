package app.oreshkov.oracleformsmcp.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.deleteExisting
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every staleness test elsewhere changes the file's size, so the branch that only sha256 can
 * decide — same size, different mtime — is pinned here, both ways round.
 */
class FingerprintsTest {

    private val dir: Path = Files.createTempDirectory("fingerprints-test")
    private val file: Path = dir.resolve("orders_fmb.xml").also { it.writeText("<Module name=\"ORDERS\"/>") }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun Path.moveMtime(byMillis: Long) =
        setLastModifiedTime(FileTime.fromMillis(Files.getLastModifiedTime(this).toMillis() + byMillis))

    @Test
    fun anUntouchedFileMatches() {
        assertTrue(Fingerprints.matches(Fingerprints.of(file), file))
    }

    /** A `touch`, or a re-export that wrote the same bytes, must not force a reconvert. */
    @Test
    fun aNewMtimeOverTheSameContentStillMatches() {
        val cached = Fingerprints.of(file)
        file.moveMtime(60_000)
        assertTrue(Fingerprints.matches(cached, file))
    }

    /** The edit size+mtime cannot see on their own: same length, so only the hash tells. */
    @Test
    fun aSameLengthEditWithANewMtimeDoesNotMatch() {
        val cached = Fingerprints.of(file)
        file.writeText("<Module name=\"OTHERS\"/>")
        file.moveMtime(60_000)
        check(Files.size(file) == cached.sizeBytes) { "the edit must keep the length for this test to mean anything" }
        assertFalse(Fingerprints.matches(cached, file))
    }

    @Test
    fun aMissingFileDoesNotMatch() {
        val cached = Fingerprints.of(file)
        file.deleteExisting()
        assertFalse(Fingerprints.matches(cached, file))
    }
}
