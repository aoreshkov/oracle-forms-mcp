package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.server.transport.runHttpServer
import app.oreshkov.oracleformsmcp.server.transport.runStdioServer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

private const val DEFAULT_PORT = 3000

private val USAGE = """
    oracle-forms-mcp — MCP server serving the content of Oracle Forms modules in a directory.

    Usage: server [options] [<forms-dir>]

    The forms directory (required) may be given with --forms-dir or as the positional argument.
    Conversion of .fmb/.mmb/.olb/.pll binaries uses, in order: the --convert-command command if
    given, else the frmf2xml/frmcmp tools under ORACLE_HOME if that is set; otherwise
    pre-converted files (*_fmb.xml, *_mmb.xml, *_olb.xml, *.pld) are expected next to the
    modules and copied into the cache.

    Options:
      --forms-dir <path>          Directory containing the Forms modules
      --convert-command <cmd>     Site-supplied converter run instead of frmf2xml: a whole command
                                  line with its arguments, quoting any part that contains spaces
                                  ("C:\tools\my conv.bat" -xml), or a JSON array of arguments
                                  (["wine","f2x.exe","-xml"]). It is spawned directly, never
                                  through a shell. The module's absolute path replaces {} and is
                                  appended as the last argument when {} does not appear. Runs with
                                  the cwd set to the module's cache dir and must write the text
                                  forms there (*_fmb.xml etc., .pld for .pll). Takes precedence
                                  over ORACLE_HOME.
      --converted-dir <path>      Where to keep the converted XML/.pld text forms: one flat
                                  directory for all modules, each file named after its module
                                  (orders_fmb.xml, utils.pld). Created if missing.
                                  Default: inside each module's own cache entry.
      --transport stdio|http      Transport to run (default: stdio)
      --port <int>                Port for the http transport (default: $DEFAULT_PORT)
      --allowed-host <host>       Extra Host header the http transport accepts; repeatable
                                  (default: localhost only, via DNS-rebinding protection)
      --allowed-origin <url>      Extra Origin the http transport accepts; repeatable
      --cache-dir <path>          Cache directory (default: OS cache dir + /oracle-forms-mcp)
      --annotations-dir <path>    Durable annotation store (default: <cache dir>/annotations)
      --conversion-timeout <sec>  Kill a conversion after this many seconds (default: 120)
      --help                      Show this help and exit

    Environment (a flag always wins over its variable):
      ORACLE_HOME                 Oracle Forms installation providing frmf2xml/frmcmp
      $ENV_CONVERT_COMMAND       Same as --convert-command
      $ENV_CONVERTED_DIR         Same as --converted-dir

    Examples:
      server C:\forms
      server --forms-dir /srv/forms --transport http --port 3000   # http://127.0.0.1:3000/mcp
      server --forms-dir C:\forms --convert-command C:\tools\fmb2xml.bat --converted-dir C:\forms-xml
      server --forms-dir /srv/forms --convert-command "/opt/forms/convert.sh --xml {}"
""".trimIndent()

/** Environment variables mirroring the two converter flags, for launchers that can only set env. */
internal const val ENV_CONVERT_COMMAND: String = "OFMCP_CONVERT_COMMAND"
internal const val ENV_CONVERTED_DIR: String = "OFMCP_CONVERTED_DIR"

internal enum class TransportKind { STDIO, HTTP }

internal data class CliOptions(
    val transport: TransportKind = TransportKind.STDIO,
    val port: Int = DEFAULT_PORT,
    val allowedHosts: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val formsDir: Path? = null,
    val cacheDir: Path? = null,
    val annotationsDir: Path? = null,
    val conversionTimeoutSeconds: Int = 120,
    val convertCommand: String? = null,
    val convertedDir: Path? = null,
)

/**
 * A configured value, or `null` when the option was left unset.
 *
 * Blank counts as unset, and so does a leftover `${user_config.…}` template: the MCPB bundle and
 * the Claude Code plugin fill their arguments in from user configuration, and an optional entry the
 * user never filled in reaches the process either empty or with its placeholder unsubstituted.
 * Neither is a path, and failing every conversion over it would be a poor trade for an option
 * whose whole point is to be optional.
 */
internal fun configured(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() && !it.contains("\${") }

/**
 * Tiny hand-rolled parser — a handful of flags don't warrant a dependency. [fail]s on anything
 * unknown. [env] is injected so the environment fallbacks are testable.
 */
