package app.oreshkov.oracleformsmcp.server

import app.oreshkov.clikit.Argument
import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Constraint
import app.oreshkov.clikit.ConversionResult
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.EnvironmentVariable
import app.oreshkov.clikit.Example
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.ExperimentalCliKitApi
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.OptionValues
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.ValueConverter
import app.oreshkov.clikit.field
import app.oreshkov.clikit.onInterrupt
import app.oreshkov.clikit.path
import app.oreshkov.clikit.runAndExit
import app.oreshkov.clikit.unsetWhen
import app.oreshkov.clikit.writeArray
import app.oreshkov.clikit.writeObject
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.server.transport.DEFAULT_HTTP_HOST
import app.oreshkov.oracleformsmcp.server.transport.allowedHostProblem
import app.oreshkov.oracleformsmcp.server.transport.allowedOriginProblem
import app.oreshkov.oracleformsmcp.server.transport.bindAddress
import app.oreshkov.oracleformsmcp.server.transport.runHttpServer
import app.oreshkov.oracleformsmcp.server.transport.runStdioServer
import co.touchlab.kermit.Logger
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_PORT = 3000
private const val DEFAULT_CONVERSION_TIMEOUT_SECONDS = 120

/** Environment variables mirroring the converter flags, for launchers that can only set env. */
internal const val ENV_CONVERT_COMMAND: String = "OFMCP_CONVERT_COMMAND"
internal const val ENV_COMPILE_COMMAND: String = "OFMCP_COMPILE_COMMAND"
internal const val ENV_CONVERTED_DIR: String = "OFMCP_CONVERTED_DIR"

/** The http bind address, for container runtimes that pass configuration as variables. */
internal const val ENV_HTTP_HOST: String = "OFMCP_HTTP_HOST"

/** Read by the handler itself rather than through an option, so it is declared for help to list. */
internal const val ENV_ORACLE_HOME: String = "ORACLE_HOME"

/**
 * How long a stdio server stopped by a signal gets to finish closing: [McpServerHandle.close] only
 * logs a line and cancels a scope, so this is a ceiling for a wedged close, not a grace period.
 */
private val STDIO_STOP_TIMEOUT = 5.seconds

internal enum class TransportKind { STDIO, HTTP }

internal data class CliOptions(
    val formsDir: Path,
    val transport: TransportKind = TransportKind.STDIO,
    /** The http bind address: the `--host` flag, else [ENV_HTTP_HOST], else loopback. */
    val host: String = DEFAULT_HTTP_HOST,
    val port: Int = DEFAULT_PORT,
    val allowedHosts: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val cacheDir: Path? = null,
    val annotationsDir: Path? = null,
    val conversionTimeoutSeconds: Int = DEFAULT_CONVERSION_TIMEOUT_SECONDS,
    val convertCommand: String? = null,
    val compileCommand: String? = null,
    val convertedDir: Path? = null,
    val oracleHome: String? = null,
    /**
     * `--prefetch`/`--prefetch-all`/`--prefetch-type`: run the warm-up instead of a transport.
     * [Prefetch.pattern] is `null` for every module.
     */
    val prefetch: Prefetch? = null,
)

/** A warm-up run; [json] writes its summary as a JSON object instead of a sentence. */
internal data class Prefetch(val pattern: String? = null, val type: ModuleType? = null, val json: Boolean = false)

/**
 * This converter, except that a value a launcher left unfilled counts as not given, and every other
 * value is trimmed.
 *
 * Blank counts as unset, and so does a leftover `${user_config.…}` template: the MCPB bundle and
 * the Claude Code plugin fill their arguments in from user configuration, and an optional entry the
 * user never filled in reaches the process either empty or with its placeholder unsubstituted.
 * Neither is a path, and failing every conversion over it would be a poor trade for an option
 * whose whole point is to be optional. An unset flag also lets its environment variable apply.
 *
 * Only a value that is *wholly* such a template is dropped: a real one can contain `${` (a shell
 * wrapper around a converter, `sh -c 'conv "${1}"' _ {}`), and discarding it would fall back to
 * copy-mode without a word. A dropped template is reported as a warning naming the option or
 * variable; a blank one is not, since blank is how most launchers say "unset" in the first place.
 */
