package app.oreshkov.oracleformsmcp.io

import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream

/** Computes and compares [ModuleFingerprint]s of files on disk. */
public object Fingerprints {

    /** Fingerprint of [path] (must exist). */
    public fun of(path: Path): ModuleFingerprint = ModuleFingerprint(
        sizeBytes = path.fileSize(),
        lastModifiedMillis = path.getLastModifiedTime().toMillis(),
        sha256 = sha256(path),
    )

    /**
     * The hash last computed for each file, with the size and mtime it was computed at. Nothing
     * re-stamps a cached fingerprint after a bare mtime change, so without this every read of a
     * touched module would hash the whole file again until it is re-fetched. One entry per file
     * ever confirmed, so it is bounded by the forms directory.
     *
     * Only a *settled* mtime is remembered — one at least [RACY_SLACK_MILLIS] older than the hash
     * that confirmed it (git's "racily clean" rule). On a file system with a coarse mtime (FAT's
     * 2 s, a share's 1 s) a same-length edit inside the tick just hashed keeps both size and
     * mtime, and remembering that state would hide the edit for as long as the mtime holds.
     */
    private val confirmed = ConcurrentHashMap<Path, ModuleFingerprint>()

    /** How far an mtime must trail the hash that confirmed it before the pair is trusted: FAT's 2 s tick. */
    private const val RACY_SLACK_MILLIS: Long = 2_000

    /**
     * Whether [cached] still describes [path]. Size+mtime match short-circuits to `true`; a size
     * mismatch is `false` without hashing; a bare mtime change (e.g. `touch`) is confirmed with
     * sha256 so it doesn't force a reconvert — once: the hash is remembered against that size and
     * mtime when the mtime has settled, and trusted as far as the stamped fingerprint's are. A
     * missing file is always `false`.
     */
    public fun matches(cached: ModuleFingerprint, path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val size = path.fileSize()
        if (size != cached.sizeBytes) return false
        val mtime = path.getLastModifiedTime().toMillis()
        if (mtime == cached.lastModifiedMillis) return true
        val key = path.toAbsolutePath().normalize()
        confirmed[key]?.takeIf { it.sizeBytes == size && it.lastModifiedMillis == mtime }?.let {
            return it.sha256 == cached.sha256
        }
        val hashedAt = System.currentTimeMillis()
        val sha256 = sha256(path)
        if (hashedAt - mtime >= RACY_SLACK_MILLIS) confirmed[key] = ModuleFingerprint(size, mtime, sha256)
        return sha256 == cached.sha256
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
