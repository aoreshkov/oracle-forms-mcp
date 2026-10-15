package app.oreshkov.oracleformsmcp.annotation

import app.oreshkov.oracleformsmcp.core.AnnotationsFormatTooNewException
import app.oreshkov.oracleformsmcp.core.AnnotationsUnreadableException
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.CURRENT_ANNOTATIONS_FORMAT_VERSION
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.ModuleAnnotations
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.Relation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

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

    private fun encode(value: ModuleAnnotations): String = Json.encodeToString(value)

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

    @Test
    fun writesAreAtomicUnderConcurrentReads() = runBlocking {
        val small = note("a1", "WHEN-VALIDATE-ITEM")
        val large = small.copy(body = "x".repeat(200_000))
        store.addAnnotation(small)

        val writer = launch(Dispatchers.IO) {
            repeat(60) { store.addAnnotation(if (it % 2 == 0) large else small) }
        }
        val reads = mutableListOf<List<Annotation>>()
        while (writer.isActive) reads += store.forModule(key).annotations
        writer.join()

        assertTrue(reads.size > 10, "expected the reader to observe many writes, got ${reads.size}")
        // Never empty and never torn: every read is one of the two whole files.
        reads.forEachIndexed { i, read ->
            assertEquals(1, read.size, "read #$i saw a partially written file")
            assertTrue(read.single() == small || read.single() == large, "read #$i saw a torn file")
        }
    }

    @Test
    fun writesLeaveNoTempFiles() = runTest {
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        assertEquals(listOf("$key.json"), root.listDirectoryEntries().map { it.name })
    }

    @Test
    fun aWriteOverAnUnreadableFileKeepsItAside() = runTest {
        // What a write interrupted half-way through leaves behind.
        val whole = encode(ModuleAnnotations(key, listOf(note("a0", "KEY-COMMIT"))))
        val damaged = whole.take(whole.length / 2)
        root.resolve("$key.json").writeText(damaged)

        val failure = assertFailsWith<AnnotationsUnreadableException> {
            store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        }

        // The damaged content is kept byte for byte under a name that says why, and nothing was
        // written in its place: the failed call stored nothing.
        val keptAside = root.listDirectoryEntries("$key.json.corrupt-*").single()
        assertEquals(damaged, keptAside.readText())
        assertFalse(root.resolve("$key.json").exists())
        assertContains(failure.message!!, keptAside.name)
        assertContains(failure.message!!, "get_element_annotations")

        // Repeating the call starts a new set, and the kept file survives it.
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        assertEquals(listOf("a1"), store.forModule(key).annotations.map { it.id })
        assertEquals(damaged, keptAside.readText())
    }

    @Test
    fun aWriteGoesToTheFileItChecked() = runTest {
        // A file copied in from another module still names that module inside.
        val other = ModuleKey.of("customers", ModuleType.FORM)
        root.resolve("$key.json").writeText(encode(ModuleAnnotations(other, listOf(note("a0", "KEY-COMMIT")))))

        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))

        assertFalse(root.resolve("$other.json").exists(), "the write landed on a file it never checked")
        val stored = store.forModule(key)
        assertEquals(key, stored.module)
        assertEquals(listOf("a0", "a1"), stored.annotations.map { it.id })
    }

    @Test
    fun aRemoveOverAnUnreadableFileKeepsItAside() = runTest {
        root.resolve("$key.json").writeText("")
        assertFailsWith<AnnotationsUnreadableException> { store.remove(key, "a1") }
        assertEquals(1, root.listDirectoryEntries("$key.json.corrupt-*").size)
    }

    @Test
    fun writesStampTheFormatAndReadUnstampedFiles() = runTest {
        // A file written before the stamp existed has no formatVersion at all.
        val unstamped = encode(ModuleAnnotations(key, listOf(note("a0", "KEY-COMMIT"))))
        assertFalse("formatVersion" in unstamped)
        root.resolve("$key.json").writeText(unstamped)
        store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))

        val stored = store.forModule(key)
        assertEquals(listOf("a0", "a1"), stored.annotations.map { it.id })
        assertEquals(CURRENT_ANNOTATIONS_FORMAT_VERSION, stored.formatVersion)
        assertContains(root.resolve("$key.json").readText(), "\"formatVersion\"")
    }

    @Test
    fun aWriteOverANewerFormatLeavesItUntouched() = runTest {
        // A newer build's file: a higher stamp, and a field this build does not know.
        val newer = encode(
            ModuleAnnotations(
                module = key,
                annotations = listOf(note("a0", "KEY-COMMIT")),
                formatVersion = CURRENT_ANNOTATIONS_FORMAT_VERSION + 1,
            ),
        ).replaceFirst("{", "{ \"somethingNew\": [ 1, 2, 3 ],")
        root.resolve("$key.json").writeText(newer)

        assertFailsWith<AnnotationsFormatTooNewException> {
            store.addAnnotation(note("a1", "WHEN-VALIDATE-ITEM"))
        }
        assertEquals(newer, root.resolve("$key.json").readText())
        assertEquals(listOf("$key.json"), root.listDirectoryEntries().map { it.name })
    }
}
