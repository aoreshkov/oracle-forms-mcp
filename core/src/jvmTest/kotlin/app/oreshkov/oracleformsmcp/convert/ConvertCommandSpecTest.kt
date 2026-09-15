package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ConverterNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OUT = "/xml/orders_fmb.xml"

class ConvertCommandSpecTest {

    private val temp: Path = Files.createTempDirectory("convert-command-spec")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun splitsACommandIntoItsProgramAndArguments() {
        assertEquals(
            listOf("frmf2xml", "OVERWRITE=YES", "USE_PROPERTY_IDS=NO"),
            ConvertCommandSpec.parse("  frmf2xml OVERWRITE=YES   USE_PROPERTY_IDS=NO  "),
        )
    }

    @Test
    fun keepsAQuotedRunTogether() {
        assertEquals(
            listOf("C:\\Program Files\\tools\\conv.bat", "-xml", "out dir"),
            ConvertCommandSpec.parse("\"C:\\Program Files\\tools\\conv.bat\" -xml 'out dir'"),
        )
    }

    /** Quotes may open and close mid-token, so `--out="my dir"` is one argument. */
    @Test
    fun closesQuotesInsideAToken() {
        assertEquals(listOf("conv", "--out=my dir"), ConvertCommandSpec.parse("conv --out=\"my dir\""))
    }

    /** A backslash is an ordinary character — Windows paths must not need doubling. */
    @Test
    fun treatsBackslashesAsLiteral() {
        assertEquals(
            listOf("C:\\tools\\fmb2xml.bat", "/out:C:\\xml"),
            ConvertCommandSpec.parse("C:\\tools\\fmb2xml.bat /out:C:\\xml"),
        )
    }

    @Test
    fun acceptsAJsonArrayVerbatim() {
        assertEquals(
            listOf("wine", "C:\\tools\\conv exe\\f2x.exe", "-xml"),
            ConvertCommandSpec.parse("""["wine", "C:\\tools\\conv exe\\f2x.exe", "-xml"]"""),
        )
    }

    /**
     * The pre-existing "one executable" contract: a value naming a real file is taken whole, so an
     * unquoted path with spaces keeps working.
     */
    @Test
    fun takesAnExistingFileWholeEvenWithSpaces() {
        val script = temp.resolve("program files").createDirectories().resolve("conv me.bat")
        script.writeText("@echo off")

        assertEquals(listOf(script.toString()), ConvertCommandSpec.parse(script.toString()))
    }

    @Test
    fun substitutesTheModulePathAtThePlaceholder() {
        assertEquals(
            listOf("conv", "--in", "/forms/ORDERS.fmb", "--xml"),
            ConvertCommandSpec.argvFor(listOf("conv", "--in", "{}", "--xml"), "/forms/ORDERS.fmb", OUT),
        )
    }

    @Test
    fun substitutesThePlaceholderInsideAnArgument() {
        assertEquals(
            listOf("conv", "--in=/forms/ORDERS.fmb"),
            ConvertCommandSpec.argvFor(listOf("conv", "--in={}"), "/forms/ORDERS.fmb", OUT),
        )
    }

    /** Without a placeholder the path is appended — what the older calling convention did. */
    @Test
    fun appendsTheModulePathWhenNoPlaceholderIsGiven() {
        assertEquals(
            listOf("conv", "--xml", "/forms/ORDERS.fmb"),
            ConvertCommandSpec.argvFor(listOf("conv", "--xml"), "/forms/ORDERS.fmb", OUT),
        )
    }

    /** What frmcmp needs: it writes beside the module unless told the output file. */
    @Test
    fun substitutesTheOutputPathAtItsPlaceholder() {
        assertEquals(
            listOf("frmcmp_batch", "module=/forms/UTILS.pll", "output_file=/xml/utils.pld"),
            ConvertCommandSpec.argvFor(
                listOf("frmcmp_batch", "module={}", "output_file={out}"), "/forms/UTILS.pll", "/xml/utils.pld",
            ),
        )
    }

    /** A command that writes into its working directory must not be handed an extra argument. */
    @Test
    fun neverAppendsTheOutputPath() {
        assertEquals(
            listOf("conv", "/forms/ORDERS.fmb"),
            ConvertCommandSpec.argvFor(listOf("conv", "{}"), "/forms/ORDERS.fmb", OUT),
        )
        assertEquals(
            listOf("conv", "--out", OUT, "/forms/ORDERS.fmb"),
            ConvertCommandSpec.argvFor(listOf("conv", "--out", "{out}"), "/forms/ORDERS.fmb", OUT),
        )
    }

    /** A path that itself contains a placeholder is inserted verbatim, never substituted again. */
    @Test
    fun substitutesBothPlaceholdersInOnePass() {
        assertEquals(
            listOf("conv", "/forms/{out}/ORDERS.fmb:/xml/{}/orders_fmb.xml"),
            ConvertCommandSpec.argvFor(
                listOf("conv", "{}:{out}"), "/forms/{out}/ORDERS.fmb", "/xml/{}/orders_fmb.xml",
            ),
        )
    }

    /** `Regex.replace` with a string replacement would treat `$1` or a backslash as syntax. */
    @Test
    fun keepsDollarSignsAndBackslashesInSubstitutedPaths() {
        assertEquals(
            listOf("conv", "C:\\forms\\\$1\\ORDERS.fmb", "C:\\xml\\\$0\\orders_fmb.xml"),
            ConvertCommandSpec.argvFor(
                listOf("conv", "{}", "{out}"), "C:\\forms\\\$1\\ORDERS.fmb", "C:\\xml\\\$0\\orders_fmb.xml",
            ),
        )
    }

    @Test
    fun namesTheCompileOptionInItsMessages() {
        val error = assertFailsWith<ConverterNotFoundException> {
            ConvertCommandSpec.parse("   ", ConverterOption.COMPILE)
        }

        assertTrue("--compile-command" in error.message!!)
        assertTrue("OFMCP_COMPILE_COMMAND" in error.message!!)
        assertTrue("{out}" in error.message!!)
        assertFalse("--convert-command" in error.message!!)
    }

    @Test
    fun rejectsAnUnterminatedQuoteWithAnActionableMessage() {
        val error = assertFailsWith<ConverterNotFoundException> {
            ConvertCommandSpec.parse("\"C:\\tools\\conv.bat --xml")
        }

        assertTrue("unterminated" in error.message!!)
        assertTrue("--convert-command" in error.message!!)
        assertTrue("OFMCP_CONVERT_COMMAND" in error.message!!)
    }

    @Test
    fun rejectsMalformedJson() {
        val error = assertFailsWith<ConverterNotFoundException> {
            ConvertCommandSpec.parse("""["wine", "conv.exe" """)
        }

        assertTrue("JSON" in error.message!!)
    }

    @Test
    fun rejectsAJsonArrayThatIsEmptyOrNotAllStrings() {
        assertFailsWith<ConverterNotFoundException> { ConvertCommandSpec.parse("[]") }
        assertFailsWith<ConverterNotFoundException> { ConvertCommandSpec.parse("""["conv", 7]""") }
    }
}
