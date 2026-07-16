package app.oreshkov.oracleformsmcp.annotation

import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.Relation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class OnDiskAnnotationStoreTest {

    private val root: Path = Files.createTempDirectory("forms-annotations-test")
    private val store = OnDiskAnnotationStore(root)

    private val key = ModuleKey.of("orders", ModuleType.FORM)
    private fun element(name: String) = ElementId(key, ElementKind.TRIGGER, name)
    private fun note(id: String, name: String) = Annotation(
        id = id,
        target = element(name),
        kind = AnnotationKind.NOTE,
        body = "note $id",
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun roundTripsAnnotationsAndRelations() = runTest {
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        store.addRelation(
            Relation(
                id = "r1",
                from = element("WHEN-VALIDATE-ITEM"),
                to = ElementId(key, ElementKind.PROGRAM_UNIT, "PKG_ORDERS"),
                relType = "calls",
                createdAt = Instant.fromEpochMilliseconds(0),
            ),
        )
        val stored = store.forModule(key)
        assertEquals(1, stored.annotations.size)
        assertEquals(1, stored.relations.size)
        assertEquals("note a1", stored.annotations.single().body)
    }

    @Test
    fun persistsAcrossStoreInstances() = runTest {
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        // A fresh store over the same root reads the file back — durable, not in-memory.
        assertEquals(1, OnDiskAnnotationStore(root).forModule(key).annotations.size)
    }

    @Test
    fun replacesByIdRatherThanDuplicating() = runTest {
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM").copy(body = "updated"))
        val stored = store.forModule(key)
        assertEquals(1, stored.annotations.size)
        assertEquals("updated", stored.annotations.single().body)
    }

    @Test
    fun removeReportsWhetherSomethingWasRemoved() = runTest {
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        assertTrue(store.remove(key, "a1"))
        assertFalse(store.remove(key, "a1"))
        assertEquals(0, store.forModule(key).annotations.size)
    }

    @Test
    fun missingModuleReadsAsEmpty() = runTest {
        assertEquals(0, store.forModule(ModuleKey.of("nope", ModuleType.MENU)).annotations.size)
    }

    @Test
    fun corruptFileDegradesToEmpty() = runTest {
        root.resolve("$key.json").writeText("{ not json !!")
        assertEquals(0, store.forModule(key).annotations.size)
    }
}
