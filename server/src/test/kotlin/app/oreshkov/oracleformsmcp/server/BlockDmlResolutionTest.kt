package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.dto.UnresolvedItem
import app.oreshkov.oracleformsmcp.dto.UnresolvedReason
import app.oreshkov.oracleformsmcp.model.ItemDml
import app.oreshkov.oracleformsmcp.model.ItemGeometry
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * A size is a class-supplied property like any other, and the one most often written by halves:
     * a real classed item carries a `Width` and no `Height` at all, so serving only what the item
     * wrote reports every such item as having no height — an absence indistinguishable from a
     * height nobody set. `effectiveGeometry` resolves it the way `effectiveDml` resolves the rest.
     */
    @Test
    fun anItemsSizeResolvesThroughItsClassLikeEveryOtherProperty() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)

        val detail = service.getBlock(claimsKey, "CLAIM", detailed = true)
        val size = detail.effectiveGeometry

        // The item writes its own width; the height exists only on the shared class.
        assertEquals(ItemGeometry(width = 60, height = 24), size["CLAIM_ID"])
        // Writes neither: both dimensions come from the class.
        assertEquals(ItemGeometry(width = 120, height = 24), size["STATUS"])
        // Through a stub to a class based on another class, both hops in the shared module.
        assertEquals(ItemGeometry(width = 120, height = 24), size["AMOUNT_LOCKED"])
        // A class in this module, supplying a height and no width: what is unset stays null.
        assertEquals(ItemGeometry(height = 18), size["OWNER_NAME"])

        // The item's own row is untouched by resolution — it is what the item wrote, nothing more.
        val claimId = detail.block.items.single { it.name == "CLAIM_ID" }
        assertEquals(60, claimId.width)
        assertNull(claimId.height)
        assertNull(detail.block.items.single { it.name == "STATUS" }.width)

        // An item with no size anywhere in its chain is left out rather than served as an empty row.
        assertFalse("NOTES" in size, "an item with no size written anywhere says nothing here")
        // An unresolved class yields no size either: it is an unknown, not a default.
        assertFalse("COMMENTS" in size)
        assertTrue(detail.unresolvedItems.any { it.name == "COMMENTS" })
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
        // A pointer at a module that is not there at all is reported separately.
        assertEquals(
            ModuleKey.of("absent", ModuleType.FORM),
            detail.propertyClasses.single { it.name == "MISSING_TEXT" }.missingModule,
        )
    }

    /**
     * A class whose module is in the directory and a class whose module is not are both unresolved,
     * but only one of them has anything a caller can do about it.
     *
     * Naming `fetch_module` for a module the directory does not hold sends a caller round a call
     * that fails, while reporting the reason as "not fetched" — which is how the same result tells
     * them to retry and tells them nothing. The library hints already drew this distinction; the
     * class hints did not, and the two sat in the same response.
     */
    @Test
    fun aClassModuleTheDirectoryDoesNotHoldIsDistinguishedFromOneMerelyUnfetched() = runTest {
        service.fetchModule(claimsKey)

        val detail = service.getBlock(claimsKey, "CLAIM", detailed = true)
        val absentKey = ModuleKey.of("absent", ModuleType.FORM)

        // COMMENTS needs absent.fmb, which is not in the forms directory: no call can fix it.
        assertEquals(
            UnresolvedItem("COMMENTS", "MISSING_TEXT", UnresolvedReason.CLASS_MODULE_NOT_IN_DIRECTORY, absentKey),
            detail.unresolvedItems.single { it.name == "COMMENTS" },
        )
        // STATUS needs styles.fmb, which is there and simply not fetched yet.
        assertEquals(
            UnresolvedItem("STATUS", "BASE_TEXT", UnresolvedReason.CLASS_MODULE_NOT_FETCHED, stylesKey),
            detail.unresolvedItems.single { it.name == "STATUS" },
        )

        val hint = assertNotNull(detail.hint)
        assertTrue(hint.contains("'ABSENT.fmb', which is not in the forms directory"), hint)
        assertFalse(
            hint.contains("fetch_module(module=\"ABSENT.fmb\")"),
            "a call that cannot succeed is not a hint: $hint",
        )
        // The fetchable one still gets its call.
        assertTrue(hint.contains("fetch_module(module=\"STYLES.fmb\")"), hint)
    }

    /**
     * The failure this guards against reached a written review: a hint named the module to fetch,
     * nobody fetched it, and an item absent from `effectiveDml` was read as an item with no length
     * limit. The unknown is therefore stated per item, in the data, beside the map — never as a
     * `null` inside it, where it would read as the Forms default.
     */
    @Test
    fun anUnresolvedClassIsNeverServedAsTheDefault() = runTest {
        service.fetchModule(claimsKey)
        val lengthQuestion = listOf("CLAIM_ID", "STATUS", "AMOUNT_LOCKED", "OWNER_NAME")

        val before = service.getBlock(claimsKey, "CLAIM", detailed = true, items = lengthQuestion)

        assertEquals(setOf("OWNER_NAME"), before.effectiveDml.keys)
        assertEquals(
            listOf(
                UnresolvedItem("CLAIM_ID", "BASE_TEXT", UnresolvedReason.CLASS_MODULE_NOT_FETCHED, stylesKey),
                UnresolvedItem("STATUS", "BASE_TEXT", UnresolvedReason.CLASS_MODULE_NOT_FETCHED, stylesKey),
                UnresolvedItem("AMOUNT_LOCKED", "LOCKED_TEXT", UnresolvedReason.CLASS_MODULE_NOT_FETCHED, stylesKey),
            ),
            before.unresolvedItems,
        )
        assertFalse(before.truncated, "nothing was cut: the absence is an unknown, not a size limit")
        val hint = assertNotNull(before.hint)
        assertTrue(hint.contains("'unresolvedItems'"), hint)
        // The follow-up call asks the same narrow question again.
        assertTrue(
            hint.contains(
                "get_block(module=\"CLAIMS.fmb\", block=\"CLAIM\", verbosity=\"detailed\", " +
                    "items=[\"CLAIM_ID\", \"STATUS\", \"AMOUNT_LOCKED\", \"OWNER_NAME\"])",
            ),
            hint,
        )

        service.fetchModule(stylesKey)
        val after = service.getBlock(claimsKey, "CLAIM", detailed = true, items = lengthQuestion)

        assertEquals(lengthQuestion.toSet(), after.effectiveDml.keys)
        assertEquals(30, after.effectiveDml.getValue("CLAIM_ID").maximumLength)
        assertTrue(after.unresolvedItems.isEmpty(), "${after.unresolvedItems}")
        assertNull(after.hint)
    }

    @Test
    fun everyItemMissingFromTheMapIsListedWithItsReason() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)

        val claim = service.getBlock(claimsKey, "CLAIM", detailed = true)

        // A pointer at a module the directory does not have: named, with the module still given,
        // but as a limit rather than as a fetch to retry.
        assertEquals(
            listOf(
                UnresolvedItem(
                    "COMMENTS",
                    "MISSING_TEXT",
                    UnresolvedReason.CLASS_MODULE_NOT_IN_DIRECTORY,
                    ModuleKey.of("absent", ModuleType.FORM),
                ),
            ),
            claim.unresolvedItems,
        )
        assertEquals(claim.block.items.map { it.name }.toSet(), claim.effectiveDml.keys + claim.unresolvedItems.map { it.name })

        val pickerKey = ModuleKey.of("picker", ModuleType.FORM)
        service.fetchModule(pickerKey)
        val bar = service.getBlock(pickerKey, "BAR_LIST", detailed = true)
        assertEquals(listOf("SELECT", "CANCEL"), bar.unresolvedItems.map { it.name })
        assertTrue(bar.unresolvedItems.all { it.reason == UnresolvedReason.SUBCLASSED && it.missingModule == null })
    }

    /** A chain that names no module this server can find cannot be fixed by a fetch, and says so. */
    @Test
    fun aClassThatCannotBeFollowedHasNoModuleToFetch() = runTest {
        formsDir.resolve("loose_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="LOOSE">
            |    <Block Name="MAIN">
            |      <Item Name="CODE" ItemType="Text Item" ParentModule="LOOSE" ParentModuleType="12" ParentName="NOWHERE_TEXT" ParentType="29"/>
            |    </Block>
            |    <PropertyClass Name="NOWHERE_TEXT" ParentModule="NOWHERE" ParentModuleType="12" ParentName="NOWHERE_TEXT" ParentType="29"/>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
        val looseKey = ModuleKey.of("loose", ModuleType.FORM)
        service.fetchModule(looseKey)

        val detail = service.getBlock(looseKey, "MAIN", detailed = true)

        assertTrue(detail.effectiveDml.isEmpty())
        assertEquals(
            listOf(UnresolvedItem("CODE", "NOWHERE_TEXT", UnresolvedReason.CLASS_NOT_FOLLOWABLE)),
            detail.unresolvedItems,
        )
        assertFalse(assertNotNull(detail.hint).contains("fetch_module"), detail.hint)
    }

    @Test
    fun itemsNarrowEveryListButNotTheBlockOrItsColumns() = runTest {
        service.fetchModule(claimsKey)
        service.fetchModule(stylesKey)

        val detail = service.getBlock(
            claimsKey,
            "CLAIM",
            detailed = true,
            columns = true,
            items = listOf("owner_name", "CLAIM.status", " "),
        )

        // Block order, not argument order; a block prefix and case are both accepted.
        assertEquals(listOf("STATUS", "OWNER_NAME"), detail.block.items.map { it.name })
        assertEquals(8, detail.itemTotal)
        assertEquals(2, detail.itemsMatched)
        assertEquals(setOf("STATUS", "OWNER_NAME"), detail.effectiveDml.keys)
        assertEquals(setOf("BASE_TEXT", "LOCAL_TEXT"), detail.propertyClasses.map { it.name }.toSet())
        assertEquals(1, detail.propertyClasses.single { it.name == "BASE_TEXT" }.itemCount)
        // A column supplied by an item the call did not ask about is still supplied.
        assertEquals(listOf("AMOUNT", "CREATED_BY", "CREATED_ON"), assertNotNull(detail.columns).columnsWithoutItem)

        val everything = service.getBlock(claimsKey, "CLAIM", items = emptyList())
        assertNull(everything.itemsMatched)
        assertEquals(8, everything.block.items.size)
    }

    @Test
    fun anUnknownItemFailsWithTheBlocksItemNames() = runTest {
        service.fetchModule(claimsKey)

        val failure = assertFailsWith<IllegalArgumentException> {
            service.getBlock(claimsKey, "CLAIM", items = listOf("STATUS", "STATUS_CODE"))
        }

        val message = assertNotNull(failure.message)
        assertTrue(message.contains("'STATUS_CODE'"), message)
        assertFalse(message.contains("'STATUS'"), message)
        assertTrue(message.contains("CLAIM_ID, STATUS, REFERENCE"), message)
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
