package app.oreshkov.oracleformsmcp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleTypeTest {

    @Test
    fun matchesConvertedFileNames() {
        assertEquals("ORDERS" to ModuleType.FORM, ModuleType.matchConverted("orders_fmb.xml"))
        assertEquals("MAIN" to ModuleType.MENU, ModuleType.matchConverted("MAIN_MMB.XML"))
        assertEquals("UTILS" to ModuleType.LIBRARY, ModuleType.matchConverted("utils.pld"))
        assertEquals("OBJ" to ModuleType.OBJECT_LIBRARY, ModuleType.matchConverted("obj_olb.xml"))
    }

    @Test
    fun rejectsNonConvertedNames() {
        assertNull(ModuleType.matchConverted("orders.fmb"))
        assertNull(ModuleType.matchConverted("readme.xml"))
        assertNull(ModuleType.matchConverted("_fmb.xml"))
    }
}
