package app.oreshkov.oracleformsmcp.parse

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlsqlSidecarsTest {

    private val cacheDir: Path = Files.createTempDirectory("sidecars-test")
    private val sidecars = PlsqlSidecars(cacheDir)

    @AfterTest
    fun cleanup() {
        cacheDir.toFile().deleteRecursively()
    }

    private fun readRef(file: String): String = cacheDir.resolve(file).toFile().readText()

    @Test
    fun collidingKeysGetDistinctFilesInWriteOrder() {
        val first = sidecars.write(PlsqlSidecars.TRIGGERS, "FORM.KEY-COMMIT", "commit_form;")
        val second = sidecars.write(PlsqlSidecars.TRIGGERS, "FORM.KEY-COMMIT", "do_block_commit;")
        assertEquals("plsql/triggers/FORM.KEY-COMMIT.sql", first.file)
        assertEquals("plsql/triggers/FORM.KEY-COMMIT~2.sql", second.file)
        assertEquals("commit_form;", readRef(first.file))
        assertEquals("do_block_commit;", readRef(second.file))
    }

    @Test
    fun caseVariantKeysDoNotOverwrite() {
        val upper = sidecars.write(PlsqlSidecars.PROGRAM_UNITS, "FOO.PROCEDURE", "-- upper")
        val lower = sidecars.write(PlsqlSidecars.PROGRAM_UNITS, "Foo.Procedure", "-- lower")
        assertNotEquals(upper.file, lower.file)
        assertEquals("-- upper", readRef(upper.file))
        assertEquals("-- lower", readRef(lower.file))
    }

    @Test
    fun sanitizerCollisionsAreDetected() {
        val slash = sidecars.write(PlsqlSidecars.MENU_ITEMS, "A/B", "-- slash")
        val underscore = sidecars.write(PlsqlSidecars.MENU_ITEMS, "A_B", "-- underscore")
        assertEquals("plsql/menu-items/A_B.sql", slash.file)
        assertEquals("plsql/menu-items/A_B~2.sql", underscore.file)
        assertEquals("-- slash", readRef(slash.file))
        assertEquals("-- underscore", readRef(underscore.file))
    }

    @Test
    fun sameNameInDifferentCategoriesDoesNotSuffix() {
        val trigger = sidecars.write(PlsqlSidecars.TRIGGERS, "SAME", "-- trigger")
        val unit = sidecars.write(PlsqlSidecars.PROGRAM_UNITS, "SAME", "-- unit")
        assertEquals("plsql/triggers/SAME.sql", trigger.file)
        assertEquals("plsql/program-units/SAME.sql", unit.file)
    }

    /**
     * "One line" is not a size. A body that really is one physical line — minified, or escaped in
     * a way [decodeDoubleEscaped] could not prove — would otherwise arrive whole in a field
     * documented as a one-line preview, once per row, on a module with hundreds of triggers.
     */
    @Test
    fun theOneLinePreviewIsCappedAndSaysSo() {
        val preview = firstCodeLine("BEGIN " + "x := x + 1; ".repeat(500))
        assertEquals(PREVIEW_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun aPreviewThatFitsIsLeftAlone() {
        assertEquals("BEGIN", firstCodeLine("\n\n   BEGIN\n  NULL;\nEND;"))
    }
}
