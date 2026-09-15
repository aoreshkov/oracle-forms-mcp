package app.oreshkov.oracleformsmcp.server

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