internal fun <T> ValueConverter<T>.configured(): ValueConverter<T> {
    val inner = this
    return ValueConverter { raw -> inner.convert(raw.trim()) }.unsetWhen(
        { it.isBlank() || UNFILLED_TEMPLATE.matches(it.trim()) },
        notice = { if (it.isBlank()) null else unfilledNotice(it) },
    )
}

private val UNFILLED_TEMPLATE = Regex("""\$\{user_config\.[^}]*}""")

private fun unfilledNotice(raw: String): String =
    "ignoring '${raw.trim()}': an unfilled user-configuration template, so it is unset"

/**
 * The bind address. A blank one on the command line is a mistake rather than "unset"; only a
 * launcher's unfilled template is unset, so `${user_config.http_host}` falls back to loopback.
 */
private val hostConverter: ValueConverter<String> = ValueConverter { raw ->
    raw.trim().takeIf { it.isNotEmpty() }?.let { ConversionResult.Ok(it) }
        ?: ConversionResult.Invalid("expected an address, e.g. 127.0.0.1 or 0.0.0.0")
}.unsetWhen({ UNFILLED_TEMPLATE.matches(it.trim()) }, notice = ::unfilledNotice)

/**
 * A Host or Origin allowlist entry, trimmed and checked here: the SDK checks its entries only when
 * the engine starts, and stops the server on a bad one with an exception that names no option.
 */
private fun allowListEntry(problem: (String) -> String?): ValueConverter<String> = ValueConverter { raw ->
    val entry = raw.trim()
    problem(entry)?.let { ConversionResult.Invalid(it) } ?: ConversionResult.Ok(entry)
}

/** The module types, spelled as `ModuleType` names them (`object_library`) and matched in any case. */
private val moduleTypeConverter: ValueConverter<ModuleType> =
    Converters.enum(ModuleType.entries) { it.name.lowercase() }.let { exact ->
        object : ValueConverter<ModuleType> {
            override fun convert(raw: String) = exact.convert(raw.lowercase())
            override val choices: List<String> get() = exact.choices
        }
    }

