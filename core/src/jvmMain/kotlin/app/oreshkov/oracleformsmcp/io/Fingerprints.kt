package app.oreshkov.oracleformsmcp.io

import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
     * Whether [cached] still describes [path]. Size+mtime match short-circuits to `true`; a size
     * mismatch is `false` without hashing; a bare mtime change (e.g. `touch`) is confirmed with
     * sha256 so it doesn't force a reconvert. A missing file is always `false`.
     */
    public fun matches(cached: ModuleFingerprint, path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (path.fileSize() != cached.sizeBytes) return false
        if (path.getLastModifiedTime().toMillis() == cached.lastModifiedMillis) return true
        return sha256(path) == cached.sha256
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
