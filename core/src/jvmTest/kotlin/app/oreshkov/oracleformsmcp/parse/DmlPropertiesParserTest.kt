package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.copyFixture
import app.oreshkov.oracleformsmcp.model.BlockDml
import app.oreshkov.oracleformsmcp.model.ItemDml
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.TextEncoding
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a record's insert and update depend on — item and block DML properties, and the property
 * classes that usually supply them — indexed as written, with "not written" kept distinct from
 * `false`.
 */
class DmlPropertiesParserTest {

    private val cacheDir: Path = Files.createTempDirectory("dml-parser-test")
    private val parser = FormsModuleParser()

    @AfterTest
    fun cleanup() {
        cacheDir.toFile().deleteRecursively()
    }

    private fun parse(fixture: String, key: ModuleKey): ModuleIndex {
        val converted = copyFixture(fixture, cacheDir.resolve("converted").createDirectories())
        return parser.parse(key, converted.toString(), cacheDir.toString())
    }

    private fun claims() = parse("claims_fmb.xml", ModuleKey.of("claims", ModuleType.FORM))

    private fun item(index: ModuleIndex, name: String) =
        index.blocks.flatMap { it.items }.single { it.name == name }

    @Test
    fun anItemCarriesOnlyTheDmlPropertiesItWritesItself() {
        val index = claims()

        assertEquals(
            ItemDml(insertAllowed = false, updateAllowed = false),
            item(index, "REFERENCE").dml,
        )
        assertEquals(ItemDml(primaryKey = true), item(index, "CLAIM_ID").dml)
        assertEquals(ItemDml(initialValue = "OPEN"), item(index, "STATUS").dml)
        assertEquals(ItemDml(enabled = false), item(index, "NOTES").dml)
        // Classed, and writes nothing of its own: not "all false", nothing at all. A blank
        // CopyValueFromItem is Forms2XML's way of writing nothing, not a value.
        assertNull(item(index, "TOTAL_DISPLAY").dml)
        assertNull(item(index, "OWNER_NAME").dml)
    }

    @Test
    fun blockDmlAndItsSqlAreRecoveredFromDoubleEscaping() {
        val index = claims()
        val claim = index.blocks.single { it.name == "CLAIM" }

        assertEquals(
            BlockDml(
                deleteAllowed = false,
                whereClause = "status = 'OPEN'\nAND owner = USER",
                orderByClause = "claim_id",
                sqlEncoding = TextEncoding.RECOVERED,
            ),
            claim.dml,
        )
        assertEquals("CLAIMS", claim.queryDataSourceName)

        val summary = index.blocks.single { it.name == "SUMMARY" }
        assertEquals("(SELECT owner, COUNT(*) n\n   FROM claims\n  GROUP BY owner) S", summary.queryDataSourceName)
        val dml = assertNotNull(summary.dml)
        assertEquals(TextEncoding.RECOVERED, dml.sqlEncoding, "a recovered query source must say so")
        assertEquals(false, dml.insertAllowed)
        assertEquals(true, dml.databaseBlock)
        assertNull(dml.whereClause)
    }

    @Test
    fun dataSourceColumnsAreCountedNotIndexed() {
        val index = claims()

        assertEquals(7, index.blocks.single { it.name == "CLAIM" }.dataSourceColumnCount)
        assertEquals(0, index.blocks.single { it.name == "SUMMARY" }.dataSourceColumnCount)
        // An unnamed element gets no ObjectRef, so the columns add nothing else to the index.
        assertEquals(0, index.objectRefs.count { it.objectType == "DataSourceColumn" })
    }

    @Test
    fun propertyClassesKeepTheirPropertiesAndTheirPointer() {
        val classes = claims().propertyClassDetails.associateBy { it.name }

        // A stub: nothing but the pointer to the module that really defines it.
        val stub = classes.getValue("BASE_TEXT")
        assertNull(stub.item)
        assertNull(stub.block)
        val pointer = assertNotNull(stub.inherited)
        assertEquals("styles.fmb", pointer.file)
        assertEquals("BASE_TEXT", pointer.name)

        // A class defined here carries its values and no pointer.
        val local = classes.getValue("LOCAL_TEXT")
        assertEquals(ItemDml(databaseItem = true, required = true, maximumLength = 40), local.item)
        assertNull(local.inherited)
    }

    @Test
    fun aClassBasedOnAnotherClassNamesItAndIsNotInheritance() {
        val classes = parse("styles_fmb.xml", ModuleKey.of("styles", ModuleType.FORM))
            .propertyClassDetails.associateBy { it.name }

        val locked = classes.getValue("LOCKED_TEXT")
        assertEquals("BASE_TEXT", locked.propertyClass)
        assertNull(locked.inherited, "a class in this same module hides nothing")
        assertEquals(ItemDml(updateAllowed = false), locked.item)
        assertEquals(
            ItemDml(databaseItem = true, insertAllowed = true, updateAllowed = true, maximumLength = 30),
            classes.getValue("BASE_TEXT").item,
        )
    }
}
