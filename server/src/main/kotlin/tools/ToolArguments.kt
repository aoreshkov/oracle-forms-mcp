package app.oreshkov.oracleformsmcp.server.tools

import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Tool registration with the argument check every tool needs and none should re-implement, and the
 * tally of the errors tools return. Plumbing, like ToolSupport: nothing here knows what a tool does.
 */

/**
 * Registers a tool exactly as [Server.addTool] does, plus two things every tool gets from here:
 *
 * - **Its arguments are checked against its own [inputSchema] before [handler] runs.** The spec
 *   says servers MUST validate tool inputs, and the SDK's [ToolSchema] cannot carry
 *   `additionalProperties: false`, so nothing else rejects an argument a tool does not have — it
 *   was dropped, and the caller then told to supply what it had already supplied under another
 *   name. Every problem is reported at once (unknown, missing, not one of the permitted values),
 *   each with what the tool accepts instead, because a check that names one problem per call costs
 *   a round trip per problem. See [argumentProblems].
 * - **Every `isError` result is counted** by tool and by the argument names it carried
 *   ([ToolErrorTally]) — the number that says which description is costing calls.
 *
 * [example] is one literal call, appended to the description and repeated in an argument error:
 * MCP's `Tool` has no `input_examples` member, and one worked call removes retries that a list of
 * argument names does not.
 */
internal fun Server.addCheckedTool(
    name: String,
    description: String,
    inputSchema: ToolSchema,
    title: String,
    outputSchema: ToolSchema,
    toolAnnotations: ToolAnnotations,
    meta: JsonObject? = null,
    example: String? = null,
    handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult,
) {
    addTool(
        name = name,
        description = if (example == null) description else "$description Example: $example",
        inputSchema = inputSchema,
        title = title,
        outputSchema = outputSchema,
        toolAnnotations = toolAnnotations,
        meta = meta,
    ) { request ->
        val args = request.args()
        val problems = argumentProblems(inputSchema, args)
        val result = if (problems.isEmpty()) {
            handler(request)
        } else {
            CallToolResult(
                content = listOf(TextContent(argumentError(name, inputSchema, problems, example))),
                isError = true,
            )
        }
        if (result.isError == true) ToolErrorTally.record(name, args.keys, result)
        result
    }
}

/**
 * Everything wrong with [args] against [schema], in the order a caller fixes it: arguments the
 * tool does not have (each with the one it most likely meant), required ones that are absent (each
 * with its description and permitted values), and values outside an `enum`. Empty when the call
 * may proceed.
 *
 * Absent means what the handlers' own parsing means by it — no key, `null`, or a blank string — so
 * this never passes a call the handler would then fail as "missing". Requirements a schema cannot
 * state ("either `uri` or `file`") stay with the handler.
 */
internal fun argumentProblems(schema: ToolSchema, args: JsonObject): List<String> {
    val props = schema.properties ?: JsonObject(emptyMap())
    val missing = schema.required.orEmpty().filter { args[it].isAbsent() }
    val unknown = args.keys.filter { it !in props }
    return buildList {
        unknown.forEach { arg ->
            val meant = likelyMeant(arg, props.keys, missing, unknown.size)
            add(
                "unknown argument '$arg'" +
                    (meant?.let { " — did you mean '$it'?" } ?: " — this tool has no such argument"),
            )
        }
        missing.forEach { arg ->
            val prop = props[arg] as? JsonObject
            add("missing required argument '$arg'" + (prop?.let { " — ${describe(it)}" } ?: ""))
        }
        args.forEach { (arg, value) ->
            val allowed = (props[arg] as? JsonObject)?.enumValues() ?: return@forEach
            val raw = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            if (allowed.none { it.equals(raw, ignoreCase = true) }) {
                add("'$arg' is '$raw', which is not one of: ${allowed.joinToString(", ")}")
            }
        }
    }
}

