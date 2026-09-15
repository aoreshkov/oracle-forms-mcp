package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns an operator-supplied command value (`--convert-command`, `--compile-command`) into the argv
 * list of the process to run.
 *
 * The value is a **command with its parameters**, not merely an executable: sites wrap the Forms
 * tools in an interpreter, a container, or a compatibility layer, and every one of those needs
 * arguments of its own (`wine frmf2xml.exe`, `docker run --rm -v ... image convert`). Only one
 * string reaches this code — the flag, the `OFMCP_CONVERT_COMMAND` variable, and the `user_config`
 * entries of the MCPB bundle and the Claude Code plugin are all single strings — so the split into
 * argv happens here, **never by handing the value to a shell**. The command is operator
 * configuration and the module path is derived from the scanned forms directory, but running a
 * shell would still turn every quoting mistake into a second, silent command.
 *
 * Two syntaxes are accepted:
 *
 * - **JSON array** — `["wine", "C:\\tools\\conv.exe", "--xml"]` — one element per argument, with
 *   no quoting rules beyond JSON's own. Unambiguous, and the same shape MCP clients use for
 *   `command`/`args`; prefer it when a path contains spaces *and* the command takes arguments.
 * - **Quoted string** — `conv.bat --xml` — split on whitespace, with `"..."` and `'...'` grouping
 *   a run that contains spaces. A backslash is **literal**, never an escape, so Windows paths
 *   survive verbatim; a path containing spaces has to be quoted.
 *
 * A value that names an existing file is always taken whole, spaces and all. That keeps every
 * configuration written against the older "one executable" contract working, including the
 * unquoted `C:\Program Files\...` ones that the tokenizer would otherwise split.
 *
 * The module path goes wherever [SOURCE_PLACEHOLDER] appears, and is appended as the last argument
 * when it appears nowhere — which is what the older contract did, so wrappers written for it need
 * no change. The canonical output path goes wherever [OUTPUT_PLACEHOLDER] appears, and is **never**
 * appended: a command without it writes into its working directory, as it always has. It exists
 * for tools that cannot be pointed at a working directory — `frmcmp` writes its `.pld` next to the
 * *module* unless it is given an output file.
 */
internal object ConvertCommandSpec {

    /** Stands for the absolute path of the module being converted, `xargs -I`-style. */
    const val SOURCE_PLACEHOLDER: String = "{}"

    /** Stands for the absolute path the text form is to be written to, e.g. `<dir>/utils.pld`. */
    const val OUTPUT_PLACEHOLDER: String = "{out}"

    /** Both placeholders in one pattern, so substitution is a single pass over each argument. */
    private val PLACEHOLDER = Regex("""\{(?:out)?\}""")

    /**
     * Parses [spec] into a non-empty argv template, or throws with a message naming the fix — and
     * naming [option], the setting the value came from.
     */
    fun parse(spec: String, option: ConverterOption = ConverterOption.CONVERT): List<String> {
        val trimmed = spec.trim()
        return when {
            trimmed.isEmpty() -> throw invalid(option, spec, "it is empty")
            trimmed.startsWith("[") -> parseJsonArray(option, spec, trimmed)
            // The pre-existing "value is one executable" contract, kept verbatim for paths with
            // spaces: an existing file is never a command line that happens to look like one.
            isExistingFile(trimmed) -> listOf(trimmed)
            else -> tokenize(option, spec, trimmed)
        }
    }

    /**
     * Applies [template] to one module: [sourcePath] replaces [SOURCE_PLACEHOLDER] (or is appended
     * when no argument carries it) and [outputPath] replaces [OUTPUT_PLACEHOLDER] (never appended).
     *
     * Both are replaced in one pass, so a path that itself contains `{}` or `{out}` is inserted
     * verbatim rather than substituted again; the lambda form of `replace` also keeps `$` and a
     * backslash in a path literal.
     */
    fun argvFor(template: List<String>, sourcePath: String, outputPath: String): List<String> {
        val argv = template.map { arg ->
            PLACEHOLDER.replace(arg) { if (it.value == SOURCE_PLACEHOLDER) sourcePath else outputPath }
        }
        return if (template.any { SOURCE_PLACEHOLDER in it }) argv else argv + sourcePath
    }

    private fun parseJsonArray(option: ConverterOption, spec: String, trimmed: String): List<String> {
        val elements = try {
            Json.parseToJsonElement(trimmed) as? JsonArray
                ?: throw invalid(option, spec, "it starts with '[' but is not a JSON array")
        } catch (e: IllegalArgumentException) {
            throw invalid(option, spec, "it starts with '[' but is not valid JSON (${e.message})")
        }
        val argv = elements.map { element ->
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                throw invalid(option, spec, "the JSON array element $element is not a string")
            }
            primitive.content
        }
        if (argv.isEmpty()) throw invalid(option, spec, "the JSON array is empty")
        return argv
    }

    /**
     * Splits on whitespace, honouring `"` and `'` groups. Quotes are removed and may open and close
     * mid-token (`--out="my dir"`); a backslash is an ordinary character, so no Windows path needs
     * doubling. An empty quoted group is a real, empty argument.
     */
    private fun tokenize(option: ConverterOption, spec: String, trimmed: String): List<String> {
        val argv = mutableListOf<String>()
        val token = StringBuilder()
        var inToken = false
        var quote: Char? = null
        for (ch in trimmed) {
            when {
                ch == quote -> quote = null
                quote != null -> token.append(ch)
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    inToken = true
                }
                ch.isWhitespace() -> {
                    if (inToken) {
                        argv += token.toString()
                        token.setLength(0)
                        inToken = false
                    }
                }
                else -> {
                    token.append(ch)
                    inToken = true
                }
            }
        }
        if (quote != null) throw invalid(option, spec, "it has an unterminated $quote quote")
        if (inToken) argv += token.toString()
        return argv
    }

    private fun isExistingFile(value: String): Boolean =
        try {
            Path.of(value).isRegularFile()
        } catch (_: Exception) {
            // InvalidPathException on Windows for a value carrying argument syntax — that is a
            // command line, not a path.
            false
        }

    private fun invalid(option: ConverterOption, spec: String, problem: String): ConverterNotFoundException =
        ConverterNotFoundException(
            "${option.flag} (or ${option.envVar}) is set to '$spec' but $problem. " +
                "Give the command and its arguments either as one string, quoting any part that " +
                "contains spaces (\"C:\\Program Files\\tools\\conv.bat\" -xml), or as a JSON " +
                "array ([\"wine\", \"conv.exe\", \"-xml\"]). Write $SOURCE_PLACEHOLDER where the " +
                "module path belongs; it is appended as the last argument when omitted. Write " +
                "$OUTPUT_PLACEHOLDER where a tool that does not write into its working directory " +
                "takes its output file.",
        )
}
