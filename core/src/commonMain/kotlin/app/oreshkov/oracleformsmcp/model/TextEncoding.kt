package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a PL/SQL body reached the index as written, or had to be recovered on the way in.
 *
 * Some converted files arrive **doubly escaped**: a newline was written as `&amp;#10;` rather than
 * `&#10;`, so the XML parser decodes it once and the body still holds the literal characters
 * `&#10;`. Left alone that is not merely ugly — the whole body is one physical line, so its line
 * count is 1, every `SourceRef` range collapses, and a line-oriented search can only ever report
 * line 1 of a file with one very long line.
 *
 * The recovery is guarded (see the parser's decode step) but not provable: a body that genuinely
 * contained those five characters inside a string literal is indistinguishable from one that was
 * escaped twice. [RECOVERED] is therefore recorded rather than assumed away — the transformation
 * is visible to whoever reads the text, which is the only honest way to do it.
 */
@Serializable
@SerialName("TextEncoding")
public enum class TextEncoding {
    /** The converted file's own text, decoded once by the XML parser as usual. */
    ORIGINAL,

    /** Numeric character references left over from a doubly-escaped file were decoded. */
    RECOVERED,
}
