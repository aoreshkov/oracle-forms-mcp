# Oracle Forms MCP

[![CI](https://github.com/aoreshkov/oracle-forms-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/aoreshkov/oracle-forms-mcp/actions/workflows/ci.yml)
[![CodeQL](https://github.com/aoreshkov/oracle-forms-mcp/actions/workflows/codeql.yml/badge.svg)](https://github.com/aoreshkov/oracle-forms-mcp/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/aoreshkov/oracle-forms-mcp)](https://github.com/aoreshkov/oracle-forms-mcp/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

An [MCP](https://modelcontextprotocol.io) server that serves the content of Oracle Forms modules
(`.fmb` forms, `.mmb` menus, `.pll` PL/SQL libraries, `.olb` object libraries) found in a
directory, so AI assistants can inspect blocks, items, triggers, program units, and raw object
XML without opening Forms Builder.

<!-- mcp-name: io.github.aoreshkov/oracle-forms-mcp -->

Built as a Kotlin Multiplatform core (pure `@Serializable` models and ports) with a JVM MCP
server on top: declarative tool adapters over a single `FormsService`, stdio and HTTP transports,
and a fingerprint-based on-disk cache. Oracle tool conversion feeds a streaming StAX parser that
turns Forms XML into a structured index.

## How it works

1. `list_modules` scans the configured `--forms-dir` (non-recursive) and reports each module's
   cache status: `NOT_CACHED`, `CACHED`, `STALE` (source changed on disk), or `SOURCE_MISSING`.
2. `fetch_module` produces the module's text form in the cache and indexes it:
   - **`ORACLE_HOME` set** — binaries are converted with the Oracle tools in
     `%ORACLE_HOME%\bin`: `frmf2xml` for `.fmb`/`.mmb`/`.olb` (XML), `frmcmp_batch`
     (`Module_Type=LIBRARY Script=YES`) for `.pll` (a `.pld` text dump).
   - **`ORACLE_HOME` not set** — pre-converted files are expected next to the modules
     (`orders_fmb.xml`, `main_mmb.xml`, `objects_olb.xml`, `utils.pld`) and copied into the cache.
3. A single StAX pass parses the XML into a structured index (blocks with items, triggers with
   decoded PL/SQL, program units, LOVs, record groups, windows, canvases, …). PL/SQL bodies are
   extracted to `.sql` sidecar files; every named XML element gets a line-range reference so
   `get_object_xml` can slice it back out of the converted file.
4. The other tools read the cached index. Caching is fingerprint-based (size + mtime + sha256 of
   the source file): editing a module marks it `STALE` and read tools ask for a re-fetch.

## Tools

| Tool | What it returns |
|---|---|
| `list_modules` | Every module in the forms dir with type, size, and cache status |
| `fetch_module` | Converts + indexes one module (idempotent; progress notifications) |
| `get_module_overview` | Names of every section + counts — the first call after a fetch |
| `list_blocks` | Blocks with base table, item count, trigger count |
| `get_block` | One block in full: items (type, column, canvas, prompt) + trigger names |
| `list_triggers` | Triggers with level/scope/preview; filter by block, item, or level |
| `get_trigger` | One trigger's decoded PL/SQL body |
| `list_program_units` | Procedures, functions, package specs/bodies with line counts |
| `get_program_unit` | One program unit's PL/SQL (disambiguate spec/body via `unitType`) |
| `search_source` | Line search over extracted PL/SQL (`plsql`), the raw XML (`xml`), or both |
| `get_object_xml` | The raw XML fragment of any named object — the escape hatch |

Plus a resource per cached module (`oracleforms://ORDERS.fmb/index`), a
`oracleforms://{module}/index` resource template, and an `explain_module` prompt.

## Quick start

Requires a JRE 21+. Build and install:

```
gradlew :server:installDist
```

Register with Claude Code (stdio):

```
claude mcp add oracle-forms -- server/build/install/server/bin/server --forms-dir C:\path\to\forms
```

Try it without any Oracle installation using the bundled fixtures:

```
server --forms-dir sample-forms
```

HTTP transport:

```
server --forms-dir C:\forms --transport http --port 3000   # endpoint: http://127.0.0.1:3000/mcp
```

### Docker (copy-mode only)

A container image is published to `ghcr.io/aoreshkov/oracle-forms-mcp`. Oracle's `frmf2xml` /
`frmcmp_batch` binaries are proprietary and **not** bundled, so the image works only in
**copy-mode**: the modules you mount must already have their pre-converted text form
(`*_fmb.xml`/`*_mmb.xml`/`*_olb.xml`/`*.pld`) sitting next to them. For live `.fmb`/`.pll`
conversion, run the server on a host with an Oracle Forms installation (`ORACLE_HOME` set).

```
docker run -i -v /path/to/forms:/forms ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
```

### Options

```
--forms-dir <path>          Directory containing the Forms modules (or pass it positionally)
--transport stdio|http      Transport (default: stdio)
--port <int>                HTTP port (default: 3000)
--allowed-host / --allowed-origin   Extra HTTP hosts/origins (localhost-only by default)
--cache-dir <path>          Cache override (default: OS cache dir + /oracle-forms-mcp)
--conversion-timeout <sec>  Kill a stuck conversion (default: 120)
```

## Cache

`%LOCALAPPDATA%\oracle-forms-mcp` (Windows), `~/Library/Caches/oracle-forms-mcp` (macOS),
`$XDG_CACHE_HOME/oracle-forms-mcp` (Linux). One directory per module:

```
ORDERS.fmb/
  converted/orders_fmb.xml      converted (or copied) text form
  plsql/triggers/*.sql          decoded trigger bodies
  plsql/program-units/*.sql     decoded program units
  index.json                    the structured index
```

Safe to delete at any time; modules are simply re-fetched.

## Notes on the Oracle tools

- `frmf2xml` writes its output into the process working directory; the server runs it with the
  module's cache dir as cwd and passes `OVERWRITE=YES USE_PROPERTY_IDS=NO`.
- `frmcmp_batch` is preferred over `frmcmp` (headless); the server passes
  `Script=YES Batch=YES Logon=NO` and augments `FORMS_PATH` with the forms dir so attached
  libraries resolve.
- Forms tools have unreliable exit codes — success is judged by the output file existing,
  being non-empty, and being newer than the invocation; failures surface the tool's output tail.
- `.pld` files may be written in the client NLS charset; the parser reads UTF-8 with a
  windows-1252 fallback (set `NLS_LANG` accordingly if you see mojibake).

## Development

```
gradlew build          # compile + all tests (no Oracle installation needed)
gradlew apiDump        # refresh the binary-compatibility dump after public API changes
gradlew :server:run --args="--forms-dir sample-forms"
```

Converter behavior is tested against a fake `ORACLE_HOME` (stub scripts); the full copy-mode
pipeline is covered end-to-end by `FormsServiceIntegrationTest` against the bundled fixtures.