/** The whole argument error: every problem, what the tool takes, and its example call. */
private fun argumentError(tool: String, schema: ToolSchema, problems: List<String>, example: String?): String {
    val required = schema.required.orEmpty().toSet()
    val takes = schema.properties?.keys.orEmpty()
        .joinToString(", ") { if (it in required) "$it (required)" else it }
    return buildString {
        append("$tool was not run — ")
        append(if (problems.size == 1) "one argument problem" else "${problems.size} argument problems")
        append(":\n")
        problems.forEach { append("- ").append(it).append('\n') }
        append("$tool takes: ${takes.ifEmpty { "no arguments" }}.")
        example?.let { append("\nExample: ").append(it) }
    }
}

/**
 * The declared argument an unknown [arg] most likely stands for: a case-insensitive match; else,
 * when exactly one argument is unknown and exactly one required one is missing, that one (the
 * `pattern`-for-`query` slip); else the closest name by shared prefix or small edit distance
 * (`elementType` for `elementKind`), a missing one first. `null` when nothing is close — a wrong
 * guess would cost more than none.
 */
private fun likelyMeant(arg: String, declared: Set<String>, missing: List<String>, unknownCount: Int): String? {
    declared.firstOrNull { it.equals(arg, ignoreCase = true) }?.let { return it }
    if (unknownCount == 1 && missing.size == 1) return missing.single()
    val a = arg.lowercase()
    return (missing + (declared - missing.toSet()))
        .map { it to it.lowercase() }
        .filter { (_, d) -> a.commonPrefixWith(d).length >= MIN_SHARED_PREFIX || editDistance(a, d) <= MAX_TYPO_EDITS }
        .maxByOrNull { (_, d) -> a.commonPrefixWith(d).length * PREFIX_WEIGHT - editDistance(a, d) }
        ?.first
}

private const val MIN_SHARED_PREFIX = 4
private const val MAX_TYPO_EDITS = 2
private const val PREFIX_WEIGHT = 10

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}

private fun JsonElement?.isAbsent(): Boolean = when (this) {
    null, is JsonNull -> true
    is JsonPrimitive -> isString && content.isBlank()
    else -> false
}

private fun JsonObject.enumValues(): List<String>? =
    (this["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** A property's description, with its permitted values when the description does not state them. */
private fun describe(prop: JsonObject): String {
    val text = (prop["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    val values = prop.enumValues()
    return if (values == null || text.contains("One of:")) text else "${text.trimEnd('.')}. One of: ${values.joinToString(", ")}."
}

/**
 * Process-wide count of `isError` tool results, by tool and by call shape (the tool plus the
 * argument names the failing call carried). A tool error goes back to the model and is otherwise
 * gone; counted, it says which tool's description or error text is costing calls — the metric
 * Anthropic's tool-writing guidance says to collect alongside accuracy. Each error is logged
 * (stderr, never stdout) with its running count, and [summary] once when the server closes.
 */
internal object ToolErrorTally {
    private val log = Logger.withTag("ToolErrors")
    private val byTool = ConcurrentHashMap<String, AtomicInteger>()
    private val byCall = ConcurrentHashMap<String, AtomicInteger>()

    fun record(tool: String, argNames: Set<String>, result: CallToolResult) {
        val call = "$tool(${argNames.sorted().joinToString(",")})"
        val n = byTool.computeIfAbsent(tool) { AtomicInteger() }.incrementAndGet()
        byCall.computeIfAbsent(call) { AtomicInteger() }.incrementAndGet()
        val message = (result.content.firstOrNull() as? TextContent)?.text.orEmpty()
            .lineSequence().firstOrNull().orEmpty().take(MAX_LOGGED_MESSAGE_CHARS)
        log.i { "tool error #$n from $call: $message" }
    }

    /** Errors so far, by tool name. */
    fun byTool(): Map<String, Int> = byTool.mapValues { it.value.get() }

    /** Errors so far, by call shape, e.g. `read_source(startLine,uri)`. */
    fun byCall(): Map<String, Int> = byCall.mapValues { it.value.get() }

    /** One line for the log, or `null` when nothing has failed. */
    fun summary(): String? {
        val calls = byCall()
        if (calls.isEmpty()) return null
        return "tool errors this process: ${calls.values.sum()} — " +
            calls.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
    }

    private const val MAX_LOGGED_MESSAGE_CHARS = 200
}
