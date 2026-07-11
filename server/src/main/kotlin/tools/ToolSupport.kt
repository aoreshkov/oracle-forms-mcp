package app.oreshkov.oracleformsmcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/*
 * Shared plumbing for the tools, so each tool file is a declarative adapter:
 * parse args → call FormsService → serialize a core DTO. No business logic here or in tools.
 */

/** One JSON encoder for every tool response; pretty output reads well in MCP clients. */
internal val toolJson = Json { prettyPrint = true }

/**
 * Serializes a DTO once and returns it both ways the spec recommends: human-readable JSON text
 * (for clients without structured-output support) and `structuredContent` matching the tool's
 * `outputSchema` (see [outputSchemaOf]).
 */
internal inline fun <reified T> toolResult(value: T): CallToolResult {
    val json = toolJson.encodeToJsonElement(value)
    return CallToolResult(
        content = listOf(TextContent(toolJson.encodeToString(json))),
        structuredContent = json as? JsonObject,
    )
}

// --- behavior annotations (hints surfaced in tools/list) ---

/** Reads only the local forms dir/cache: no side effects, closed domain. */
internal val LOCAL_READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

/**
 * Runs a tool body, turning expected failures (bad arguments, un-fetched module, conversion
 * errors, IO) into an `isError` result the model can read and act on, per the MCP tool-error
 * convention.
 */
internal suspend fun guarded(block: suspend () -> CallToolResult): CallToolResult =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CallToolResult(content = listOf(TextContent(e.message ?: e.toString())), isError = true)
    }

// --- input schema helpers ---

internal fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal const val MODULE_DESCRIPTION: String =
    "Module name, optionally with extension: 'ORDERS' or 'ORDERS.fmb'. The extension " +
        "(fmb/mmb/pll/olb) is only required when the same name exists as several module types."

/** Schema with the shared `module` property plus [extraProps]; [extraRequired] adds to `required`. */
internal fun moduleSchema(
    extraProps: Map<String, JsonObject> = emptyMap(),
    extraRequired: List<String> = emptyList(),
): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("module", stringProp(MODULE_DESCRIPTION))
        extraProps.forEach { (name, prop) -> put(name, prop) }
    },
    required = listOf("module") + extraRequired,
)

/** Schema for tools that take no arguments. */
internal fun emptySchema(): ToolSchema = ToolSchema(properties = JsonObject(emptyMap()))

// --- argument parsing ---

internal fun CallToolRequest.args(): JsonObject = arguments ?: JsonObject(emptyMap())

internal fun JsonObject.stringArg(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.requireStringArg(name: String): String =
    stringArg(name) ?: throw IllegalArgumentException("Missing required argument '$name'")

internal fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.booleanArg(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** The raw `module` argument; resolution to a [ModuleKey] happens in `FormsService`. */
internal fun JsonObject.moduleArg(): String = requireStringArg("module")
