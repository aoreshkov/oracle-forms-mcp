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

## Why

Oracle Forms applications from the 1990s–2000s are still running critical business processes, but
their logic is locked inside binary `.fmb`/`.pll` modules that only Forms Builder can open. That
makes them opaque to modern AI tooling and painful to review, document, or migrate.

Oracle Forms MCP turns those modules into structured, queryable content so an AI assistant can:

- **Understand a legacy app** — enumerate blocks, items, triggers, and program units without a Forms IDE.
- **Review & document PL/SQL** — pull decoded trigger and program-unit bodies straight into the model's context.
- **Assist modernization** — feed decades-old business logic to an assistant for migration to APEX,
  Java, or a rewrite, and search across every module's source.
- **Capture & retain knowledge** — let the assistant record notes, tags, and cross-references on
  individual elements that persist across sessions and re-indexing, building up a durable map of a
  form no one fully remembers.

It is aimed at developers and teams doing Oracle Forms **modernization, reverse engineering, code
review, and documentation** — anyone who needs to read Forms logic faster than opening it by hand.

## See it work

<!-- TODO: record a short asciinema/GIF of a Claude session and embed it here:
     ![demo](docs/demo.gif)   (drop the file at docs/demo.gif or an assets/ path) -->

A typical session against the bundled `sample-forms` directory:

```text
You:  What does ORDERS.fmb do?
AI →  list_modules                 → ORDERS.fmb (NOT_CACHED), MAINMENU.mmb, UTILS.pll …
AI →  fetch_module ORDERS.fmb      → converted + indexed (2 blocks, 3 triggers, 3 program units)
AI →  get_module_overview ORDERS   → blocks, triggers, LOVs, record groups, windows, canvases …
You:  Show me the validation logic on the ORDERS block.
AI →  list_triggers block=ORDERS   → WHEN-VALIDATE-ITEM (on ORDER_ID), WHEN-VALIDATE-RECORD
AI →  get_trigger ORDERS WHEN-VALIDATE-ITEM  → the decoded PL/SQL body
You:  Where else is the CALC_TOTAL procedure called?
AI →  search_source "calc_total" scope=plsql  → hits across triggers and program units
You:  That validation is the legacy pre-2010 path — note it so we remember.
AI →  annotate_element ORDERS trigger WHEN-VALIDATE-ITEM kind=note "Legacy pre-2010 validation path" → saved
      (next session)  get_trigger ORDERS WHEN-VALIDATE-ITEM → body + the stored note inline
```

## How it works

1. `list_modules` scans the configured `--forms-dir` (non-recursive) and reports each module's
   cache status: `NOT_CACHED`, `CACHED`, `STALE` (source changed on disk), or `SOURCE_MISSING`.
2. `fetch_module` produces the module's text form in the cache and indexes it:
   - **`ORACLE_HOME` set** — binaries are converted with the Oracle tools in
     `%ORACLE_HOME%\bin`: `frmf2xml` for `.fmb`/`.mmb`/`.olb` (XML), `frmcmp_batch`
     (`Module_Type=LIBRARY Script=YES`) for `.pll` (a `.pld` text dump).
   - **`ORACLE_HOME` not set** — pre-converted files are expected next to the modules
     (`orders_fmb.xml`, `mainmenu_mmb.xml`, `objects_olb.xml`, `utils.pld`) and copied into the cache.
3. A single StAX pass parses the XML into a structured index (blocks with items, triggers with
   decoded PL/SQL, program units, LOVs, record groups, windows, canvases, …). PL/SQL bodies are
   extracted to `.sql` sidecar files; every named XML element gets a line-range reference so
   `get_object_xml` can slice it back out of the converted file.
4. The other tools read the cached index. Caching is fingerprint-based (size + mtime + sha256 of
   the source file): editing a module marks it `STALE` and read tools ask for a re-fetch.
5. `annotate_element` and `relate_elements` let the assistant write durable meta-information back
   about individual elements (notes, tags, summaries, classifications, cross-references). This is
   kept in a **separate** store — not the derived index — so it survives re-fetching, and the read
   tools surface it inline. An annotation made before a source change is flagged, never dropped.

## Tools

| Tool | What it returns |
|---|---|
| `list_modules` | Every module in the forms dir with type, size, and cache status |
| `fetch_module` | Converts + indexes one module (idempotent; progress notifications) |
| `get_module_overview` | Names of every section + counts — the first call after a fetch |
| `list_blocks` | Blocks with base table, item count, trigger count |
| `get_block` | One block in full: items (type, column, canvas, prompt) + trigger names |
| `list_triggers` | Triggers with level/scope; filter by block, item, or level (`verbosity=detailed` adds a PL/SQL preview) |
| `get_trigger` | One trigger's decoded PL/SQL body |
| `list_program_units` | Procedures, functions, package specs/bodies with line counts |
| `get_program_unit` | One program unit's PL/SQL (disambiguate spec/body via `unitType`) |
| `search_source` | Line search over extracted PL/SQL (`plsql`), the raw XML (`xml`), or both; paginated via `offset`/`nextOffset` |
| `get_object_xml` | The raw XML fragment of any named object — the escape hatch |

