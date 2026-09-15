package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ByModuleTypeConverterTest {

    /** Records the keys it was asked to convert, and answers [convertsBinary] with [binary]. */
    private class RecordingConverter(override val description: String, private val binary: Boolean) :
        ModuleConverter {
        val converted = mutableListOf<ModuleKey>()

        override fun convertsBinary(type: ModuleType): Boolean = binary

        override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String {
            converted += key
            return "$description:$key"
        }
    }

    private val libraries = RecordingConverter("compile", binary = true)
    private val everythingElse = RecordingConverter("copy", binary = false)
    private val router = ByModuleTypeConverter(mapOf(ModuleType.LIBRARY to libraries), everythingElse)

    @Test
    fun convertsEachModuleWithTheConverterForItsType() = runTest {
        val library = ModuleKey.of("utils", ModuleType.LIBRARY)
        val form = ModuleKey.of("orders", ModuleType.FORM)

        assertEquals("compile:UTILS.pll", router.convert(library, "UTILS.pll", "out"))
        assertEquals("copy:ORDERS.fmb", router.convert(form, "ORDERS.fmb", "out"))
        assertEquals(listOf(library), libraries.converted)
        assertEquals(listOf(form), everythingElse.converted)
    }

    /** Which file is consumed — and fingerprinted — follows the converter a module reaches. */
    @Test
    fun answersConvertsBinaryForTheConverterItsTypeReaches() {
        assertTrue(router.convertsBinary(ModuleType.LIBRARY))
        ModuleType.entries.filter { it != ModuleType.LIBRARY }.forEach {
            assertFalse(router.convertsBinary(it), "$it")
        }
    }

    @Test
    fun describesTheFallbackAndEveryOverride() {
        assertEquals("copy; .pll: compile", router.description)
    }

    @Test
    fun rejectsAnEmptyRoutingTable() {
        assertFailsWith<IllegalArgumentException> { ByModuleTypeConverter(emptyMap(), everythingElse) }
    }
}
