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

A typical session against the bundled `sample-forms` directory:

```text
You:  What does ORDERS.fmb do?
AI →  list_modules                 → ORDERS.fmb (NOT_CACHED), MAINMENU.mmb, UTILS.pll …
AI →  fetch_module ORDERS.fmb      → converted + indexed (3 blocks, 3 triggers, 3 program units)
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

`list_modules` scans the directory you name and reports each module's cache status. `fetch_module`
produces the module's text form, with the Oracle tools when `ORACLE_HOME` is set and from
pre-converted files next to the modules when it is not, and parses it into a structured index. The
other tools read that index. A `STALE` module is one whose source changed or whose index an older
build wrote, and fetching it again clears that. Notes recorded with `annotate_element` and
`relate_elements` live in a separate store, so re-fetching keeps them.

[How it works](guide/how-it-works.md) has the full sequence and the cache layout.

## Tools

| Tool | What it does |
|---|---|
| `list_modules` | Modules in the forms dir with type, size, and cache status; filter by `pattern`/`regex`, `type`, `status` and page via `limit`/`cursor` |
| `fetch_module` | Converts + indexes one module (idempotent; `force=true` converts a current one again; progress notifications) |
| `get_module_overview` | Names of every section + counts — the first call after a fetch (`verbosity=detailed` adds window and canvas objects) |
| `list_blocks` | Blocks with base table, item count, trigger count |
| `get_block` | One block: items with type, property class, prompt and trigger names, and its master-detail relations (`verbosity=detailed` adds data type, column, canvas, size, visible/required/LOV and the DML properties resolved through each item's property class; `columns=true` adds the base table's columns and the ones no item supplies); flags subclassed blocks/items |
| `list_triggers` | Triggers with level/scope; filter by block, item, or level (`verbosity=detailed` adds a PL/SQL preview) |
| `get_trigger` | One trigger's decoded PL/SQL body (same-named triggers told apart by `ownerPath` or `level`); `resolve` follows a subclassing pointer into a cached parent module |
| `list_program_units` | Procedures, functions, package specs/bodies with line counts |
| `get_program_unit` | One program unit's PL/SQL (disambiguate spec/body via `unitType`); `resolve` as for `get_trigger` |
| `search_source` | Line search over one fetched module: extracted PL/SQL (`plsql`), the raw XML (`xml`), or both; paginated via `offset`/`nextOffset` |
| `search_modules` | The same search across **every cached module** — cross-form calls, shared `:GLOBAL`s, subclassing pointers; reports the modules it could not reach; paged via an opaque `cursor` |
| `read_source` | A line range of a cached file, by `uri` (which names its module) or `module` + `file` — the converted XML or an extracted PL/SQL sidecar |
| `get_object_xml` | The raw XML fragment of any named object — the escape hatch |
| `annotate_element` | Store a note / summary / tag / classification about one element |
| `relate_elements` | Record a directed cross-reference between two elements (e.g. a trigger `calls` a program unit) |
| `get_element_annotations` | The notes and relations stored about one element |
| `search_annotations` | Search a module's stored notes/tags/relations by text, kind, or tag |
| `remove_annotation` | Delete a stored annotation or relation by id |

Every tool checks its arguments against its own input schema before running. An argument it does
not have is never silently dropped: the call fails with every problem listed at once (unknown
arguments with the name most likely meant, missing ones with their permitted values, values outside
an enum), plus an example call where the tool has one.

The server also offers a resource per cached module (`oracleforms://ORDERS.fmb/index`), the
`oracleforms://{module}/index` and `oracleforms://{module}/annotations` resource templates, and an
`explain_module` prompt.

[Tools in depth](guide/tools-in-depth.md) explains how to read the results: property classes,
`source` references, subclassed objects, tracing across modules, and annotations.

## Quick start