### Annotations

Meta-information the assistant records back **about** an element rather than reads *from* it —
semantic notes, tags, classifications, and cross-reference relations. It is persisted in a durable
store, kept separate from the derived index (not in the protocol `_meta` field), so it survives
`fetch_module` re-indexing and is served back to later sessions. Each entry carries its author and
is flagged `staleAgainstSource` when it predates the module's current source, so a note is never
silently dropped. The read tools above (`get_module_overview`, `get_block`, `get_trigger`,
`get_program_unit`, `get_object_xml`) surface an element's annotations inline.

| Tool | What it does |
|---|---|
| `annotate_element` | Store a note / summary / tag / classification about one element |
| `relate_elements` | Record a directed cross-reference between two elements (e.g. a trigger `calls` a program unit) |
| `get_element_annotations` | The notes and relations stored about one element |
| `search_annotations` | Search a module's stored notes/tags/relations by text, kind, or tag |
| `remove_annotation` | Delete a stored annotation or relation by id |

Plus a resource per cached module (`oracleforms://ORDERS.fmb/index`), the
`oracleforms://{module}/index` and `oracleforms://{module}/annotations` resource templates, and an
`explain_module` prompt.

## Quick start

Requires a JRE 21+. Build and install:

```
gradlew :server:installDist
```

Register with Claude Code (stdio):

```
claude mcp add oracle-forms -- server/build/install/server/bin/server --forms-dir C:\path\to\forms
```

<details>
<summary><b>Other MCP clients</b> (Claude Desktop, Cursor, VS Code) — via the Docker image</summary>

The published image runs the server over stdio with no local build. Point the volume mount at
your forms directory (copy-mode: the pre-converted `*_fmb.xml`/`*.pld` files must sit next to the
modules — see [Docker](#docker-copy-mode-only)).

**Claude Desktop** (`claude_desktop_config.json`) and **Cursor** (`~/.cursor/mcp.json`) use the same shape:

```json
{
  "mcpServers": {
    "oracle-forms": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-v", "ofmcp-cache:/home/mcp/.cache", "-v", "/path/to/forms:/forms",
               "ghcr.io/aoreshkov/oracle-forms-mcp", "--forms-dir", "/forms"]
    }
  }
}
```

**VS Code** (`.vscode/mcp.json`) uses a `servers` key instead:

```json
{
  "servers": {
    "oracle-forms": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-v", "ofmcp-cache:/home/mcp/.cache", "-v", "${workspaceFolder}/forms:/forms",
               "ghcr.io/aoreshkov/oracle-forms-mcp", "--forms-dir", "/forms"]
    }
  }
}
```

The `ofmcp-cache` named volume keeps the parsed-module cache and — more importantly — the durable
annotation store across container restarts; `--rm` removes the container but not a named volume. Drop
it and the notes/tags/relations the assistant records won't survive the next run. See
[Docker](#docker-copy-mode-only) for the bind-mount variant and its one-time `chown`.

Prefer the native launcher? Swap `"command": "docker", "args": [...]` for
`"command": "/abs/path/to/server/build/install/server/bin/server", "args": ["--forms-dir", "/abs/path/to/forms"]`.

</details>

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

**Persisting the cache and annotations.** Without a volume, the cache and the durable annotation
store live in the container's writable layer and are discarded when it exits. Mount a volume at
`/home/mcp/.cache` to keep them across runs:

```
docker run -i -v ofmcp-cache:/home/mcp/.cache -v /path/to/forms:/forms \
  ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
```

A named or anonymous volume inherits the image's non-root ownership (uid 10001) and just works.
A host **bind mount** does not — Docker never chowns the target — so run `chown 10001 /host/cache`
once on the host first, or redirect the writes with `--cache-dir` / `--annotations-dir` onto a path
the container user can write.

### Options

```
--forms-dir <path>          Directory containing the Forms modules (or pass it positionally)
--transport stdio|http      Transport (default: stdio)
--port <int>                HTTP port (default: 3000)
--allowed-host / --allowed-origin   Extra HTTP hosts/origins (localhost-only by default)
--cache-dir <path>          Cache override (default: OS cache dir + /oracle-forms-mcp)
--annotations-dir <path>    Durable annotation store (default: <cache dir>/annotations)
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
  plsql/menu-items/*.sql        menu-item command bodies (menu modules)
  index.json                    the structured index
```

Safe to delete at any time; modules are simply re-fetched.

Annotations are **not** part of this derived cache. They live in a separate `annotations/` store
(one `NAME.ext.json` per module, defaulting to `<cache dir>/annotations`, overridable with
`--annotations-dir`), so deleting a module's cache entry — or re-fetching it — leaves the notes,
tags, and relations you recorded intact.

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
gradlew updateKotlinAbi   # refresh the ABI dump (core/api/*.api) after public API changes
gradlew :server:run --args="--forms-dir sample-forms"
```

Converter behavior is tested against a fake `ORACLE_HOME` (stub scripts); the full copy-mode
pipeline is covered end-to-end by `FormsServiceIntegrationTest` against the bundled fixtures.
