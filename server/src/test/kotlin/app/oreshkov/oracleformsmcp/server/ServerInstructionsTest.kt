package app.oreshkov.oracleformsmcp.server

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The server `instructions` are the only place guidance about choosing *between* the tools can
 * live — and a client sees them once, at initialization, so a quiet edit that dropped a hazard
 * would not fail any other test.
 *
 * What is pinned here is not the wording but the facts that have each cost a real trace: that
 * discovery is `list_modules` and not a directory walk, that reading the cached files directly
 * risks the very drift `STALE` reports, that the shell habits have named replacements, and that an
 * empty PL/SQL body means nothing without `bodySource`.
 */
class ServerInstructionsTest {

    private val instructions = SERVER_INSTRUCTIONS

    private fun assertMentions(vararg fragments: String) {
        fragments.forEach { fragment ->
            assertTrue(instructions.contains(fragment), "the server instructions no longer mention '$fragment'")
        }
    }

    @Test
    fun theyNameTheTraversalOrder() {
        assertMentions("list_modules", "fetch_module", "get_object_xml as the escape hatch")
    }

    @Test
    fun theyNameTheStalenessHazardOfReadingTheFilesDirectly() {
        assertMentions("STALE", "Read through these tools rather than through the files", "cache-relative")
    }

    /** The habit→tool mapping: every shell reflex a reader brings has a call named beside it. */
    @Test
    fun theyMapEveryShellHabitToTheToolThatReplacesIt() {
        assertMentions(
            "get_block, not grep",
            "get_object_xml, not sed",
            "read_source",
            "search_modules, not a directory walk",
        )
    }

    @Test
    fun theySayThatAnEmptyBodyIsNotAFact() {
        assertMentions("An empty PL/SQL body is never a fact on its own", "bodySource", "inherited")
    }

    @Test
    fun theyDistinguishTheTwoSearchTools() {
        assertMentions("search_source searches one module", "search_modules searches every")
    }

    @Test
    fun theyIntroduceTheAnnotationLayer() {
        assertMentions("annotate_element", "relate_elements", "persists across sessions")
    }

    /**
     * Instructions are paid for in every session's context, so they stay short enough to be read
     * whole. This is a ceiling, not a target — the guidance above earns its length.
     */
    @Test
    fun theyStayShortEnoughToBeWorthSendingEverySession() {
        assertTrue(instructions.length < 2_500, "instructions grew to ${instructions.length} chars")
    }
}
