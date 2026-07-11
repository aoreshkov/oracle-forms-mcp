package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.SourceRef
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Writes decoded PL/SQL bodies (trigger text, program units, menu commands) as `.sql` sidecar
 * files under `<moduleCacheDir>/plsql/`, so the index JSON stays small and reads/searches are
 * plain file operations.
 */
internal class PlsqlSidecars(private val moduleCacheDir: Path) {

    /**
     * Writes [text] (normalized to `\n`) under `plsql/<category>/<fileName>.sql` and returns a
     * [SourceRef] covering the whole file.
     */
    fun write(category: String, fileName: String, text: String): SourceRef {
        val normalized = text.replace("\r\n", "\n")
        val dir = moduleCacheDir.resolve("plsql").resolve(category).createDirectories()
        val file = dir.resolve(sanitizeFileName(fileName) + ".sql")
        file.writeText(normalized)
        val lineCount = normalized.lineSequence().count().coerceAtLeast(1)
        return SourceRef(
            file = cacheRelative(file, moduleCacheDir),
            startLine = 1,
            endLine = lineCount,
        )
    }

    companion object {
        const val TRIGGERS = "triggers"
        const val PROGRAM_UNITS = "program-units"
        const val MENU_ITEMS = "menu-items"
    }
}

/** First non-blank line of [text], trimmed, for one-line previews. */
internal fun firstCodeLine(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** Number of lines in normalized [text]. */
internal fun lineCountOf(text: String): Int =
    text.replace("\r\n", "\n").lineSequence().count().coerceAtLeast(1)
