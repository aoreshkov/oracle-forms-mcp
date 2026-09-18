package app.oreshkov.oracleformsmcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The shared argument check: what it counts as absent, unknown and invalid has to match what the
 * handlers' own parsing does, or it would pass a call a handler then fails one problem at a time.
 */
class ToolArgumentsTest {

    private val schema: ToolSchema = moduleSchema(
        extraProps = mapOf(
            "query" to stringProp("Text to find"),
            "verbosity" to verbosityProp("is shorter"),
        ),
        extraRequired = listOf("query"),
    )

    @Test
    fun aCompleteCallHasNoProblems() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("verbosity", "DETAILED")
        }
        assertEquals(emptyList(), argumentProblems(schema, args))
    }

    @Test
    fun blankAndNullCountAsMissingAsTheHandlersCountThem() {
        val args = buildJsonObject {
            put("module", "  ")
            put("query", JsonNull)
        }
        val problems = argumentProblems(schema, args)
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.all { it.startsWith("missing required argument") }, problems.toString())
    }

    @Test
    fun aValueOutsideTheEnumIsNamedWithTheValuesThatAreAllowed() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("verbosity", "full")
        }
        assertEquals(
            listOf("'verbosity' is 'full', which is not one of: concise, detailed"),
            argumentProblems(schema, args),
        )
    }

    @Test
    fun aMissingEnumArgumentStatesItsValues() {
        val withEnum = moduleSchema(
            extraProps = mapOf("verbosity" to verbosityProp("is shorter")),
            extraRequired = listOf("verbosity"),
        )
        val problems = argumentProblems(withEnum, buildJsonObject { put("module", "ORDERS") })
        assertTrue(problems.single().contains("concise"), problems.toString())
    }

    @Test
    fun aCaseSlipIsMatchedToTheDeclaredName() {
        val args = buildJsonObject {
            put("Module", "ORDERS")
            put("query", "x")
        }
        val problems = argumentProblems(schema, args)
        assertTrue(problems.contains("unknown argument 'Module' — did you mean 'module'?"), problems.toString())
    }

    @Test
    fun anUnrelatedNameGetsNoGuess() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("zzz", "y")
        }
        assertEquals(listOf("unknown argument 'zzz' — this tool has no such argument"), argumentProblems(schema, args))
    }
}
