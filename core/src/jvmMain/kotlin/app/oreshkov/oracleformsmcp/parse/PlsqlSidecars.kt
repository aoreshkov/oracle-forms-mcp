package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TextEncoding
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Writes decoded PL/SQL bodies (trigger text, program units, menu commands) as `.sql` sidecar
 * files under `<moduleCacheDir>/plsql/`, so the index JSON stays small and reads/searches are
 * plain file operations.
 */
internal class PlsqlSidecars(private val moduleCacheDir: Path) {

    /** Case-folded `category/name` keys already written by this parse, to detect collisions. */
    private val used = HashSet<String>()

    /**
     * Writes [text] (normalized to `\n`) under `plsql/<category>/<fileName>.sql` and returns a
     * [SourceRef] covering the whole file.
     *
     * Distinct elements can sanitize to the same file name (a block literally named `FORM` vs
     * the form-level fallback scope, case-variant names on case-insensitive filesystems,
     * sanitizer-collapsed characters); colliding names get a deterministic `~2`, `~3`, … suffix
     * in document order instead of silently overwriting an earlier body.
     */
    fun write(category: String, fileName: String, text: String): SourceRef {
        val normalized = text.replace("\r\n", "\n")
        val dir = moduleCacheDir.resolve("plsql").resolve(category).createDirectories()
        val base = sanitizeFileName(fileName)
        var candidate = base
        var n = 2
        while (!used.add("$category/${candidate.uppercase()}")) candidate = "$base~${n++}"
        val file = dir.resolve("$candidate.sql")
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

/**
 * Longest one-line preview kept in the index, matching the snippet cap the search tools apply.
 * A preview is a triage aid; past this it is a body, and `list_triggers(detailed)` on a
 * 158-trigger module would return one.
 */
internal const val PREVIEW_CHARS: Int = 200

/**
 * First non-blank line of [text], trimmed, for one-line previews — cut at [PREVIEW_CHARS] with an
 * ellipsis, because "line" is not a bound.
 *
 * A body that really is one physical line can be any length at all: a minified body, or one whose
 * escaping [decodeDoubleEscaped] could not prove (so it was rightly left alone). `lineCount`
 * carries the true size beside this, so the cut costs nothing and states itself.
 */
internal fun firstCodeLine(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= PREVIEW_CHARS) line else line.take(PREVIEW_CHARS) + "…"
}

/** Number of lines in normalized [text]. */
internal fun lineCountOf(text: String): Int =
    text.replace("\r\n", "\n").lineSequence().count().coerceAtLeast(1)

/** A PL/SQL body as it will be stored, and whether getting it there required a recovery. */
internal class DecodedText(val text: String, val encoding: TextEncoding)

/** Numeric character references still present in a value the XML parser already decoded once. */
private val NUMERIC_ENTITY = Regex("""&#(\d+|[xX][0-9a-fA-F]+);""")

/**
 * Undoes double escaping in a PL/SQL body, when the evidence says that is what happened.
 *
 * A correctly escaped file writes a newline as `&#10;` and the XML parser hands over a real
 * newline. A doubly-escaped one writes `&amp;#10;`, the parser decodes the `&amp;`, and what
 * arrives is the five literal characters `&#10;` — so the entire body is one physical line, its
 * line count is 1, every recorded line range collapses to it, and a line-oriented search has
 * nothing to report but line 1.
 *
 * The guard is the pair of conditions that only hold together for that case: the text carries no
 * real line break at all, *and* decoding its leftover references introduces one. A genuine
 * one-liner is left alone even when it contains `&#9;`, and a multi-line body is never touched.
 *
 * It cannot be proven — a body that really did contain those characters inside a string literal
 * looks the same — which is why the result is labelled [TextEncoding.RECOVERED] and served that
 * way rather than quietly swapped in.
 */
internal fun decodeDoubleEscaped(raw: String): DecodedText {
    if (raw.isEmpty() || raw.contains('\n') || raw.contains('\r')) {
        return DecodedText(raw, TextEncoding.ORIGINAL)
    }
    if (!NUMERIC_ENTITY.containsMatchIn(raw)) return DecodedText(raw, TextEncoding.ORIGINAL)
    val decoded = NUMERIC_ENTITY.replace(raw) { match ->
        val digits = match.groupValues[1]
        val code = if (digits[0] == 'x' || digits[0] == 'X') {
            digits.substring(1).toIntOrNull(16)
        } else {
            digits.toIntOrNull()
        }
        // An out-of-range or unparseable reference is left exactly as it was found.
        if (code == null || code !in 1..0x10FFFF) match.value else String(Character.toChars(code))
    }
    return if (decoded.contains('\n')) {
        DecodedText(decoded, TextEncoding.RECOVERED)
    } else {
        DecodedText(raw, TextEncoding.ORIGINAL)
    }
}
