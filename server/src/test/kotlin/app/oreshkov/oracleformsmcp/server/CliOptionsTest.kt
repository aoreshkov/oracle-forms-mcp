package app.oreshkov.oracleformsmcp.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The converter options have to survive every channel that configures them: a plain command line,
 * an MCPB bundle or Claude Code plugin substituting user configuration into arguments, and a
 * container that can only set environment variables.
 */
class CliOptionsTest {

    private val noEnv: (String) -> String? = { null }

    private fun parse(vararg args: String, env: (String) -> String? = noEnv) = parseArgs(arrayOf(*args), env)

    @Test
    fun readsBothConverterOptionsFromFlags() {
        val options = parse(
            "--forms-dir", "/srv/forms",
            "--convert-command", "/opt/tools/fmb2xml.sh",
            "--converted-dir", "/srv/forms-xml",
        )
        assertEquals(Path.of("/srv/forms"), options.formsDir)
        assertEquals("/opt/tools/fmb2xml.sh", options.convertCommand)
        assertEquals(Path.of("/srv/forms-xml"), options.convertedDir)
    }

    @Test
    fun fallsBackToTheEnvironmentWhenTheFlagsAreAbsent() {
        val env = mapOf(
            ENV_CONVERT_COMMAND to "/opt/tools/fmb2xml.sh",
            ENV_CONVERTED_DIR to "/srv/forms-xml",
        )
        val options = parse("/srv/forms", env = env::get)
        assertEquals("/opt/tools/fmb2xml.sh", options.convertCommand)
        assertEquals(Path.of("/srv/forms-xml"), options.convertedDir)
    }

    @Test
    fun flagsWinOverTheEnvironment() {
        val env = mapOf(
            ENV_CONVERT_COMMAND to "/from/env.sh",
            ENV_CONVERTED_DIR to "/from/env",
        )
        val options = parse(
            "--forms-dir", "/srv/forms",
            "--convert-command", "/from/flag.sh",
            "--converted-dir", "/from/flag",
            env = env::get,
        )
        assertEquals("/from/flag.sh", options.convertCommand)
        assertEquals(Path.of("/from/flag"), options.convertedDir)
    }

    @Test
    fun blankAndUnsubstitutedTemplatesCountAsUnset() {
        // What a launcher passes for an optional user-config value the user never filled in.
        val options = parse(
            "--forms-dir", "/srv/forms",
            "--convert-command", "  ",
            "--converted-dir", "\${user_config.converted_dir}",
        )
        assertNull(options.convertCommand)
        assertNull(options.convertedDir)

        val env = mapOf(
            ENV_CONVERT_COMMAND to "\${user_config.convert_command}",
            ENV_CONVERTED_DIR to "",
        )
        val fromEnv = parse("/srv/forms", env = env::get)
        assertNull(fromEnv.convertCommand)
        assertNull(fromEnv.convertedDir)
    }

    /**
     * `--convert-command` is a whole command line, so it reaches the server as one argument (or one
     * variable) and is passed to the converter unsplit — the argv split belongs to the converter,
     * which does it without a shell.
     */
    @Test
    fun keepsAConverterCommandLineWithArgumentsIntact() {
        val command = "\"/opt/my tools/f2x.sh\" --xml {}"
        assertEquals(command, parse("/srv/forms", "--convert-command", command).convertCommand)

        val env = mapOf(ENV_CONVERT_COMMAND to """["wine", "f2x.exe", "{}"]""")
        assertEquals("""["wine", "f2x.exe", "{}"]""", parse("/srv/forms", env = env::get).convertCommand)
    }

    @Test
    fun readsTheCompileCommandFromItsFlagOrItsVariable() {
        val command = "frmcmp_batch Module={} Module_Type=LIBRARY Output_File={out}"
        assertEquals(command, parse("/srv/forms", "--compile-command", command).compileCommand)

        val env = mapOf(ENV_COMPILE_COMMAND to """["frmcmp_batch", "Module={}", "Output_File={out}"]""")
        assertEquals(
            """["frmcmp_batch", "Module={}", "Output_File={out}"]""",
            parse("/srv/forms", env = env::get).compileCommand,
        )
    }

    @Test
    fun theCompileCommandFlagWinsOverItsVariable() {
        val env = mapOf(ENV_COMPILE_COMMAND to "/from/env.sh")
        assertEquals(
            "/from/flag.sh",
            parse("/srv/forms", "--compile-command", "/from/flag.sh", env = env::get).compileCommand,
        )
    }

    /** Both channels that fill in user configuration pass an unset `compile_command` somehow. */
    @Test
    fun anUnsetCompileCommandIsNull() {
        assertNull(parse("/srv/forms", "--compile-command", "\${user_config.compile_command}").compileCommand)
        assertNull(parse("/srv/forms", "--compile-command", " ").compileCommand)
        val env = mapOf(ENV_COMPILE_COMMAND to "\${user_config.compile_command}")
        assertNull(parse("/srv/forms", env = env::get).compileCommand)
        // Setting it leaves the whole-server converter alone.
        assertNull(parse("/srv/forms", "--compile-command", "/opt/c.sh").convertCommand)
    }

    @Test
    fun anUnsetOptionalOptionDoesNotShadowTheEnvironment() {
        // The flag is present but empty (a bundle always passes it); the variable still applies.
        val env = mapOf(ENV_CONVERTED_DIR to "/srv/forms-xml")
        val options = parse("--forms-dir", "/srv/forms", "--converted-dir", "", env = env::get)
        assertEquals(Path.of("/srv/forms-xml"), options.convertedDir)
    }
}
