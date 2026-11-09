# Configuration

This page is a summary, and `server --help` is the complete reference. The
[converters guide](converters.md) covers the converter options in detail.

## Options

```
--forms-dir <path>          Directory containing the Forms modules (or pass it positionally, or set OFMCP_FORMS_DIR);
                            without one the server starts and every tool answers with an error naming it
--convert-command <cmd>     Site-supplied converter command (with its arguments) instead of frmf2xml
--compile-command <cmd>     Separate converter command for .pll libraries
--converted-dir <path>      Directory the converted XML/.pld is written into (default: the cache)
--transport stdio|http      Transport (default: stdio)
--host <address>            HTTP bind address (default: 127.0.0.1; pass 0.0.0.0 inside a container)
--port <int>                HTTP port (default: 3000)
--allowed-host <host>       Extra HTTP Host header to accept (localhost-only by default)
--allowed-origin <url>      Extra browser Origin to accept, e.g. https://forms.example
--cache-dir <path>          Cache override (default: OS cache dir + /oracle-forms-mcp)
--annotations-dir <path>    Durable annotation store (default: <cache dir>/annotations)
--conversion-timeout <sec>  Kill a stuck conversion (default: 120)
--prefetch <pattern>        Fetch every module whose name contains <pattern>, then exit
--prefetch-all              Fetch every module in the forms directory, then exit
--prefetch-type <type>      Limit --prefetch to form, menu, library or object_library
--json                      With a prefetch: the summary on stdout as a JSON object
--force                     With a prefetch: convert modules again even when they are current
--help / --version          Print the full help page, or the version, and exit
```

`server --help` lists every option with its default and its variable, and the rules between
them. `--port`, `--allowed-host` and `--allowed-origin` need `--transport http`,
and `--prefetch` and `--prefetch-all` cannot be combined. Exit status 0 is success, 1 a prefetch
in which a module failed, 2 a command-line mistake, and 70 an unexpected failure, whose stack trace
precedes it on stderr.

Both allowlists add to localhost rather than replace it, and the two are checked separately:
allowing a host does not allow an origin. A browser-based client opened from anywhere but
localhost needs its origin passed with `--allowed-origin`, or it gets `403`. Clients that are not
browsers send no `Origin` and need only the host.

## Environment variables

The forms directory, the converter options and the HTTP bind address can also be set as
environment variables, for clients that configure a server with variables rather than arguments
(`docker run -e`, the `env` block of an MCP config, a plugin host that starts every installation
with the same command line). The command line always wins over its variable — for the forms
directory, in either spelling:

| Flag | Variable |
| --- | --- |
| `--forms-dir` (or the argument) | `OFMCP_FORMS_DIR` |
| `--convert-command` | `OFMCP_CONVERT_COMMAND` |
| `--compile-command` | `OFMCP_COMPILE_COMMAND` |
| `--converted-dir` | `OFMCP_CONVERTED_DIR` |
| `--host` | `OFMCP_HTTP_HOST` |

`--convert-command`, `--compile-command` and `--converted-dir` are also exposed as configuration
in the [Claude Code plugin](../plugins/oracle-forms/README.md#configuration), the `.mcpb` bundle
(Claude Desktop's connector settings) and the registry listing for the Docker image — so whichever
channel you install from, you can point the server at your own converter and
your own output directory without editing JSON by hand. Leaving any of them unset is always valid:
an empty value counts as "not configured".