private val formsDirOption = Option.Single(
    "--forms-dir",
    Converters.path.configured(),
    help = "Directory containing the Forms modules",
    valueName = "path",
)
private val formsDirArgument = Argument.Single(
    "forms-dir",
    Converters.path.configured(),
    help = "The same as --forms-dir",
    required = false,
)
private val convertCommandOption = Option.Single(
    "--convert-command",
    Converters.string.configured(),
    help = "Site-supplied converter run instead of frmf2xml: a whole command line with its arguments, quoting " +
        "any part that contains spaces (\"C:\\tools\\my conv.bat\" -xml), or a JSON array of arguments " +
        "([\"wine\",\"f2x.exe\",\"-xml\"]). It is spawned directly, never through a shell.\n\n" +
        "The module's absolute path replaces {} and is appended as the last argument when {} does not appear; " +
        "the output file's absolute path replaces {out} (never appended). Runs with the cwd set to the converted " +
        "directory (--converted-dir, else the module's cache dir) and must write the text forms there or to {out} " +
        "(*_fmb.xml etc., .pld for .pll). Takes precedence over ORACLE_HOME.",
    valueName = "cmd",
    env = ENV_CONVERT_COMMAND,
)
private val compileCommandOption = Option.Single(
    "--compile-command",
    Converters.string.configured(),
    help = "Converter for .pll libraries only, run instead of whatever would convert them otherwise; same " +
        "syntax and placeholders as --convert-command. frmcmp writes its .pld next to the module unless told " +
        "otherwise, so pass it Output_File={out}.",
    valueName = "cmd",
    env = ENV_COMPILE_COMMAND,
)
private val convertedDirOption = Option.Single(
    "--converted-dir",
    Converters.path.configured(),
    help = "Directory the converted XML/.pld text forms are written into: one flat directory for all modules, " +
        "each file named after its module (orders_fmb.xml, utils.pld). The converter runs with this as its " +
        "working directory. Created if missing.",
    valueName = "path",
    defaultDisplay = "inside each module's own cache entry",
    env = ENV_CONVERTED_DIR,
)
private val transportOption = Option.Single(
    "--transport",
    Converters.enum(TransportKind.entries),
    help = "Transport to run",
    valueName = "name",
    defaultDisplay = "stdio",
)
private val hostOption = Option.Single(
    "--host",
    hostConverter,
    help = "Address the http transport listens on, this machine only by default. The transport has no " +
        "authentication, so anything else lets every client that can reach the port call every tool; inside a " +
        "container use 0.0.0.0 and publish the port on the host's loopback (-p 127.0.0.1:3000:3000).",
    valueName = "address",
    defaultDisplay = DEFAULT_HTTP_HOST,
    env = ENV_HTTP_HOST,
)
private val portOption = Option.Single(
    "--port",
    Converters.intRange(1..65535),
    help = "Port for the http transport",
    valueName = "int",
    defaultDisplay = "$DEFAULT_PORT",
)
private val allowedHostOption = Option.Repeated(
    "--allowed-host",
    allowListEntry(::allowedHostProblem),
    help = "Extra Host header the http transport accepts (forms.example, an IPv6 address in brackets); " +
        "repeatable. Only localhost by default, via DNS-rebinding protection. A browser's Origin is still " +
        "checked against localhost; allow another with --allowed-origin.",
    valueName = "host",
)
private val allowedOriginOption = Option.Repeated(
    "--allowed-origin",
    allowListEntry(::allowedOriginProblem),
    help = "Extra Origin the http transport accepts, written as a browser sends it (https://forms.example); " +
        "repeatable. Compared by host only, so the scheme and port do not narrow it.",
    valueName = "url",
)
private val cacheDirOption = Option.Single(
    "--cache-dir",
    Converters.path,
    help = "Cache directory",
    valueName = "path",
    defaultDisplay = "OS cache dir + /oracle-forms-mcp",
)
private val annotationsDirOption = Option.Single(
    "--annotations-dir",
    Converters.path,
    help = "Durable annotation store",
    valueName = "path",
    defaultDisplay = "<cache dir>/annotations",
)
private val conversionTimeoutOption = Option.Single(
    "--conversion-timeout",
    Converters.intRange(1..3600),
    help = "Kill a conversion after this many seconds",
    valueName = "sec",
    defaultDisplay = "$DEFAULT_CONVERSION_TIMEOUT_SECONDS",
)
private val prefetchOption = Option.Single(
    "--prefetch",
    Converters.string,
    help = "Instead of serving, fetch (convert and index) every module whose name contains <pattern> " +
        "(case-insensitive), one at a time, then exit, so a session's search_modules covers them. " +
        "Already-current modules are skipped cheaply, so a rerun resumes. Exits 1 when any module failed.",
    valueName = "pattern",
)
private val prefetchAllOption = Option.Flag(
    "--prefetch-all",
    help = "The same for every module in the forms directory",
)
private val prefetchTypeOption = Option.Single(
    "--prefetch-type",
    moduleTypeConverter,
    help = "Only prefetch this module type; given alone, every module of that type",
    valueName = "type",
)
private val jsonOption = Option.Flag(
    "--json",
    help = "With a prefetch, write its summary on stdout as JSON (modules, fetched, current, failed)",
)

