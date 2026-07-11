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
}
