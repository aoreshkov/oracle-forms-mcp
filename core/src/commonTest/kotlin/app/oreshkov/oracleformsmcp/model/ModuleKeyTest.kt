package app.oreshkov.oracleformsmcp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModuleKeyTest {

    @Test
    fun parsesAndNormalizesCase() {
        val key = ModuleKey.parse("orders.fmb")
        assertEquals("ORDERS", key.name)
        assertEquals(ModuleType.FORM, key.type)
        assertEquals("ORDERS.fmb", key.toString())
    }

    @Test
    fun parsesEveryExtension() {
        assertEquals(ModuleType.MENU, ModuleKey.parse("main.MMB").type)
        assertEquals(ModuleType.LIBRARY, ModuleKey.parse("utils.pll").type)
        assertEquals(ModuleType.OBJECT_LIBRARY, ModuleKey.parse("objects.olb").type)
    }

    @Test
    fun rejectsUnknownOrMissingExtension() {
        assertFailsWith<IllegalArgumentException> { ModuleKey.parse("orders.rdf") }
        assertFailsWith<IllegalArgumentException> { ModuleKey.parse("orders") }
        assertNull(ModuleKey.parseOrNull("orders"))
    }

    @Test
    fun sameNameDifferentTypeAreDistinctKeys() {
        val form = ModuleKey.of("orders", ModuleType.FORM)
        val library = ModuleKey.of("orders", ModuleType.LIBRARY)
        assertEquals(form.name, library.name)
        check(form != library)
    }

    @Test
    fun ofOrNullRejectsWhatOfRejects() {
        assertEquals(ModuleKey.of("orders", ModuleType.FORM), ModuleKey.ofOrNull("orders", ModuleType.FORM))
        assertNull(ModuleKey.ofOrNull("orders..bak", ModuleType.FORM))
        assertNull(ModuleKey.ofOrNull("c:x", ModuleType.FORM))
        assertNull(ModuleKey.ofOrNull(" ", ModuleType.FORM))
    }

    /**
     * The canonical name becomes a cache directory name, and a tool argument reaches the file
     * system through [ModuleKey.parseOrNull] — so the constructor's guard is all that stands
     * between `../evil.fmb` and a path outside the cache.
     */
    @Test
    fun aNameThatCouldLeaveItsDirectoryIsRejected() {
        for (spec in listOf("../evil.fmb", "a/b.fmb", "a\\b.fmb", "c:x.fmb", "a..b.fmb", "...fmb")) {
            assertFailsWith<IllegalArgumentException>(spec) { ModuleKey.parse(spec) }
            assertNull(ModuleKey.parseOrNull(spec), spec)
        }
        assertFailsWith<IllegalArgumentException> { ModuleKey("..", ModuleType.FORM) }
        assertFailsWith<IllegalArgumentException> { ModuleKey("A/B", ModuleType.FORM) }
    }
}
