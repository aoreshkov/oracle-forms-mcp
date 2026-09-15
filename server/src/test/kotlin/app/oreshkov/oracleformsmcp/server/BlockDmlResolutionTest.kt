package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ItemDml
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * "Which fields does an insert write?" answered from `get_block` alone.
 *
 * The fixture has the shape that made the question expensive: most items take their DML properties
 * from a property class, and the classes this form declares are stubs pointing at a shared module.
 * The item's own attributes say almost nothing; the answer is the item over its class over the
 * class's parent — and it must never be guessed when a link of that chain is not readable.
 */
class BlockDmlResolutionTest {

    private val temp: Path = Files.createTempDirectory("block-dml-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val claimsKey = ModuleKey.of("claims", ModuleType.FORM)
    private val stylesKey = ModuleKey.of("styles", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
    )

    init {
        copyFixture("claims_fmb.xml")
        copyFixture("styles_fmb.xml")
        copyFixture("picker_fmb.xml")
    }

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    @Test
    fun anItemOverItsClassOverTheSharedClassGivesTheEffectiveProperties() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)

        val detail = service.getBlock(claimsKey, "CLAIM", detailed = true)
        val effective = detail.effectiveDml

        val base = ItemDml(databaseItem = true, insertAllowed = true, updateAllowed = true, maximumLength = 30)
        assertEquals(base.copy(primaryKey = true), effective["CLAIM_ID"])
        assertEquals(base.copy(initialValue = "OPEN"), effective["STATUS"])
        // What the item writes wins over its class: validated on entry, never written.
        assertEquals(base.copy(insertAllowed = false, updateAllowed = false), effective["REFERENCE"])
        // A stub, to a class based on another class in the shared module.
        assertEquals(base.copy(updateAllowed = false), effective["AMOUNT_LOCKED"])
        assertEquals(ItemDml(databaseItem = false, keyboardNavigable = false), effective["TOTAL_DISPLAY"])
        // A class defined in this module needs no other module.
        assertEquals(ItemDml(databaseItem = true, required = true, maximumLength = 40), effective["OWNER_NAME"])
        // No class: the item's own values, and every other field really is the Forms default.
        assertEquals(ItemDml(enabled = false), effective["NOTES"])

        // The raw row is untouched by resolution.
        assertEquals(
            ItemDml(insertAllowed = false, updateAllowed = false),
            detail.block.items.single { it.name == "REFERENCE" }.dml,
        )

        val locked = detail.propertyClasses.single { it.name == "LOCKED_TEXT" }
        assertTrue(locked.resolved)
        assertEquals(listOf(claimsKey, stylesKey), locked.resolvedThrough)
        assertEquals(1, locked.itemCount)
        assertEquals(3, detail.propertyClasses.single { it.name == "BASE_TEXT" }.itemCount)
    }

    @Test
    fun aClassInAModuleThatIsNotFetchedIsNeverGuessedAndTheHintNamesTheFetch() = runTest {
        service.fetchModule(claimsKey)

        val detail = service.getBlock(claimsKey, "CLAIM", detailed = true)

        assertEquals(setOf("OWNER_NAME", "NOTES"), detail.effectiveDml.keys)
        val base = detail.propertyClasses.single { it.name == "BASE_TEXT" }
        assertFalse(base.resolved)
        assertEquals(stylesKey, base.missingModule)

        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("fetch_module(module=\"STYLES.fmb\")"), hint)
        assertTrue(hint.contains("get_block(module=\"CLAIMS.fmb\", block=\"CLAIM\", verbosity=\"detailed\")"), hint)
        assertTrue(hint.contains("5 item(s)"), "BASE_TEXT, CONTROL_TEXT and LOCKED_TEXT items: $hint")
        // A pointer at a module that is not there at all is reported the same way, and separately.
        assertEquals(
            ModuleKey.of("absent", ModuleType.FORM),
            detail.propertyClasses.single { it.name == "MISSING_TEXT" }.missingModule,
        )
    }

    @Test
    fun aStaleParentIsNotReadForResolution() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)
        Files.writeString(formsDir.resolve("styles_fmb.xml"), "\n", StandardOpenOption.APPEND)

        val detail = service.getBlock(claimsKey, "CLAIM", detailed = true)

        assertNull(detail.effectiveDml["CLAIM_ID"], "a stale index must not supply values")
        assertEquals(stylesKey, detail.propertyClasses.single { it.name == "BASE_TEXT" }.missingModule)
    }

    @Test
    fun theConciseRowsCarryNoResolution() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)

        val concise = service.getBlock(claimsKey, "CLAIM")

        assertTrue(concise.effectiveDml.isEmpty())
        assertTrue(concise.propertyClasses.isEmpty())
        assertTrue(concise.block.items.all { it.dml == null })
        assertNull(concise.columns)
        assertNull(concise.hint)
    }

    @Test
    fun subclassedItemsAreCountedNotResolved() = runTest {
        service.fetchModule(ModuleKey.of("picker", ModuleType.FORM))

        val detail = service.getBlock(ModuleKey.of("picker", ModuleType.FORM), "BAR_LIST", detailed = true)

        assertEquals(setOf("COUNT"), detail.effectiveDml.keys)
        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("2 item(s) are subclassed"), hint)
    }

    @Test
    fun columnsAreReadFromTheBlockAndSetAgainstItsItems() = runTest {
        service.fetchModule(claimsKey)

        val columns = assertNotNull(service.getBlock(claimsKey, "CLAIM", columns = true).columns)

        assertEquals(7, columns.total)
        assertFalse(columns.truncated)
        val claimId = columns.columns.first()
        assertEquals("CLAIM_ID", claimId.name)
        assertEquals("NUMBER", claimId.dataType)
        assertEquals(10, claimId.precision)
        assertTrue(claimId.mandatory)
        assertEquals("Query", claimId.type)
        // OWNER is supplied by an item whose ColumnName carries a table alias.
        assertEquals(listOf("AMOUNT", "CREATED_BY", "CREATED_ON"), columns.columnsWithoutItem)
        assertEquals(listOf("CREATED_BY"), columns.mandatoryColumnsWithoutItem)

        val none = assertNotNull(service.getBlock(claimsKey, "SUMMARY", columns = true).columns)
        assertEquals(0, none.total)
    }
}
