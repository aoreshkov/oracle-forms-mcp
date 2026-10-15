package app.oreshkov.oracleformsmcp.io

import co.touchlab.kermit.Logger
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlinx.coroutines.delay

/** Retry budget for an atomic move; see [atomicMove]. */
private const val ATOMIC_MOVE_ATTEMPTS = 20
private const val ATOMIC_MOVE_RETRY_DELAY_MS = 5L

private val log = Logger.withTag("AtomicFiles")

/**
 * Replaces [target] with [text] so that a concurrent reader sees either the old file or the new
 * one, never a partial one, and a crash part-way leaves the old file in place.
 *
 * Tool handlers run concurrently, so a reader can open a file while a write is rewriting it. A
 * plain `writeText` truncates first, and that reader decodes half a document. Writing to a temp
 * file in the same directory and renaming it over [target] avoids that. The temp file is deleted
 * in every case, so a failed write leaves nothing behind.
 */
internal suspend fun writeTextAtomically(target: Path, text: String) {
    val tmp = Files.createTempFile(target.parent, target.name, ".tmp")
    try {
        tmp.writeText(text)
        atomicMove(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        tmp.deleteIfExists()
    }
}

/**
 * Renames [source] to [target], which must not exist, with the same retry as
 * [writeTextAtomically] — a reader holding [source] open blocks the rename on Windows just as one
 * holding a replace target does.
 */
internal suspend fun moveRetrying(source: Path, target: Path) {
    atomicMove(source, target)
}

/**
 * Renames [source] to [target] atomically, retrying while the rename is refused.
 *
 * Windows fails an atomic rename with an access error while any reader holds either file open,
 * and those readers are frequent now that tool handlers run concurrently. The naive recovery from
 * a failed replace — a plain [StandardCopyOption.REPLACE_EXISTING] move — is delete-then-rename on
 * Windows, which is precisely the torn state being avoided: a concurrent reader sees no file and
 * takes it for an empty one. Reader handles are short (one `readText`), so retry instead, and
 * reserve the non-atomic move for filesystems that genuinely cannot do atomic renames. A target
 * that already exists without [StandardCopyOption.REPLACE_EXISTING] is not transient, so it is
 * not retried.
 */
private suspend fun atomicMove(source: Path, target: Path, vararg options: CopyOption) {
    var lastFailure: Throwable? = null
    repeat(ATOMIC_MOVE_ATTEMPTS) { attempt ->
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, *options)
            return
        } catch (e: AtomicMoveNotSupportedException) {
            log.d(e) { "Atomic move unsupported at $target; falling back to a plain move" }
            Files.move(source, target, *options)
            return
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (e: IOException) {
            lastFailure = e
            delay(ATOMIC_MOVE_RETRY_DELAY_MS * (attempt + 1))
        }
    }
    throw IOException("Could not move $source to $target after $ATOMIC_MOVE_ATTEMPTS attempts", lastFailure)
}
