package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The whole copy-mode pipeline against the real scanner, converter, parser, and on-disk cache:
 * exactly what a no-ORACLE_HOME deployment runs.
 */
class FormsServiceIntegrationTest {

    private val temp: Path = Files.createTempDirectory("forms-e2e-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val utilsKey = ModuleKey.of("utils", ModuleType.LIBRARY)
    private val dupesKey = ModuleKey.of("dupes", ModuleType.FORM)
    private val menuKey = ModuleKey.of("mainmenu", ModuleType.MENU)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
        oracleConversion = false,
    )

    init {
        copyFixture("orders_fmb.xml")
        copyFixture("utils.pld")
        copyFixture("dupes_fmb.xml")
        copyFixture("mainmenu_mmb.xml")
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
    fun fullReadPathOverAFetchedForm() = runTest {
        val summary = service.fetchModule(ordersKey)
        assertEquals(2, summary.blockCount)
        assertEquals(3, summary.itemCount)
        assertEquals(3, summary.triggerCount)
        assertEquals(3, summary.programUnitCount)
        assertEquals(listOf("UTILS"), summary.attachedLibraries)

        val overview = service.overview(ordersKey)
        assertEquals(listOf("ORDERS", "CONTROL"), overview.blocks)
        assertEquals(listOf("WINDOW_MAIN"), overview.windows)

        val block = service.getBlock(ordersKey, "orders").block
        assertEquals("ORDERS", block.queryDataSourceName)

        val triggers = service.listTriggers(ordersKey, block = "ORDERS", item = null, level = null)
        assertEquals(2, triggers.triggers.size)

        val trigger = service.getTrigger(ordersKey, "WHEN-VALIDATE-ITEM", block = null, item = null)
        assertEquals(TriggerLevel.ITEM, trigger.level)
        assertEquals(
            "IF :ORDERS.ORDER_ID IS NULL THEN\n  RAISE FORM_TRIGGER_FAILURE;\nEND IF;",
            trigger.text,
        )

        val spec = service.getProgramUnit(ordersKey, "PKG_ORDERS", unitType = "PACKAGE_SPEC")
        assertTrue(spec.text.startsWith("PACKAGE pkg_orders IS"))
        val ambiguous = assertFailsWith<IllegalArgumentException> {
            service.getProgramUnit(ordersKey, "PKG_ORDERS", unitType = null)
        }
        assertTrue(ambiguous.message!!.contains("unitType"))

        val plsqlHits = service.searchSource(ordersKey, "calc_total", regex = false, scope = "plsql", maxResults = 50)
        assertTrue(plsqlHits.hits.isNotEmpty())
        val xmlHits = service.searchSource(ordersKey, "QueryDataSourceName", regex = false, scope = "xml", maxResults = 50)
        assertTrue(xmlHits.hits.isNotEmpty())

        val objectXml = service.getObjectXml(ordersKey, "Block", "CONTROL", owner = null)
        assertTrue(objectXml.xml.trimStart().startsWith("<Block"))
        assertTrue(!objectXml.truncated)
    }

    @Test
    fun listTriggersVerbosityControlsThePlsqlPreview() = runTest {
        service.fetchModule(ordersKey)

        val concise = service.listTriggers(ordersKey, block = null, item = null, level = null)
        assertTrue(concise.triggers.isNotEmpty())
        assertTrue(
            concise.triggers.all { it.firstLine.isEmpty() },
            "concise (default) must omit the one-line PL/SQL preview",
        )
        // The high-signal fields survive concise.
        assertTrue(concise.triggers.all { it.name.isNotEmpty() })

        val detailed =
            service.listTriggers(ordersKey, block = null, item = null, level = null, detailed = true)
        assertTrue(
            detailed.triggers.any { it.firstLine.isNotEmpty() },
            "detailed must include the PL/SQL preview",
        )
    }

    @Test
    fun searchSourcePagesWithOffset() = runTest {
        service.fetchModule(ordersKey)

        val page1 = service.searchSource(ordersKey, "Name", regex = false, scope = "xml", maxResults = 1)
        assertEquals(1, page1.hits.size)
        assertTrue(page1.truncated, "a one-hit page over a form with many matches must be truncated")
        assertEquals(0, page1.offset)
        assertEquals(1, page1.nextOffset)

        val page2 = service.searchSource(
            ordersKey, "Name", regex = false, scope = "xml", maxResults = 1, offset = page1.nextOffset!!,
        )
        assertEquals(1, page2.hits.size)
        assertEquals(1, page2.offset)
        // Paging advances: the second page is a different match than the first.
        assertTrue(page1.hits.single() != page2.hits.single())
    }

    @Test
    fun annotationsAttachToElementsSurviveReindexAndFlagDrift() = runTest {
        service.fetchModule(ordersKey)

        val created = service.annotate(
            key = ordersKey,
            elementKind = ElementKind.TRIGGER,
            name = "WHEN-VALIDATE-ITEM",
            ownerPath = null,
            kind = AnnotationKind.NOTE,
            body = "Validates the order id is present",
            author = Author.AI,
        )
        assertEquals(false, created.annotation.staleAgainstSource)

        // Served back through the dedicated tool...
        val direct = service.getElementAnnotations(ordersKey, ElementKind.TRIGGER, "WHEN-VALIDATE-ITEM", null)
        assertEquals(1, direct.annotations.notes.size)
        assertEquals("Validates the order id is present", direct.annotations.notes.single().body)

        // ...and inline in get_trigger.
        val trigger = service.getTrigger(ordersKey, "WHEN-VALIDATE-ITEM", block = null, item = null)
        assertEquals(1, trigger.annotations.notes.size)

        // Re-fetching (unchanged source → warm hit) must not drop the annotation.
        service.fetchModule(ordersKey)
        assertEquals(
            1,
            service.getElementAnnotations(ordersKey, ElementKind.TRIGGER, "WHEN-VALIDATE-ITEM", null)
                .annotations.notes.size,
        )

        // Changing the source (without re-indexing) flags the note as stale, but still serves it.
        val source = formsDir.resolve("orders_fmb.xml")
        Files.writeString(source, Files.readString(source) + "\n<!-- touched -->")
        val afterChange = service.getElementAnnotations(ordersKey, ElementKind.TRIGGER, "WHEN-VALIDATE-ITEM", null)
        assertEquals(1, afterChange.annotations.notes.size)
        assertTrue(
            afterChange.annotations.notes.single().staleAgainstSource,
            "a note made before the source changed must be flagged staleAgainstSource",
        )
    }

    @Test
    fun relateSearchAndRemove() = runTest {
        service.fetchModule(ordersKey)
        service.annotate(
            ordersKey, ElementKind.PROGRAM_UNIT, "PKG_ORDERS", "PACKAGE_BODY",
            AnnotationKind.TAG, "security-sensitive", Author.AI,
        )
        val relation = service.relate(
            key = ordersKey,
            fromKind = ElementKind.TRIGGER, fromName = "WHEN-VALIDATE-ITEM", fromOwner = null,
            toKind = ElementKind.PROGRAM_UNIT, toName = "PKG_ORDERS", toOwner = "PACKAGE_BODY",
            relType = "calls", note = null, author = Author.AI,
        )

        // Tag filter finds the classification note.
        val byTag = service.searchAnnotations(ordersKey, text = null, kind = null, tag = "security-sensitive")
        assertEquals(1, byTag.notes.size)
        // Text search matches the relation type.
        val byText = service.searchAnnotations(ordersKey, text = "calls", kind = null, tag = null)
        assertEquals(1, byText.relations.size)

        // Removing the relation by id takes it out of subsequent searches.
        assertTrue(service.removeAnnotation(ordersKey, relation.relation.id).removed)
        assertEquals(
            0,
            service.searchAnnotations(ordersKey, text = "calls", kind = null, tag = null).relations.size,
        )
        assertEquals(false, service.removeAnnotation(ordersKey, "no-such-id").removed)
    }

    @Test
    fun annotatingAMissingElementFailsWithAModelDirectedError() = runTest {
        service.fetchModule(ordersKey)
        val error = assertFailsWith<IllegalArgumentException> {
            service.annotate(
                ordersKey, ElementKind.BLOCK, "NOPE", null,
                AnnotationKind.NOTE, "x", Author.AI,
            )
        }
        assertTrue(error.message!!.contains("block"))
    }

    @Test
    fun ambiguousItemAnnotationRequiresOwnerPath() = runTest {
        service.fetchModule(dupesKey)

        val error = assertFailsWith<IllegalArgumentException> {
            service.annotate(dupesKey, ElementKind.ITEM, "ID", null, AnnotationKind.NOTE, "x", Author.AI)
        }
        assertTrue(error.message!!.contains("STOCK"))
        assertTrue(error.message!!.contains("AUDIT"))
        assertTrue(error.message!!.contains("ownerPath"))

        service.annotate(dupesKey, ElementKind.ITEM, "ID", "AUDIT", AnnotationKind.NOTE, "audit id", Author.AI)
        // Same name, different block: distinct annotation buckets.
        assertEquals(
            0,
            service.getElementAnnotations(dupesKey, ElementKind.ITEM, "ID", "STOCK").annotations.notes.size,
        )
        assertEquals(
            "audit id",
            service.getElementAnnotations(dupesKey, ElementKind.ITEM, "ID", "AUDIT")
                .annotations.notes.single().body,
        )
    }

    @Test
    fun packageSpecAndBodyAreDistinctAnnotationTargets() = runTest {
        service.fetchModule(ordersKey)

        val error = assertFailsWith<IllegalArgumentException> {
            service.annotate(ordersKey, ElementKind.PROGRAM_UNIT, "PKG_ORDERS", null, AnnotationKind.NOTE, "x", Author.AI)
        }
        assertTrue(error.message!!.contains("PACKAGE_SPEC"))
        assertTrue(error.message!!.contains("PACKAGE_BODY"))
        assertTrue(error.message!!.contains("ownerPath"))

        service.annotate(
            ordersKey, ElementKind.PROGRAM_UNIT, "PKG_ORDERS", "PACKAGE_BODY",
            AnnotationKind.NOTE, "refresh is a stub", Author.AI,
        )
        // Inline views agree with the resolver: the note is on the body only.
        assertEquals(
            1,
            service.getProgramUnit(ordersKey, "PKG_ORDERS", "PACKAGE_BODY").annotations.notes.size,
        )
        assertEquals(
            0,
            service.getProgramUnit(ordersKey, "PKG_ORDERS", "PACKAGE_SPEC").annotations.notes.size,
        )

        // A unit type that is unique by name stays annotatable without an owner.
        service.annotate(
            ordersKey, ElementKind.PROGRAM_UNIT, "CALC_TOTAL", null,
            AnnotationKind.NOTE, "sums order lines", Author.AI,
        )
        assertEquals(1, service.getProgramUnit(ordersKey, "CALC_TOTAL", null).annotations.notes.size)
    }

    @Test
    fun ambiguousMenuItemAnnotationRequiresOwnerPath() = runTest {
        service.fetchModule(menuKey)

        val error = assertFailsWith<IllegalArgumentException> {
            service.annotate(menuKey, ElementKind.MENU_ITEM, "EXIT", null, AnnotationKind.NOTE, "x", Author.AI)
        }
        assertTrue(error.message!!.contains("FILE_MENU"))
        assertTrue(error.message!!.contains("ownerPath"))

        service.annotate(menuKey, ElementKind.MENU_ITEM, "EXIT", "FILE_MENU", AnnotationKind.NOTE, "exits", Author.AI)
        assertEquals(
            1,
            service.getElementAnnotations(menuKey, ElementKind.MENU_ITEM, "EXIT", "FILE_MENU")
                .annotations.notes.size,
        )
        assertEquals(
            0,
            service.getElementAnnotations(menuKey, ElementKind.MENU_ITEM, "EXIT", "MAIN")
                .annotations.notes.size,
        )
    }

    @Test
    fun sameTriggerNameAtThreeLevelsIsAddressableViaOwnerPath() = runTest {
        service.fetchModule(dupesKey)

        val error = assertFailsWith<IllegalArgumentException> {
            service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null)
        }
        assertTrue(error.message!!.contains("':FORM'"))
        assertTrue(error.message!!.contains("'STOCK'"))
        assertTrue(error.message!!.contains("'STOCK.QTY'"))

        val form = service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = ":FORM")
        assertEquals(TriggerLevel.FORM, form.level)
        assertEquals("-- form level: default navigation", form.text)

