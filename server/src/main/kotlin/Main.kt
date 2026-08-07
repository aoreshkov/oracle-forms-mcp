package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.server.transport.runHttpServer
import app.oreshkov.oracleformsmcp.server.transport.runStdioServer
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

private const val DEFAULT_PORT = 3000

private val USAGE = """
    oracle-forms-mcp — MCP server serving the content of Oracle Forms modules in a directory.

    Usage: server [options] [<forms-dir>]

    The forms directory (required) may be given with --forms-dir or as the positional argument.
    Conversion of .fmb/.mmb/.olb/.pll binaries uses, in order: the --convert-command script if
    given, else the frmf2xml/frmcmp tools under ORACLE_HOME if that is set; otherwise
    pre-converted files (*_fmb.xml, *_mmb.xml, *_olb.xml, *.pld) are expected next to the
    modules and copied into the cache.

    Options:
      --forms-dir <path>          Directory containing the Forms modules
      --convert-command <path>    Site-supplied converter run instead of frmf2xml, as
                                  `<path> <module>` with the cwd set to the module's cache dir.
                                  It must write the same text forms there (*_fmb.xml etc., .pld
                                  for .pll). Takes precedence over ORACLE_HOME.
      --transport stdio|http      Transport to run (default: stdio)
      --port <int>                Port for the http transport (default: $DEFAULT_PORT)
      --allowed-host <host>       Extra Host header the http transport accepts; repeatable
                                  (default: localhost only, via DNS-rebinding protection)
      --allowed-origin <url>      Extra Origin the http transport accepts; repeatable
      --cache-dir <path>          Cache directory (default: OS cache dir + /oracle-forms-mcp)
      --annotations-dir <path>    Durable annotation store (default: <cache dir>/annotations)
      --conversion-timeout <sec>  Kill a conversion after this many seconds (default: 120)
      --help                      Show this help and exit

    Examples:
      server C:\forms
      server --forms-dir /srv/forms --transport http --port 3000   # http://127.0.0.1:3000/mcp
""".trimIndent()

private enum class TransportKind { STDIO, HTTP }

private data class CliOptions(
    val transport: TransportKind = TransportKind.STDIO,
    val port: Int = DEFAULT_PORT,
    val allowedHosts: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val formsDir: Path? = null,
    val cacheDir: Path? = null,
    val annotationsDir: Path? = null,
    val conversionTimeoutSeconds: Int = 120,
    val convertCommand: String? = null,
)

/** Tiny hand-rolled parser — a handful of flags don't warrant a dependency. [fail]s on anything unknown. */
private fun parseArgs(args: Array<String>): CliOptions {
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
            "--forms-dir" -> options = options.copy(formsDir = Path.of(value(arg)))
            // Blank is treated as "not configured" so a launcher template that always passes the
            // flag (an unset picker in an MCPB bundle, an empty env var in a wrapper script)
            // falls back to ORACLE_HOME instead of failing every conversion.
            "--convert-command" -> options = options.copy(convertCommand = value(arg).ifBlank { null })
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
                options = options.copy(formsDir = Path.of(arg))
            }
        }
        i++
    }
    return options
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
