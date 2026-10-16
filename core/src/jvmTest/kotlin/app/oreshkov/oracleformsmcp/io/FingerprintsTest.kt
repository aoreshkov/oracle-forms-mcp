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

    /**
     * A touch is confirmed by hashing once, not on every read after it: nothing re-stamps the
     * cached fingerprint with the new mtime, so without a memo every index read hashes the whole
     * file until a re-fetch. The memo trusts a *settled* size+mtime exactly as the stamped
     * fingerprint does, which is what lets this test see it: a same-size write that puts the
     * confirmed mtime back is not re-hashed, and moving the mtime again is.
     */
    @Test
    fun aSettledTouchIsNotHashedAgainUntilTheFileChangesAgain() {
        val cached = Fingerprints.of(file)
        file.moveMtime(-60_000)
        val touched = Files.getLastModifiedTime(file)
        assertTrue(Fingerprints.matches(cached, file), "the touch is confirmed by its hash")

        file.writeText("<Module name=\"OTHERS\"/>")
        file.setLastModifiedTime(touched)
        assertTrue(Fingerprints.matches(cached, file), "same size and settled mtime as the confirmed state: not hashed again")

        file.moveMtime(-60_000)
        assertFalse(Fingerprints.matches(cached, file), "a new mtime is hashed afresh, and the edit is seen")
    }

    /**
     * A mtime as recent as the hash may still be the tick a same-length edit lands in on a coarse
     * file system, so that state is never remembered: the edit is seen by the next read.
     */
    @Test
    fun aRacilyRecentTouchIsHashedAgainOnTheNextRead() {
        val cached = Fingerprints.of(file)
        val recent = FileTime.fromMillis(System.currentTimeMillis() + 60_000)
        file.setLastModifiedTime(recent)
        assertTrue(Fingerprints.matches(cached, file), "the touch is confirmed by its hash")

        file.writeText("<Module name=\"OTHERS\"/>")
        file.setLastModifiedTime(recent)
        assertFalse(Fingerprints.matches(cached, file), "the same racy size+mtime is hashed again, and the edit is seen")
    }

    @Test
    fun aMissingFileDoesNotMatch() {
        val cached = Fingerprints.of(file)
        file.deleteExisting()
        assertFalse(Fingerprints.matches(cached, file))
    }
}