        // The exact block-level match wins over the block-wide reading of 'STOCK'.
        val block = service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = "STOCK")
        assertEquals(TriggerLevel.BLOCK, block.level)
        assertEquals("-- block level: next stock record", block.text)

        val item = service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = "STOCK.QTY")
        assertEquals(TriggerLevel.ITEM, item.level)
        assertEquals("-- item level: validate qty", item.text)

        // The original block/item arguments still resolve.
        val legacy = service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = "STOCK", item = "QTY")
        assertEquals(TriggerLevel.ITEM, legacy.level)

        // Annotations through each token land in three distinct buckets, served on the right view.
        service.annotate(dupesKey, ElementKind.TRIGGER, "KEY-NEXT-ITEM", ":FORM", AnnotationKind.NOTE, "form nav", Author.AI)
        service.annotate(dupesKey, ElementKind.TRIGGER, "KEY-NEXT-ITEM", "STOCK", AnnotationKind.NOTE, "block nav", Author.AI)
        service.annotate(dupesKey, ElementKind.TRIGGER, "KEY-NEXT-ITEM", "STOCK.QTY", AnnotationKind.NOTE, "item nav", Author.AI)
        assertEquals(
            listOf("form nav"),
            service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = ":FORM")
                .annotations.notes.map { it.body },
        )
        assertEquals(
            listOf("block nav"),
            service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = "STOCK")
                .annotations.notes.map { it.body },
        )
        assertEquals(
            listOf("item nav"),
            service.getTrigger(dupesKey, "KEY-NEXT-ITEM", block = null, item = null, ownerPath = "STOCK.QTY")
                .annotations.notes.map { it.body },
        )
    }

    @Test
    fun relatingAnAmbiguousTriggerFailsWithOwnerPathHint() = runTest {
        service.fetchModule(dupesKey)
        val error = assertFailsWith<IllegalArgumentException> {
            service.relate(
                key = dupesKey,
                fromKind = ElementKind.TRIGGER, fromName = "KEY-NEXT-ITEM", fromOwner = null,
                toKind = ElementKind.BLOCK, toName = "STOCK", toOwner = null,
                relType = "references", note = null, author = Author.AI,
            )
        }
        assertTrue(error.message!!.contains("ownerPath"))
    }

    @Test
    fun libraryDumpIsServedFromThePldItself() = runTest {
        service.fetchModule(utilsKey)
        val units = service.listProgramUnits(utilsKey).units
        assertEquals(4, units.size)
        val body = service.getProgramUnit(utilsKey, "PKG_UTIL", unitType = "PACKAGE_BODY")
        assertTrue(body.text.startsWith("PACKAGE BODY pkg_util"))
    }
}