@OptIn(ExperimentalCliKitApi::class)
private val root = CommandSpec(
    "server",
    help = "oracle-forms-mcp — MCP server serving the content of Oracle Forms modules in a directory.\n\n" +
        "The forms directory (required) may be given with --forms-dir or as the positional argument. " +
        "Conversion of .fmb/.mmb/.olb/.pll binaries uses, in order: the --convert-command command if given, " +
        "else the frmf2xml/frmcmp tools under ORACLE_HOME if that is set; otherwise pre-converted files " +
        "(*_fmb.xml, *_mmb.xml, *_olb.xml, *.pld) are expected next to the modules and copied into the cache. " +
        "A .pll library goes to the --compile-command command first when one is given; the other module types " +
        "are unaffected by it.",
    options = listOf(
        formsDirOption,
        convertCommandOption,
        compileCommandOption,
        convertedDirOption,
        transportOption,
        hostOption,
        portOption,
        allowedHostOption,
        allowedOriginOption,
        cacheDirOption,
        annotationsDirOption,
        conversionTimeoutOption,
        prefetchOption,
        prefetchAllOption,
        prefetchTypeOption,
        jsonOption,
    ),
    arguments = listOf(formsDirArgument),
    examples = listOf(
        Example("server C:\\forms", "Serve a directory over stdio"),
        Example(
            "server --forms-dir /srv/forms --transport http --port 3000",
            "http://127.0.0.1:3000/mcp, this machine only",
        ),
        Example(
            "server --forms-dir C:\\forms --convert-command C:\\tools\\fmb2xml.bat --converted-dir C:\\forms-xml",
            "Convert with a site-supplied tool, into one directory",
        ),
        Example(
            "server --forms-dir /srv/forms --convert-command \"/opt/forms/convert.sh --xml {}\"",
            "A converter command line with its own arguments",
        ),
        Example("server --forms-dir /srv/forms --prefetch-all", "Warm the whole cache, then exit"),
        Example(
            "server --forms-dir /srv/forms --converted-dir /srv/forms-xml --compile-command " +
                "\"frmcmp_batch Module={} Module_Type=LIBRARY Script=YES Batch=YES Logon=NO Output_File={out}\"",
            "Libraries through frmcmp, everything else through ORACLE_HOME or the cache",
        ),
    ),
    constraints = listOf(
        Constraint.ExactlyOne(formsDirOption, formsDirArgument),
        Constraint.Exclusive(prefetchOption, prefetchAllOption),
        // An http setting silently ignored under stdio is a server not listening where it was asked to.
        // --host is left out: it reads OFMCP_HTTP_HOST, and a container that exports it may still run stdio.
        Constraint.RequiresValue(portOption, transportOption, "http"),
        Constraint.RequiresValue(allowedHostOption, transportOption, "http"),
        Constraint.RequiresValue(allowedOriginOption, transportOption, "http"),
    ),
    environment = listOf(
        EnvironmentVariable(ENV_ORACLE_HOME, "Oracle Forms installation providing frmf2xml/frmcmp"),
    ),
)

/** The command tree, for the help golden file. */
internal val commandTree: CommandSpec get() = root

/**
 * What the command line and the environment say to run. It touches nothing: whether the paths
 * exist and the host resolves is [startServer]'s to check, so the mapping is testable with paths
 * that are not there.
 */
internal fun cliOptionsOf(values: OptionValues, environment: (String) -> String?): CliOptions {
    val prefetchType = values[prefetchTypeOption]
    val json = values[jsonOption]
    // `*` is accepted as "all" where a shell passes it through, but it is not the spelling to
    // document: the JVM launcher on Windows expands a bare `*` into file names.
    val pattern = values[prefetchOption]
    val prefetch = when {
        values[prefetchAllOption] -> Prefetch(type = prefetchType, json = json)
        pattern != null -> Prefetch(pattern.trim().takeUnless { it.isEmpty() || it == "*" }, prefetchType, json)
        prefetchType != null -> Prefetch(type = prefetchType, json = json)
        // Serving, stdout is the MCP protocol channel; a JSON summary has nowhere to go.
        json -> usageError("--json applies only to a prefetch (--prefetch, --prefetch-all or --prefetch-type)")
        else -> null
    }
    return CliOptions(
        // The ExactlyOne constraint has already refused a command line with neither.
        formsDir = checkNotNull(values[formsDirOption] ?: values[formsDirArgument]),
        transport = values[transportOption] ?: TransportKind.STDIO,
        host = values[hostOption] ?: DEFAULT_HTTP_HOST,
        port = values[portOption] ?: DEFAULT_PORT,
        allowedHosts = values[allowedHostOption],
        allowedOrigins = values[allowedOriginOption],
        cacheDir = values[cacheDirOption],
        annotationsDir = values[annotationsDirOption],
        conversionTimeoutSeconds = values[conversionTimeoutOption] ?: DEFAULT_CONVERSION_TIMEOUT_SECONDS,
        convertCommand = values[convertCommandOption],
        compileCommand = values[compileCommandOption],
        convertedDir = values[convertedDirOption],
        oracleHome = environment(ENV_ORACLE_HOME),
        prefetch = prefetch,
    )
}

