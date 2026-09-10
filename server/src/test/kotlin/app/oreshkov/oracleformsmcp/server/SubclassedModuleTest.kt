package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.dto.BodySource
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Subclassed (inherited) objects, over the real pipeline.
 *
 * `PICKER` subclasses `TOOLBAR`'s `BAR` block as `BAR_LIST`, so its `SELECT` button's
 * `WHEN-BUTTON-PRESSED` is stored with an empty body — the code that runs is `TOOLBAR`'s. The
 * property under test throughout is that no read ever serves that emptiness as a fact: every one
 * of them says the body is inherited and names the call that reaches it.
 */
class SubclassedModuleTest {

    private val temp: Path = Files.createTempDirectory("subclass-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val pickerKey = ModuleKey.of("picker", ModuleType.FORM)
    private val toolbarKey = ModuleKey.of("toolbar", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        binaryConversion = false,
    )

    init {
        copyFixture("picker_fmb.xml")
        copyFixture("toolbar_fmb.xml")
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
    fun anInheritedTriggerIsNeverServedAsAnEmptyBody() = runTest {
        service.fetchModule(pickerKey)

        val trigger = service.getTrigger(pickerKey, "WHEN-BUTTON-PRESSED", block = "BAR_LIST", item = "SELECT")
        assertEquals("", trigger.text)
        assertEquals(BodySource.INHERITED, trigger.bodySource)

        val ref = assertNotNull(trigger.inherited, "an empty body must never ship without its pointer")
        assertEquals("TOOLBAR", ref.module)
        assertEquals("toolbar.fmb", ref.file)
        assertEquals("BAR.SELECT", ref.ownerPath)

        // The hint names the exact next call, the way the staleness exceptions do.
        val hint = assertNotNull(trigger.hint)
        assertEquals(
            "Trigger 'WHEN-BUTTON-PRESSED' on 'BAR_LIST.SELECT' is subclassed, so its definition " +
                "lives in 'TOOLBAR.fmb', which is not cached. Call " +
                "fetch_module(module=\"TOOLBAR.fmb\"), then get_trigger(module=\"TOOLBAR.fmb\", " +
                "name=\"WHEN-BUTTON-PRESSED\", ownerPath=\"BAR.SELECT\"). " +
                "Or retry this call with resolve=true.",
            hint,
        )
        assertNull(trigger.resolvedFrom)
    }

    @Test
    fun resolveFollowsThePointerOnceTheParentIsCached() = runTest {
        service.fetchModule(pickerKey)

        // Cold parent: resolve degrades to the pointer rather than converting anything.
        val unresolved =
            service.getTrigger(pickerKey, "WHEN-BUTTON-PRESSED", block = "BAR_LIST", item = "SELECT", resolve = true)
        assertEquals(BodySource.INHERITED, unresolved.bodySource)
        assertEquals("", unresolved.text)
        assertTrue(assertNotNull(unresolved.hint).contains("not cached"))

        service.fetchModule(toolbarKey)

        val resolved =
            service.getTrigger(pickerKey, "WHEN-BUTTON-PRESSED", block = "BAR_LIST", item = "SELECT", resolve = true)
        assertEquals(BodySource.RESOLVED, resolved.bodySource)
        assertEquals(toolbarKey, resolved.resolvedFrom)
        assertTrue(resolved.text.contains("Do_Key('HELP')"), resolved.text)
        assertNull(resolved.hint, "a resolved body needs no next call")
        // The pointer stays: the body is the parent's, and the result says whose.
        assertEquals("BAR.SELECT", assertNotNull(resolved.inherited).ownerPath)
    }

    @Test
    fun aLocalOverrideReadsAsItsOwnBody() = runTest {
        service.fetchModule(pickerKey)
        service.fetchModule(toolbarKey)

        val overridden =
            service.getTrigger(pickerKey, "WHEN-BUTTON-PRESSED", block = "BAR_LIST", item = "CANCEL", resolve = true)
        assertEquals(BodySource.OWN, overridden.bodySource)
        assertTrue(overridden.text.contains("NO_VALIDATE"))
        assertNull(overridden.hint)
        assertNull(overridden.resolvedFrom)
    }

    @Test
    fun aGenuinelyEmptyBodyIsDistinguishedFromAnInheritedOne() = runTest {
        service.fetchModule(pickerKey)
        val listed = service.listTriggers(pickerKey, block = "BAR_LIST", item = null, level = null).triggers
        assertEquals(
            mapOf<String?, BodySource>("SELECT" to BodySource.INHERITED, "CANCEL" to BodySource.OWN),
            listed.associate { it.item to it.bodySource },
        )
        assertEquals(
            BodySource.OWN,
            service.listTriggers(pickerKey, block = "CUSTOMERS", item = null, level = null)
                .triggers.single().bodySource,
        )
        // A stub trigger really is empty — and that, unlike the inherited one, is a fact.
        val stub = service.getTrigger(pickerKey, "WHEN-NEW-BLOCK-INSTANCE", block = "ORPHAN", item = null)
        assertEquals(BodySource.EMPTY, stub.bodySource)
        assertEquals("", stub.text)
        assertNull(stub.inherited)
        assertNull(stub.hint)
    }

    @Test
    fun getBlockFlagsTheSubclassedBlockAndItsItems() = runTest {
        service.fetchModule(pickerKey)
        val detail = service.getBlock(pickerKey, "BAR_LIST")

        assertEquals("BAR", assertNotNull(detail.block.inherited).name)
        assertTrue(assertNotNull(detail.hint).contains("get_block(module=\"TOOLBAR.fmb\", block=\"BAR\")"))

        val items = detail.block.items.associateBy { it.name }
        assertEquals("BAR", assertNotNull(items.getValue("SELECT").inherited).ownerPath)
        // An item this module added itself carries no pointer, inside an inherited block or not.
        assertNull(items.getValue("COUNT").inherited)

        // ...and a block that is not subclassed says nothing about inheritance at all.
        val plain = service.getBlock(pickerKey, "CUSTOMERS")
        assertNull(plain.block.inherited)
        assertNull(plain.hint)
    }

    @Test
    fun getObjectXmlResolvesInheritanceAtTheLevelItWasAsked() = runTest {
        service.fetchModule(pickerKey)
        val item = service.getObjectXml(pickerKey, "Item", "SELECT", owner = "BAR_LIST")

        // The fragment itself carries only the marker — this is the gap the DTO field closes.
        assertTrue(item.xml.contains("SubclassSubObject=\"true\""))
        assertTrue(!item.xml.contains("ParentModule"))
        assertEquals("TOOLBAR", assertNotNull(item.inherited).module)
        assertEquals("BAR", item.inherited?.ownerPath)
    }

    @Test
    fun anInheritedProgramUnitBehavesLikeAnInheritedTrigger() = runTest {
        service.fetchModule(pickerKey)
        val cold = service.getProgramUnit(pickerKey, "BAR_REFRESH", unitType = "PROCEDURE")
        assertEquals(BodySource.INHERITED, cold.bodySource)
        assertTrue(assertNotNull(cold.hint).contains("fetch_module(module=\"TOOLBAR.fmb\")"))

        service.fetchModule(toolbarKey)
        val resolved = service.getProgramUnit(pickerKey, "BAR_REFRESH", unitType = "PROCEDURE", resolve = true)
        assertEquals(BodySource.RESOLVED, resolved.bodySource)
        assertEquals(toolbarKey, resolved.resolvedFrom)
        assertTrue(resolved.text.contains("Synchronize"))
    }

    /**
     * The other way a definition arrives from elsewhere: copied in with an **object group**,
     * typically from an `.olb`. The object keeps its own name there, and where the group puts it
     * inside the library is not recorded here — so the hint names the group and, unlike the
     * subclassing case, suggests no `ownerPath` it cannot vouch for.
     */
    @Test
    fun anObjectGroupMemberNamesItsGroupAndClaimsNoPathInTheLibrary() = runTest {
        service.fetchModule(pickerKey)

        val trigger = service.getTrigger(pickerKey, "ON-ERROR", block = null, item = null)
        assertEquals(BodySource.INHERITED, trigger.bodySource)
        assertEquals("STD", assertNotNull(trigger.inherited).objectGroup)
        assertEquals("ON-ERROR", trigger.inherited?.name)
        val hint = assertNotNull(trigger.hint)
        assertTrue(hint.contains("(object group 'STD')"), hint)
        assertTrue(hint.contains("get_trigger(module=\"SHARED.olb\", name=\"ON-ERROR\")"), hint)
        assertTrue(!hint.contains("ownerPath"), "the library's own layout is not ours to assert: $hint")

        val unit = service.getProgramUnit(pickerKey, "SHOW_INFO", unitType = "PROCEDURE")
        assertEquals(BodySource.INHERITED, unit.bodySource)
        assertEquals("SHOW_INFO", assertNotNull(unit.inherited).name)
    }

    /**
     * `ParentName` also carries the *property class*, with `ParentModule` naming this same module.
     * That hides nothing — the class is indexed alongside — so it is not an inheritance pointer.
     * A real form carries dozens; treating them as inheritance would fill `get_block` with
     * uncallable references to objects of the wrong kind.
     */
    @Test
    fun aPropertyClassInThisModuleIsNotReportedAsInheritance() = runTest {
        service.fetchModule(pickerKey)
        val items = service.getBlock(pickerKey, "CUSTOMERS").block.items.associateBy { it.name }
        assertNull(items.getValue("NAME").inherited)
        assertNull(items.getValue("REF").inherited)
    }

    @Test
    fun aSubclassedObjectWithNoRecordedParentStillSaysSo() = runTest {
        service.fetchModule(pickerKey)
        val orphan = service.getBlock(pickerKey, "ORPHAN").block.items.single()
        val ref = assertNotNull(orphan.inherited, "subclassed with no pointer is still subclassed")
        assertTrue(ref.subObject)
        assertNull(ref.module)
    }
}
