package app.oreshkov.oracleformsmcp.server.prompts

import app.oreshkov.oracleformsmcp.annotation.OnDiskAnnotationStore
import app.oreshkov.oracleformsmcp.cache.OnDiskModuleCache
import app.oreshkov.oracleformsmcp.convert.PreConvertedCopyConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.parse.FormsModuleParser
import app.oreshkov.oracleformsmcp.scan.FormsDirectoryScannerImpl
import app.oreshkov.oracleformsmcp.server.BigModules
import app.oreshkov.oracleformsmcp.server.FormsService
import app.oreshkov.oracleformsmcp.server.MAX_RESULT_CHARS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `explain_module` puts the index in front of the model and tells it to explain the module
 * "strictly" from that. So the text is held to what the tools are held to: an inherited body is
 * never presented as an empty one, and no list is unbounded.
 */
class ExplainModulePromptTest {

    private val temp: Path = Files.createTempDirectory("explain-module-test")
    private val formsDir: Path = Files.createDirectories(temp.resolve("forms"))

    private val service = FormsService(
        scanner = FormsDirectoryScannerImpl(formsDir),
        converter = PreConvertedCopyConverter(),
        parser = FormsModuleParser(),
        cache = OnDiskModuleCache(temp.resolve("cache")),
        annotationStore = OnDiskAnnotationStore(temp.resolve("annotations")),
        formsDir = formsDir,
    )

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String) {
        val resource = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        resource.use { Files.copy(it, formsDir.resolve(name)) }
    }

    private fun lineFor(text: String, vararg parts: String): String =
        text.lines().singleOrNull { line -> parts.all { it in line } }
            ?: error("no single line with ${parts.toList()} in:\n$text")

    @Test
    fun anInheritedBodyIsShownAsAPointerNotAsAnEmptyOne() = runTest {
        copyFixture("picker_fmb.xml")
        val key = ModuleKey.of("picker", ModuleType.FORM)
        service.fetchModule(key)

        val text = explainModuleText(service, key)

        val trigger = lineFor(text, "BAR_LIST.SELECT", "WHEN-BUTTON-PRESSED")
        assertTrue(trigger.contains("inherited from TOOLBAR"), trigger)
        assertTrue(trigger.contains("get_trigger") && trigger.contains("resolve=true"), trigger)

        val unit = lineFor(text, "BAR_REFRESH")
        assertTrue(unit.contains("inherited from TOOLBAR"), unit)
        assertTrue(unit.contains("get_program_unit") && unit.contains("resolve=true"), unit)
        assertFalse(unit.contains("lines)"), "an inherited unit has no line count here: $unit")

        // An object-group member names its group, the way the tool hints do.
        assertTrue(lineFor(text, "SHOW_INFO").contains("object group STD"), text)

        // A local override is this module's own code, and shows it.
        val override = lineFor(text, "BAR_LIST.CANCEL")
        assertTrue(override.contains("overridden locally") && !override.contains("inherited"), override)
        // The instructions say what an inherited entry means before the model reads one.
        assertTrue(text.contains("not empty"), text)
    }

    @Test
    fun everyListIsCappedAndSaysWhatItLeftOut() = runTest {
        BigModules.writeForm(formsDir)
        val key = ModuleKey.of("big", ModuleType.FORM)
        service.fetchModule(key)

        val text = explainModuleText(service, key)

        assertTrue(text.length < MAX_RESULT_CHARS, "a generated form must not flood the context: ${text.length}")
        assertTrue(text.contains("more blocks omitted") && text.contains("list_blocks"), text)
        assertTrue(text.contains("more program units omitted") && text.contains("list_program_units"), text)
        assertTrue(text.contains("more attached libraries omitted"), text)
        // A block's items are cut inside its own line, with the call that lists them all.
        val block = lineFor(text, "- BLOCK_1 ")
        assertTrue(block.contains("more") && block.contains("get_block(module=\"BIG.fmb\", block=\"BLOCK_1\")"), block)
    }

    @Test
    fun menusAreCappedToo() = runTest {
        BigModules.writeMenu(formsDir)
        val key = ModuleKey.of("bigmenu", ModuleType.MENU)
        service.fetchModule(key)

        val text = explainModuleText(service, key)

        assertTrue(text.length < MAX_RESULT_CHARS, "${text.length}")
        assertTrue(text.contains("more menus omitted"), text)
        assertTrue(lineFor(text, "- MENU_1 ").contains("more"), text)
    }
}
