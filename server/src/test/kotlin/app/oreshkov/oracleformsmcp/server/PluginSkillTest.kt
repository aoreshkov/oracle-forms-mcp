package app.oreshkov.oracleformsmcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin's skill names tools by hand and ships with nothing to build, so a renamed tool would
 * leave it sending the model to a call that doesn't exist. Every snake_case name that leads a code
 * span or a line of a code block has to be a registered tool, or be listed in [notTools].
 */
class PluginSkillTest {

    /** Forms built-ins the skill quotes, and the tool it says doesn't exist. */
    private val notTools = setOf("call_form", "set_item_property", "set_block_property", "find_writes")

    private fun skill(): String = assertNotNull(
        javaClass.getResource("/plugin-skill/SKILL.md"),
        "the build copies the plugin's SKILL.md into the test resources",
    ).readText()

    @Test
    fun everyToolTheSkillNamesIsRegistered() {
        val registered = serverWithAllTools().tools.keys
        val unknown = namesIn(skill()) - registered - notTools
        assertEquals(emptySet(), unknown, "the skill names tools the server doesn't register")
    }

    @Test
    fun theListOfNonToolsStaysAccurate() {
        val named = namesIn(skill())
        assertEquals(emptySet(), notTools - named, "listed as quoted by the skill, but no longer are")
        val registered = serverWithAllTools().tools.keys
        assertEquals(emptySet(), notTools intersect registered, "listed as not tools, but registered")
    }

    @Test
    fun namesAreFoundInEveryFormTheSkillWritesThem() {
        val text = """
            Call `plain_name`, then `with_args(verbosity: "detailed")` and `with_words ORDERS.fmb`,
            or `mcp__plugin_oracle-forms_oracle-forms__namespaced_name`. Not `camelCase`, `Do_Key`.

            ```text
            in_a_fence "ORDERS"
            in_a_call(module="ORDERS.fmb",
                      kind="summary")
            ```
        """.trimIndent()
        assertEquals(
            setOf("plain_name", "with_args", "with_words", "namespaced_name", "in_a_fence", "in_a_call"),
            namesIn(text),
        )
    }

    @Test
    fun theSkillStillNamesTools() {
        assertTrue("get_block" in namesIn(skill()), "found no tool names, so the check above is empty")
    }

    private fun namesIn(markdown: String): Set<String> {
        val fences = FENCE.findAll(markdown).map { it.groupValues[1] }.toList()
        val prose = FENCE.replace(markdown, "")
        val leadingText = CODE_SPAN.findAll(prose).map { it.groupValues[1] } +
            fences.asSequence().flatMap { it.lineSequence() }
        return leadingText.mapNotNull { LEADING_NAME.find(it.trim())?.groupValues?.get(1) }.toSet()
    }

    private companion object {
        val FENCE = Regex("""(?ms)^```[^\n]*\n(.*?)^```""")
        val CODE_SPAN = Regex("""`([^`]+)`""")
        val LEADING_NAME =
            Regex("""^(?:mcp__plugin_oracle-forms_oracle-forms__)?([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\b""")
    }
}
