package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.core.ModuleIndexOutdatedException
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.TextEncoding
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Master-detail relations through `get_block`, over the real pipeline.
 *
 * In Forms the relations a block takes part in decide what it can be queried through — with
 * `PreventMasterlessOperations` a detail block is reachable only from its master — so "what can this
 * screen see" has to be answerable from `get_block` without knowing to grep the XML for an element
 * no tool names. `ORDERS` is the master of `ORDER_LINES` in the sample forms.
 */
class BlockRelationsTest {

    private val temp: Path = Files.createTempDirectory("block-relations-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))
    private val cache = OnDiskModuleCache(temp.resolve("cache"))
    private val ordersKey = ModuleKey.of("orders", ModuleType.FORM)
    private val pickerKey = ModuleKey.of("picker", ModuleType.FORM)

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = cache,
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
    )

    init {
        copyFixture("orders_fmb.xml")
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

    /**
     * The master carries the relation as Forms wrote it; the detail names its master without the
     * relation being stored twice. Both come back at the default verbosity, since they are
     * structure, not description.
     */
    @Test
    fun theMasterServesItsRelationsAndTheDetailNamesItsMaster() = runTest {
        service.fetchModule(ordersKey)

        val master = service.getBlock(ordersKey, "ORDERS")
        val relation = master.block.relations.single()
        assertEquals("ORDER_LINES", relation.detailBlock)
        assertEquals(true, relation.preventMasterlessOperations)
        assertEquals(TextEncoding.RECOVERED, relation.joinEncoding)
        assertTrue(master.detailOf.isEmpty())

        val detail = service.getBlock(ordersKey, "order_lines")
        assertTrue(detail.block.relations.isEmpty())
        val through = detail.detailOf.single()
        assertEquals("ORDERS", through.masterBlock)
        assertEquals(relation, through.relation)
        assertNull(detail.hint, "a block with nothing missing has nothing to say")

        val unrelated = service.getBlock(ordersKey, "CONTROL")
        assertTrue(unrelated.block.relations.isEmpty())
        assertTrue(unrelated.detailOf.isEmpty())
    }

    /** The relation named by `get_block` is the one `get_object_xml` reads, with its master as owner. */
    @Test
    fun aServedRelationIsReadableRawWithItsMasterAsOwner() = runTest {
        service.fetchModule(ordersKey)
        val relation = service.getBlock(ordersKey, "ORDER_LINES").detailOf.single()

        val xml = service.getObjectXml(ordersKey, "Relation", relation.relation.name, owner = relation.masterBlock)

        assertTrue(xml.xml.trimStart().startsWith("<Relation"), xml.xml)
        assertTrue(xml.xml.contains("PreventMasterlessOperations=\"true\""))
    }

    /** Size is descriptive: detailed rows carry it, concise rows do not. */
    @Test
    fun itemSizeIsServedAtDetailedVerbosityOnly() = runTest {
        service.fetchModule(ordersKey)

        val concise = service.getBlock(ordersKey, "ORDERS").block.items.first { it.name == "ORDER_ID" }
        assertNull(concise.width)
        assertNull(concise.height)

        val detailed = service.getBlock(ordersKey, "ORDERS", detailed = true).block.items.associateBy { it.name }
        assertEquals(60, detailed.getValue("ORDER_ID").width)
        assertEquals(17, detailed.getValue("ORDER_ID").height)
        assertNull(detailed.getValue("CUSTOMER_NAME").width, "not written is not zero")
    }

    /**
     * A subclassed block stores only its overrides, so "no relations" here is not a fact about the
     * form — the hint says where they are.
     */
    @Test
    fun aSubclassedBlockWithNoRelationsSaysTheyAreDefinedWithItsParent() = runTest {
        service.fetchModule(pickerKey)

        val subclassed = service.getBlock(pickerKey, "BAR_LIST")
        assertTrue(subclassed.block.relations.isEmpty())
        assertTrue(assertNotNull(subclassed.hint).contains("no relations on the subclassed block"), subclassed.hint)

        assertNull(service.getBlock(pickerKey, "CUSTOMERS").hint)
    }

    /** An inherited relation that writes no join here must not read as a relation without a join. */
    @Test
    fun anInheritedRelationWithoutAJoinSaysWhereTheJoinIsDefined() = runTest {
        val key = ModuleKey.of("sublines", ModuleType.FORM)
        formsDir.resolve("sublines_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="SUBLINES">
            |    <Block Name="HEADER" ParentModule="TOOLBAR" ParentName="BAR" ParentFilename="toolbar.fmb">
            |      <Relation Name="HEADER_LINES" SubclassSubObject="true" DetailBlock="LINES"/>
            |    </Block>
            |    <Block Name="LINES" QueryDataSourceName="LINES"/>
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
        service.fetchModule(key)

        val header = service.getBlock(key, "HEADER")
        val relation = header.block.relations.single()
        assertNull(relation.joinCondition)
        assertEquals("toolbar.fmb", assertNotNull(relation.inherited).file)
        val hint = assertNotNull(header.hint)
        assertTrue(hint.contains("HEADER_LINES are subclassed and write no join condition here"), hint)
        assertTrue(!hint.contains("no relations on the subclassed block"), hint)

        // The detail side says the same about the relation it is reached through.
        val lines = service.getBlock(key, "LINES")
        assertTrue(assertNotNull(lines.hint).contains("HEADER_LINES are subclassed"), lines.hint)
    }

    /**
     * An index written before relations were parsed answers "no relations" for every block, which
     * reads as plausible. It is reported outdated and healed by a re-parse that finds them.
     */
    @Test
    fun anIndexFromBeforeRelationsIsOutdatedAndHealsWithThem() = runTest {
        service.fetchModule(ordersKey)
        val current = assertNotNull(cache.get(ordersKey))
        cache.putIndex(
            current.copy(
                indexVersion = 2,
                blocks = current.blocks.map { block ->
                    block.copy(relations = emptyList(), items = block.items.map { it.copy(width = null, height = null) })
                },
            ),
        )

        val outdated = assertFailsWith<ModuleIndexOutdatedException> { service.getBlock(ordersKey, "ORDERS") }
        assertTrue(outdated.message!!.contains("fetch_module"))

        service.fetchModule(ordersKey)

        assertEquals("ORDERS", service.getBlock(ordersKey, "ORDER_LINES").detailOf.single().masterBlock)
    }
}
