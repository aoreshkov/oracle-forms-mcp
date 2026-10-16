package app.oreshkov.oracleformsmcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

    private val typed: ToolSchema = moduleSchema(
        extraProps = mapOf(
            "query" to stringProp("Text to find"),
            "offset" to intProp("Rows to skip"),
            "resolve" to boolProp("Follow the pointer"),
        ),
        extraRequired = listOf("query"),
    )

    @Test
    fun aValueThatIsNotTheDeclaredTypeIsReportedNotDefaulted() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("offset", 50.5)
            put("resolve", "yes")
        }
        assertEquals(
            listOf(
                "'offset' is '50.5', which is not an integer",
                "'resolve' is 'yes', which is not a boolean (true or false)",
            ),
            argumentProblems(typed, args),
        )
    }

    @Test
    fun anIntegerTooLargeToUseSaysSoRatherThanThatItIsNoInteger() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("offset", 3_000_000_000L)
        }
        assertEquals(
            listOf("'offset' is '3000000000', which is out of range (at most ${Int.MAX_VALUE})"),
            argumentProblems(typed, args),
        )
    }

    @Test
    fun aValueThatParsesAsTheDeclaredTypeIsAcceptedAndReadTheSameWay() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", "x")
            put("offset", "50")
            put("resolve", "True")
        }
        assertEquals(emptyList(), argumentProblems(typed, args))
        assertEquals(50, args.intArg("offset"))
        assertEquals(true, args.booleanArg("resolve"))
        assertEquals(50, buildJsonObject { put("offset", 50.0) }.intArg("offset"))
    }

    @Test
    fun aStringSentAsAnArrayIsAWrongTypeNotAMissingArgument() {
        val args = buildJsonObject {
            put("module", "ORDERS")
            put("query", buildJsonArray { add("x") })
            put("offset", buildJsonObject { put("n", 1) })
            put("zzz", "y")
        }
        assertEquals(
            listOf(
                "unknown argument 'zzz' — this tool has no such argument",
                "'query' must be a string, not an array",
                "'offset' must be an integer, not an object",
            ),
            argumentProblems(typed, args),
        )
    }
}