/**
 * The program: help, `--version` and usage errors are answered by cli-kit, and every other command
 * line goes to [start]. [environment] and [start] are injected so that a test runs the whole parse
 * without serving anything.
 */
internal fun program(
    environment: (String) -> String? = System::getenv,
    start: (CliOptions, CommandContext) -> Int = ::startServer,
): Program = Program(
    root,
    "oracle-forms-mcp ${ServerVersion.value}",
    mapOf(root to CommandHandler { context -> start(cliOptionsOf(context.values, context.environment), context) }),
    environment = environment,
)

/**
 * Validates `--converted-dir`. It may not exist yet (it is created at the first conversion), but it
 * must not be a file, and it must not be the forms directory itself: conversion output is *written*
 * there under names identical to the pre-converted modules the scanner reads from the forms
 * directory, so pointing it at the forms directory would have the server overwrite its own inputs.
 *
 * It is refused however it is reached, by any spelling of its path or through a link to it. That is
 * equality, not containment: a subdirectory of the forms directory is fine, because the scanner
 * reads the forms directory non-recursively.
 *
 * Throws rather than failing the command, so the rule can be tested; [startServer] turns the throw
 * into a usage error.
 *
 * @throws IllegalArgumentException naming what is wrong with [dir].
 */
internal fun validateConvertedDir(dir: Path, formsDir: Path): Path {
    val resolved = dir.toAbsolutePath().normalize()
    require(!resolved.exists() || resolved.isDirectory()) {
        "--converted-dir is set to '$dir' but that is an existing file, not a directory"
    }
    // Lexically first, then as a file: a symlink, junction or bind mount is a spelling no
    // normalising reveals, and the output would still land among the forms directory's inputs.
    // A comparison that cannot be made is a refusal, not a pass: that is the case it exists for.
    val forms = formsDir.toAbsolutePath().normalize()
    val sameDirectory = resolved == forms || (resolved.exists() && forms.exists() && sameFile(dir, resolved, forms))
    require(!sameDirectory) {
        "--converted-dir must not be the forms directory ('$dir'). Conversion output is " +
            "written there, named like the pre-converted modules read from the forms " +
            "directory, so the server would overwrite its own inputs. Point it at a separate " +
            "directory (a subdirectory of the forms directory is fine)."
    }
    return resolved
}

private fun sameFile(dir: Path, resolved: Path, forms: Path): Boolean = try {
    Files.isSameFile(resolved, forms)
} catch (e: IOException) {
    throw IllegalArgumentException(
        "--converted-dir '$dir' could not be compared with the forms directory ($e), so it " +
            "cannot be shown not to be the same directory. Point it at a directory this process " +
            "can read.",
        e,
    )
}

/** A usage error found after parsing, in a value only the file system or the resolver can refuse. */
private fun usageError(message: String): Nothing = throw CommandFailure(ExitStatus.USAGE, message)

/**
 * Checks what parsing could not, then runs the prefetch or the transport. Anything unexpected is
 * logged with its stack trace before it becomes a failure: an MCP client launches the server with
 * fixed arguments and keeps its stderr as the log, so a trace helps only if it is already there.
 */
private fun startServer(options: CliOptions, context: CommandContext): Int {
    // Before anything can log: Kermit's own default writer is stdout, the stdio protocol channel.
    routeKermitToSlf4j()
    val formsDir = options.formsDir
    if (!formsDir.isDirectory()) usageError("Forms directory does not exist or is not a directory: $formsDir")

    // Resolved before anything starts, so a --host that names nothing is a usage error, not a
    // stack trace from inside the engine.
    val httpAddress = if (options.transport == TransportKind.HTTP) {
        try {
            bindAddress(options.host)
        } catch (e: IllegalArgumentException) {
            usageError(e.message ?: "Invalid --host")
        }
    } else {
        null
    }
    val convertedDir = options.convertedDir?.let {
        try {
            validateConvertedDir(it, formsDir)
        } catch (e: IllegalArgumentException) {
            usageError(e.message ?: "Invalid --converted-dir")
        }
    }

    val cacheDir = options.cacheDir ?: ServerConfig(formsDir).cacheDir
    val config = ServerConfig(
        formsDir = formsDir.toAbsolutePath().normalize(),
        cacheDir = cacheDir,
        annotationsDir = options.annotationsDir ?: cacheDir.resolve("annotations"),
        oracleHome = options.oracleHome,
        conversionTimeout = options.conversionTimeoutSeconds.seconds,
        convertCommand = options.convertCommand,
        compileCommand = options.compileCommand,
        convertedDir = convertedDir,
    )
    return try {
        when {
            options.prefetch != null -> runPrefetch(config, options.prefetch, context)
            httpAddress != null -> serveHttp(config, options, httpAddress)
            else -> serveStdio(config, context)
        }
    } catch (failure: CommandFailure) {
        throw failure
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        Logger.withTag("Main").e(error) { "The server stopped on an unexpected error" }
        throw CommandFailure(
            ExitStatus.SOFTWARE,
            "the server stopped on an unexpected ${error::class.simpleName}: ${error.message} (stack trace above)",
        )
    }
}

