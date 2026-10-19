package app.oreshkov.oracleformsmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The converter options have to survive every channel that configures them: a plain command line,
 * an MCPB bundle or Claude Code plugin substituting user configuration into arguments, and a
 * container that can only set environment variables.
 *
 * Each test runs the real program in-process, with a start function that records the options it
 * was handed instead of serving, so the parse, the constraints and the warnings are the ones a
 * launched server sees.
 */
class CliOptionsTest {

    private val temp: Path = Files.createTempDirectory("cli-options")

    @AfterTest
    fun cleanup() {
        temp.toFile().deleteRecursively()
    }

    private class Run(val result: RunResult, val options: CliOptions?)

    private fun run(vararg args: String, env: Map<String, String> = emptyMap()): Run {
        var captured: CliOptions? = null
        val result = program(env::get) { options, _ ->
            captured = options
            ExitStatus.OK
        }.record(*args).assertPipeSafe()
        return Run(result, captured)
    }

    /** The options a command line that parses hands to the server. */
    private fun parse(vararg args: String, env: Map<String, String> = emptyMap()): CliOptions {
        val run = run(*args, env = env)
        assertEquals(ExitStatus.OK, run.result.status, run.result.stderr)
        return checkNotNull(run.options)
    }

    /** The message of a command line that does not parse; it never reaches the server. */
    private fun refused(vararg args: String, env: Map<String, String> = emptyMap()): String {
        val run = run(*args, env = env)
        assertEquals(ExitStatus.USAGE, run.result.status, run.result.stderr)
        assertNull(run.options, "a refused command line must not start the server")
        assertEquals("", run.result.stdout, "a usage error goes to stderr")
        return run.result.stderr
    }

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
            ENV_ORACLE_HOME to "/u01/oracle/forms",
        )
        val options = parse("/srv/forms", env = env)
        assertEquals("/opt/tools/fmb2xml.sh", options.convertCommand)
        assertEquals(Path.of("/srv/forms-xml"), options.convertedDir)
        assertEquals("/u01/oracle/forms", options.oracleHome)
    }

    @Test
    fun prefetchTakesAPatternOrAllAndAnOptionalType() {
        assertNull(parse("/srv/forms").prefetch, "serving is the default")
        assertEquals(Prefetch(pattern = "ORDER"), parse("/srv/forms", "--prefetch", "ORDER").prefetch)
        assertEquals(Prefetch(pattern = null), parse("/srv/forms", "--prefetch-all").prefetch)
        assertEquals(Prefetch(pattern = null), parse("/srv/forms", "--prefetch", "*").prefetch)
        assertEquals(
            Prefetch(pattern = "ORDER", type = ModuleType.LIBRARY),
            parse("/srv/forms", "--prefetch", "ORDER", "--prefetch-type", "library").prefetch,
        )
        assertEquals(
            Prefetch(type = ModuleType.OBJECT_LIBRARY),
            parse("/srv/forms", "--prefetch-type", "OBJECT_LIBRARY").prefetch,
            "a type alone prefetches every module of it, and its name matches in any case",
        )
    }

    @Test
    fun aPatternAndAllTogetherAreRefusedRatherThanLastWins() {
        assertContains(refused("/srv/forms", "--prefetch", "ORDER", "--prefetch-all"), "cannot be combined")
    }

    /** Serving, stdout carries the protocol, so there is no summary for `--json` to shape. */
    @Test
    fun jsonIsForAPrefetchOnly() {
        assertEquals(Prefetch(json = true), parse("/srv/forms", "--prefetch-all", "--json").prefetch)
        assertContains(refused("/srv/forms", "--json"), "--json applies only to a prefetch")
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
            env = env,
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
        val fromEnv = parse("/srv/forms", env = env)
        assertNull(fromEnv.convertCommand)
        assertNull(fromEnv.convertedDir)
    }

    @Test
    fun valuesAreTrimmed() {
        val options = parse("--forms-dir", " /srv/forms ", "--convert-command", " /opt/c.sh ")
        assertEquals(Path.of("/srv/forms"), options.formsDir)
        assertEquals("/opt/c.sh", options.convertCommand)
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
        assertEquals(command, parse("/srv/forms", "--convert-command=$command").convertCommand)

        val env = mapOf(ENV_CONVERT_COMMAND to """["wine", "f2x.exe", "{}"]""")
        assertEquals("""["wine", "f2x.exe", "{}"]""", parse("/srv/forms", env = env).convertCommand)
    }

    @Test
    fun readsTheCompileCommandFromItsFlagOrItsVariable() {
        val command = "frmcmp_batch Module={} Module_Type=LIBRARY Output_File={out}"
        assertEquals(command, parse("/srv/forms", "--compile-command", command).compileCommand)

        val env = mapOf(ENV_COMPILE_COMMAND to """["frmcmp_batch", "Module={}", "Output_File={out}"]""")
        assertEquals(
            """["frmcmp_batch", "Module={}", "Output_File={out}"]""",
            parse("/srv/forms", env = env).compileCommand,
        )
    }

    @Test
    fun theCompileCommandFlagWinsOverItsVariable() {
        val env = mapOf(ENV_COMPILE_COMMAND to "/from/env.sh")
        assertEquals(
            "/from/flag.sh",
            parse("/srv/forms", "--compile-command", "/from/flag.sh", env = env).compileCommand,
        )
    }

    /** Both channels that fill in user configuration pass an unset `compile_command` somehow. */
    @Test
    fun anUnsetCompileCommandIsNull() {
        assertNull(parse("/srv/forms", "--compile-command", "\${user_config.compile_command}").compileCommand)
        assertNull(parse("/srv/forms", "--compile-command", " ").compileCommand)
        val env = mapOf(ENV_COMPILE_COMMAND to "\${user_config.compile_command}")
        assertNull(parse("/srv/forms", env = env).compileCommand)
        // Setting it leaves the whole-server converter alone.
        assertNull(parse("/srv/forms", "--compile-command", "/opt/c.sh").convertCommand)
    }

    /**
     * Only an unfilled `${user_config.…}` template is unset. A real value can contain `${` — a
     * shell wrapper around a converter is the usual case — and dropping it would silently fall
     * back to copy-mode, and let the environment beat a flag.
     */
    @Test
    fun aRealValueContainingADollarBraceIsKept() {
        val wrapper = "sh -c 'conv \"\${1}\"' _ {}"
        val env = mapOf(ENV_CONVERT_COMMAND to "/from/env.sh")
        val options = parse(
            "/srv/forms",
            "--convert-command", wrapper,
            "--converted-dir", "/srv/\${site}/xml",
            env = env,
        )
        assertEquals(wrapper, options.convertCommand, "the flag must win over the environment")
        assertEquals(Path.of("/srv/\${site}/xml"), options.convertedDir)
    }

    @Test
    fun aDiscardedTemplateIsReportedAndABlankIsNot() {
        val stderr = run(
            "/srv/forms", "--converted-dir", " \${user_config.converted_dir} ", "--compile-command", "",
            env = mapOf(ENV_CONVERT_COMMAND to "\${user_config.convert_command}"),
        ).result.stderr
        val warnings = stderr.lines().filter { it.startsWith("warning:") }
        assertEquals(2, warnings.size, stderr)
        assertTrue(warnings.any { it.startsWith("warning: --converted-dir: ") && "user_config.converted_dir" in it }, stderr)
        assertTrue(warnings.any { it.startsWith("warning: $ENV_CONVERT_COMMAND: ") }, stderr)
    }

    @Test
    fun anUnsetOptionalOptionDoesNotShadowTheEnvironment() {
        // The flag is present but empty (a bundle always passes it); the variable still applies.
        val env = mapOf(ENV_CONVERTED_DIR to "/srv/forms-xml")
        val options = parse("--forms-dir", "/srv/forms", "--converted-dir", "", env = env)
        assertEquals(Path.of("/srv/forms-xml"), options.convertedDir)
    }

    @Test
    fun theFormsDirectoryIsTheFlagOrThePositionalExactlyOnce() {
        assertEquals(Path.of("/srv/forms"), parse("/srv/forms").formsDir)
        // An unfilled bundle template gives way to the positional, after a warning.
        val run = run("--forms-dir", "\${user_config.forms_dir}", "/srv/forms")
        assertEquals(Path.of("/srv/forms"), run.options?.formsDir)
        assertContains(run.result.stderr, "warning: --forms-dir: ignoring")

        assertContains(refused(), "exactly one of --forms-dir and <forms-dir> is required")
        assertContains(refused("--forms-dir", "/a", "/b"), "cannot be combined")
        assertContains(refused("--forms-dir", "\${user_config.forms_dir}"), "which counts as not set")
    }

    @Test
    fun theHttpHostIsLoopbackUnlessTheFlagOrTheEnvironmentSaysOtherwise() {
        assertEquals("127.0.0.1", parse("/srv/forms").host)
        assertEquals("0.0.0.0", parse("/srv/forms", "--host", "0.0.0.0").host)
        assertEquals("0.0.0.0", parse("/srv/forms", env = mapOf(ENV_HTTP_HOST to "0.0.0.0")).host)
        assertEquals(
            "10.0.0.5",
            parse("/srv/forms", "--host", "10.0.0.5", env = mapOf(ENV_HTTP_HOST to "0.0.0.0")).host,
            "a flag wins over its variable",
        )
        assertEquals(
            "127.0.0.1",
            parse("/srv/forms", env = mapOf(ENV_HTTP_HOST to "\${user_config.http_host}")).host,
            "an unfilled launcher template is unset, not an address",
        )
        assertContains(refused("/srv/forms", "--host", " "), "expected an address")
    }

    /** An http setting under stdio would be ignored without a word; the host is exempt, see `Main.kt`. */
    @Test
    fun httpSettingsNeedTheHttpTransport() {
        assertContains(refused("/srv/forms", "--port", "8080"), "--port requires --transport http")
        assertContains(refused("/srv/forms", "--transport", "stdio", "--allowed-host", "a.example"), "--allowed-host requires")
        val http = parse("/srv/forms", "--transport", "http", "--port", "8080", "--allowed-origin", "https://a.example")
        assertEquals(TransportKind.HTTP, http.transport)
        assertEquals(8080, http.port)
        assertEquals(listOf("https://a.example"), http.allowedOrigins)
        assertEquals(TransportKind.STDIO, parse("/srv/forms", env = mapOf(ENV_HTTP_HOST to "0.0.0.0")).transport)
    }

    @Test
    fun malformedValuesAreUsageErrors() {
        assertContains(refused("/srv/forms", "--transport", "sse"), "is not one of stdio|http")
        assertContains(refused("/srv/forms", "--transport", "http", "--port", "70000"), "--port")
        assertContains(refused("/srv/forms", "--conversion-timeout", "0"), "--conversion-timeout")
        assertContains(refused("/srv/forms", "--prefetch-type", "report"), "--prefetch-type")
        assertContains(refused("/srv/forms", "--convert-comand", "x"), "did you mean --convert-command?")
    }

    @Test
    fun helpAndVersionArePrintedOnStdout() {
        val help = run("--help").result
        assertEquals(ExitStatus.OK, help.status)
        assertContains(help.stdout, "Usage:")
        assertContains(help.stdout, "Environment:")
        assertEquals("", help.stderr)

        val version = run("--version").result
        assertEquals(ExitStatus.OK, version.status)
        assertEquals("oracle-forms-mcp ${ServerVersion.value}\n", version.stdout)
    }

    /** These run the real start function, which refuses before it builds anything. */
    @Test
    fun aMissingFormsDirectoryIsAUsageError() {
        val result = program(emptyMap<String, String>()::get).record(temp.resolve("absent").toString())
        assertEquals(ExitStatus.USAGE, result.status)
        assertContains(result.stderr, "Forms directory does not exist")
    }

    @Test
    fun theConvertedDirectoryMayNotBeTheFormsDirectory() {
        val result = program(emptyMap<String, String>()::get).record("$temp", "--converted-dir", "$temp")
        assertEquals(ExitStatus.USAGE, result.status)
        assertContains(result.stderr, "must not be the forms directory")
    }
}
