package app.oreshkov.oracleformsmcp.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The most characters one tool result may carry as JSON text, declared to the client per tool
 * through `_meta["anthropic/maxResultSizeChars"]` (see `LARGE_RESULT_META`).
 *
 * The server can count characters but not a client's tokens, and the two diverge most on exactly
 * the content this server serves: converted XML tokenizes far denser than prose. Declaring the
 * ceiling in characters turns Claude Code's limit for those tools from a token estimate into the
 * same number the server enforces, so a result that fits here is never spilled to a file there.
 * Only tools whose output is capped below it declare it — declaring it on an uncapped tool would
 * *lower* that tool's limit, not raise it.
 */
internal const val MAX_RESULT_CHARS: Int = 60_000

/**
 * What a row-shaped result leaves for everything that is not rows: the module key, the hints, the
 * annotations, the resource link, and the JSON around it all.
 */
internal const val RESULT_OVERHEAD_CHARS: Int = 6_000

/** One JSON encoder for every tool response, and for measuring what one costs. */
internal val resultJson: Json = Json { prettyPrint = false }

/**
 * Spends a result's character budget across the lists it carries, in the order they matter.
 *
 * A wide block is the case that needs it: 150 detailed items, their resolved properties and a
 * 350-column base table serialize to more than any client accepts, and each of the three is capped
 * on its own row count only. Rows are measured as they will be serialized, so the budget is spent
 * in the same units the client counts.
 */
internal class RowBudget(private var remaining: Int) {

    /** The rows of [rows] that fit in [share] of the budget, and whether any were left behind. */
    fun <T> take(rows: List<T>, serializer: KSerializer<T>, share: Int = remaining): Pair<List<T>, Boolean> {
        var allowance = minOf(share, remaining)
        val kept = mutableListOf<T>()
        for (row in rows) {
            val cost = resultJson.encodeToString(serializer, row).length + 1 // plus the separating comma
            if (cost > allowance) return kept to true
            allowance -= cost
            remaining -= cost
            kept += row
        }
        return kept to false
    }

    /** [share] as a percentage of what is left, for a section that must not crowd out the rest. */
    fun share(percent: Int): Int = remaining * percent / 100
}

/**
 * How many characters [text] occupies once written as a JSON string: quotes, backslashes and the
 * short-escaped control characters take two, every other control character six (`\u00XX`).
 *
 * The budgets are counted this way because a tool result travels as JSON text. Converted XML is
 * attribute-dense — a line of one item runs to hundreds of quotes — so a budget counted on the raw
 * text undercounts precisely where a response is largest.
 */
internal fun jsonEscapedLength(text: String): Int {
    var length = 0
    for (c in text) length += jsonEscapedWidth(c)
    return length
}

/**
 * The longest prefix of [text], in raw characters, whose JSON-escaped form fits in [budget]. Equal
 * to `text.length` when the whole text fits. Never splits a surrogate pair.
 */
internal fun jsonEscapedPrefixLength(text: String, budget: Int): Int {
    var used = 0
    for ((i, c) in text.withIndex()) {
        used += jsonEscapedWidth(c)
        if (used > budget) return if (i > 0 && text[i - 1].isHighSurrogate()) i - 1 else i
    }
    return text.length
}

private fun jsonEscapedWidth(c: Char): Int = when {
    c == '"' || c == '\\' -> 2
    c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '' -> 2
    c < ' ' -> 6
    else -> 1
}