/**
 * Serves over stdio until the client closes stdin, which is how MCP ends a stdio session. A signal
 * cancels the server instead, and the handle is closed on the way out either way, so the session's
 * `ToolErrorTally` summary is logged even when the process is stopped rather than hung up on.
 */
private fun serveStdio(config: ServerConfig, context: CommandContext): Int = runBlocking {
    val server = launch { McpServerFactory.create(config).use { runStdioServer(it.server) } }
    context.onInterrupt {
        server.cancel()
        runBlocking { withTimeoutOrNull(STDIO_STOP_TIMEOUT) { server.join() } }
    }.use { server.join() }
    ExitStatus.OK
}

/** Serves over http until stopped. Ktor installs its own shutdown hook, which drains in-flight calls. */
private fun serveHttp(config: ServerConfig, options: CliOptions, address: InetAddress): Int {
    runBlocking {
        McpServerFactory.create(config).use { handle ->
            runHttpServer(
                server = handle.server,
                address = address,
                port = options.port,
                allowedHosts = options.allowedHosts,
                allowedOrigins = options.allowedOrigins,
            )
        }
    }
    return ExitStatus.OK
}

/**
 * The `--prefetch` run: every matching module fetched in turn, with a progress line at a terminal
 * and one line per module on stderr otherwise. Each failure is reported as it happens and repeated
 * in the summary on stdout, since those are what the operator acts on; `--json` makes the summary
 * a JSON object, for CI. No transport is started, so stdout carries only the summary.
 */
private fun runPrefetch(config: ServerConfig, prefetch: Prefetch, context: CommandContext): Int {
    val output = context.output
    val outcomes = runBlocking {
        McpServerFactory.create(config).use { handle ->
            val progress = output.progress("Prefetching")
            try {
                handle.service.prefetch(pattern = prefetch.pattern, type = prefetch.type) { done, total, outcome ->
                    val error = outcome.error
                    if (error != null) {
                        progress.finish()
                        output.error("${outcome.module}: ${error.lineSequence().first()}")
                    } else if (!context.io.interactive) {
                        val status = if (outcome.summary?.fromCache == true) "current" else "fetched"
                        output.notice("[$done/$total] ${outcome.module}: $status")
                    }
                    progress.update(done.toLong(), total.toLong(), outcome.module.toString())
                }
            } finally {
                progress.finish()
            }
        }
    }
    val failed = outcomes.filter { it.error != null }
    val fetched = outcomes.count { it.summary?.fromCache == false }
    val current = outcomes.size - fetched - failed.size
    val summary = buildString {
        if (prefetch.json) {
            JsonWriter(this).writeObject {
                field("modules", outcomes.size)
                field("fetched", fetched)
                field("current", current)
                name("failed").writeArray {
                    failed.forEach { writeObject { field("module", "${it.module}").field("error", it.error) } }
                }
            }
        } else {
            append("Prefetched ${outcomes.size} module(s): $fetched fetched, $current already current, ${failed.size} failed.")
            failed.forEach { append("\n  ${it.module}: ${it.error}") }
        }
    }
    output.text(summary)
    return if (failed.isEmpty()) ExitStatus.OK else ExitStatus.FAILURE
}

fun main(args: Array<String>): Unit = program().runAndExit(args)