internal fun parseArgs(args: Array<String>, env: (String) -> String? = System::getenv): CliOptions {
    var options = CliOptions()
    var i = 0

    fun value(flag: String): String {
        if (i + 1 >= args.size) fail("Missing value for $flag")
        return args[++i]
    }

    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                println(USAGE)
                exitProcess(0)
            }
            "--transport" -> options = when (val t = value(arg)) {
                "stdio" -> options.copy(transport = TransportKind.STDIO)
                "http" -> options.copy(transport = TransportKind.HTTP)
                else -> fail("Unknown transport '$t' (expected stdio or http)")
            }
            "--port" -> {
                val port = value(arg).toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: fail("Invalid --port (expected 1-65535)")
                options = options.copy(port = port)
            }
            "--allowed-host" -> options =
                options.copy(allowedHosts = options.allowedHosts + value(arg))
            "--allowed-origin" -> options =
                options.copy(allowedOrigins = options.allowedOrigins + value(arg))
            "--forms-dir" -> options = options.copy(formsDir = configured(value(arg))?.let(Path::of))
            // An unset optional option (see `configured`) falls back to ORACLE_HOME / the cache
            // instead of failing every conversion.
            "--convert-command" -> options = options.copy(convertCommand = configured(value(arg)))
            "--converted-dir" -> options = options.copy(convertedDir = configured(value(arg))?.let(Path::of))
            "--cache-dir" -> options = options.copy(cacheDir = Path.of(value(arg)))
            "--annotations-dir" -> options = options.copy(annotationsDir = Path.of(value(arg)))
            "--conversion-timeout" -> {
                val seconds = value(arg).toIntOrNull()?.takeIf { it in 1..3600 }
                    ?: fail("Invalid --conversion-timeout (expected seconds, 1-3600)")
                options = options.copy(conversionTimeoutSeconds = seconds)
            }
            else -> {
                // A single positional argument is the forms directory.
                if (arg.startsWith("--")) fail("Unknown option '$arg'")
                if (options.formsDir != null) fail("Unexpected extra argument '$arg'")
                options = options.copy(formsDir = configured(arg)?.let(Path::of))
            }
        }
        i++
    }
    // Environment fallbacks for clients that can set variables but not arguments (docker run -e,
    // an MCPB `env` block). A flag always wins.
    return options.copy(
        convertCommand = options.convertCommand ?: configured(env(ENV_CONVERT_COMMAND)),
        convertedDir = options.convertedDir ?: configured(env(ENV_CONVERTED_DIR))?.let(Path::of),
    )
}

/**
 * Validates `--converted-dir`. It may not exist yet (it is created at the first conversion), but it
 * must not be a file, and it must not be the forms directory itself: converted files are named
 * exactly like the pre-converted modules the scanner reads from there, so pointing it at the forms
 * directory would have the server overwrite its own inputs.
 */
private fun convertedDir(dir: Path, formsDir: Path): Path {
    val resolved = dir.toAbsolutePath().normalize()
    if (resolved.exists() && !resolved.isDirectory()) {
        fail("--converted-dir is set to '$dir' but that is an existing file, not a directory")
    }
    if (resolved == formsDir.toAbsolutePath().normalize()) {
        fail(
            "--converted-dir must not be the forms directory ('$dir'). Converted files are named " +
                "like the pre-converted modules read from there, so the server would overwrite " +
                "its own inputs. Point it at a separate directory.",
        )
    }
    return resolved
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message\n\n$USAGE")
    exitProcess(2)
}

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val formsDir = options.formsDir
        ?: fail("Missing the forms directory (pass --forms-dir <path> or a positional argument)")
    if (!formsDir.isDirectory()) fail("Forms directory does not exist or is not a directory: $formsDir")

    val cacheDir = options.cacheDir ?: ServerConfig(formsDir).cacheDir
    val config = ServerConfig(
        formsDir = formsDir.toAbsolutePath().normalize(),
        cacheDir = cacheDir,
        annotationsDir = options.annotationsDir ?: cacheDir.resolve("annotations"),
        conversionTimeout = options.conversionTimeoutSeconds.seconds,
        convertCommand = options.convertCommand,
        convertedDir = options.convertedDir?.let { convertedDir(it, formsDir) },
    )
    runBlocking {
        McpServerFactory.create(config).use { handle ->
            when (options.transport) {
                TransportKind.STDIO -> runStdioServer(handle.server)
                TransportKind.HTTP -> runHttpServer(
                    server = handle.server,
                    port = options.port,
                    allowedHosts = options.allowedHosts,
                    allowedOrigins = options.allowedOrigins,
                )
            }
        }
    }
}