Pick the channel that matches your client: the [plugin](#claude-code-one-command-plugin) for Claude
Code, the [`.mcpb` bundle](#claude-desktop-one-click-bundle) for Claude Desktop, the
[Docker image](guide/install.md#docker-copy-mode-only) for
[other MCP clients](guide/install.md#other-mcp-clients), or a
[build from source](#build-from-source). The server is published to the
[official MCP Registry](https://registry.modelcontextprotocol.io) as
`io.github.aoreshkov/oracle-forms-mcp`.

### Claude Code (one-command plugin)

```
/plugin marketplace add aoreshkov/oracle-forms-mcp
/plugin install oracle-forms@oracle-forms-mcp
```

Claude Code prompts for your forms directory, then fetches and checksum-verifies the released
server into the plugin's data directory on first use — no clone, no build, no JSON to edit. The
tools are live after `/reload-plugins`, and the plugin's `trace-form` skill teaches the
[traversal](guide/tools-in-depth.md#tracing-across-modules) — the order to ask in, and the readings
that mislead — so a trace does not start with `grep`.
Skip the prompt and the server still starts; its tools then answer with an error asking for the
directory until you set it with `/plugin configure oracle-forms@oracle-forms-mcp`.

> [!IMPORTANT]
> **Requires a JDK 25+** (`javac -version`), because the plugin's bootstrap runs in Java's
> single-file source mode. It uses the one on your `PATH` unless you set the plugin's `java` option
> to another JDK's `java` executable, and falls back to `JAVA_HOME` when that one is too old for the
> server. With only a JRE, use the `.mcpb` bundle or `claude mcp add` below.

Details, configuration options, and the escape hatches are in the
[plugin README](plugins/oracle-forms/README.md).

### Claude Desktop (one-click bundle)

Download `oracle-forms-mcp-<version>.mcpb` from the
[latest release](https://github.com/aoreshkov/oracle-forms-mcp/releases/latest) and open it. Claude
Desktop installs it as a connector and prompts for your forms directory with a native folder picker
— no JSON editing, no local build. Leave it empty and the connector still starts; its tools then
answer with an error asking for the directory until you set it in the connector's settings.

> [!IMPORTANT]
> **Requires a JRE 25+.** The bundle ships the server, not a Java runtime. It runs on the **Java
> installation** chosen in the connector's settings, else the one `JAVA_HOME` names, else the `java`
> on your `PATH`. The [MCPB](https://github.com/modelcontextprotocol/mcpb) manifest format can only
> declare Node and Python runtimes, so this cannot be checked at install time: if the connector
> fails to start, choose the Java installation in its settings, or confirm `java -version` works in
> a terminal.

The same copy-mode caveat as [Docker](guide/install.md#docker-copy-mode-only) applies unless the
machine has an Oracle Forms installation (`ORACLE_HOME`) for live `.fmb`/`.pll` conversion.

### Build from source

Building works from any JDK 17+ that can start the wrapper (Gradle downloads JDK 25 for its daemon
and the toolchain when none is installed). Running needs a JRE 25+. Build and install:

```
gradlew :server:installDist
```

Register with Claude Code (stdio):

```
claude mcp add oracle-forms -- server/build/install/server/bin/server --forms-dir C:\path\to\forms
```

The `server` script runs on the Java installation `OFMCP_JAVA_HOME` names, else `JAVA_HOME`, else
the `java` on `PATH`, so a JRE that is not on `PATH` is one variable away:

```
claude mcp add --env OFMCP_JAVA_HOME=C:\Java\jdk-25 --transport stdio oracle-forms -- server/build/install/server/bin/server --forms-dir C:\path\to\forms
```

Try it without any Oracle installation using the bundled fixtures:

```
server --forms-dir sample-forms
```

## Using your own converter

If your site wraps the Forms tools or uses a different converter, `--convert-command` runs it in
place of `frmf2xml`, and `--compile-command` does the same for `.pll` libraries. The
[converters guide](guide/converters.md#using-your-own-converter) has the command syntax, the
placeholders and the precedence.

## Documentation

| Page | What it covers |
|---|---|
| [Installing](guide/install.md) | Other MCP clients, the HTTP transport, Docker |
| [Configuration](guide/configuration.md) | Command-line options and environment variables |
| [Converters](guide/converters.md) | Keeping the converted XML, your own converter, PL/SQL libraries, notes on the Oracle tools |
| [How it works](guide/how-it-works.md) | The fetch-and-index sequence and the cache layout |
| [Tools in depth](guide/tools-in-depth.md) | How to read tool results, subclassed objects, tracing across modules, annotations |
| [Claude Code plugin](plugins/oracle-forms/README.md) | Plugin options, how the server is delivered, escape hatches |
| [Changelog](CHANGELOG.md) | What changed in each release |

## Privacy Policy

Oracle Forms MCP runs entirely on your machine and collects nothing.

- **Data collection.** None. The server has no telemetry, analytics, crash reporting, or accounts,
  and it makes no network requests of its own.
- **What it reads and stores.** It reads the modules in the `--forms-dir` you name and writes
  derived files — converted text forms, decoded PL/SQL, and `index.json` — to the local
  [cache](guide/how-it-works.md#cache) (or `--converted-dir`). Annotations you or your assistant record are written to
  the local annotations store. All of these stay on your disk, and you can delete them at any time.
- **Where content goes.** Module content leaves the server only as responses to the MCP client you
  connected it to. What that client does with it — including sending it to a model provider — is
  governed by that client's own privacy policy, not this one. The `http` transport listens on
  `127.0.0.1` only unless you bind it elsewhere with `--host`.
- **Third-party sharing.** None. The one exception to "no network" is the Claude Code plugin's
  launcher: it asks the GitHub API for the latest release at most once a day and downloads the
  release zip and its checksum from GitHub Releases. Those requests go to GitHub, under
  [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement),
  and carry nothing from your modules. Pin `server_version` or use `OFMCP_SERVER_HOME` to avoid
  them.
- **Retention.** Cached and annotation files persist until you delete them; the software keeps
  nothing anywhere else.
- **Contact.** Questions go to [GitHub issues](https://github.com/aoreshkov/oracle-forms-mcp/issues);
  security reports follow [SECURITY.md](SECURITY.md).

## Help

Ask questions in [Discussions](https://github.com/aoreshkov/oracle-forms-mcp/discussions), and
report bugs or request features in [issues](https://github.com/aoreshkov/oracle-forms-mcp/issues).
Security reports follow [SECURITY.md](SECURITY.md).

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers building, testing and sending a change.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
