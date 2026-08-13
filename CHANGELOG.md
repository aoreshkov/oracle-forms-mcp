# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **`--converted-dir` is now the directory converters write into**, not a destination files are
  moved to after conversion. The converter runs with it as its working directory, so a
  `--convert-command` that writes into its working directory is unaffected, while one that writes
  to a fixed location of its own can finally be pointed at it — previously such a wrapper failed
  with "produced no output file" even though the XML existed, because the setting was consulted
  only *after* a successful conversion. Conversion output is now identified by its canonical name
  (`orders_fmb.xml`) before falling back to the newest matching file, and conversions sharing one
  output directory are serialised, so a shared directory cannot attribute one module's output to
  another. Converted files still end up under their canonical name and the index still records
  them as `converted/<name>`, so nothing changes for readers.

## [0.6.0] - 2026-08-10

### Fixed
- **`--convert-command` now takes a command *with its arguments*, not just an executable.** Site
  wrappers are rarely a bare script — they are an interpreter, a container, or a compatibility
  layer that needs arguments of its own (`wine frmf2xml.exe`, `convert.sh --xml --quiet`) — and
  those were impossible to express. The value is now parsed into an argv list, either as a quoted
  string (`"C:\tools\my conv.bat" -xml`, backslashes literal so Windows paths need no doubling) or
  as a JSON array (`["wine", "f2x.exe", "-xml"]`, the shape MCP clients use for `command`/`args`).
  It is still spawned directly, never through a shell. The module's absolute path is substituted
  for `{}` anywhere in the command, and appended as the last argument when `{}` does not appear —
  the earlier `<command> <module>` convention — so existing wrappers need no changes. A value that
  names an existing file is taken whole, spaces and all, so an unquoted `C:\Program Files\…` path
  configured before this change keeps working. The program may also be a bare name looked up on
  `PATH`, and a command that cannot be started now fails with a message naming the flag instead of
  a raw IO error.
- The same option in every installation channel accepts the full command line: the `.mcpb` bundle
  and the Claude Code plugin now declare `convert_command` as a text field rather than a file
  picker (which could only ever yield one path), and the MCP Registry listing declares
  `--convert-command` / `OFMCP_CONVERT_COMMAND` as a string rather than a filepath.

## [0.5.0] - 2026-08-07

### Added
- **`--converted-dir <path>`**: keep the converted XML / `.pld` text forms in a directory of your
  own instead of inside the cache — one flat directory for all modules, each file named the way
  Oracle names it (`orders_fmb.xml`, `utils.pld`), so a re-fetch replaces a module's file rather
  than accumulating copies. The directory is created if missing and may not be the forms directory
  (the names would collide with the pre-converted modules read from there). Conversion still runs
  in the module's own cache directory and the result is moved into place afterwards: converters are
  driven with their working directory as the output directory and judged by "newest matching file
  written after the run started", so converting two modules directly into one shared directory
  could attribute one module's output to another. The index addresses the text form by a stable
  `converted/<name>` path either way, so a cache stays valid whether or not the option is set.
- Both converter options are now settable through **every distribution channel**, not just the
  command line: `OFMCP_CONVERT_COMMAND` / `OFMCP_CONVERTED_DIR` environment variables (a flag wins
  over its variable) for containers and `env`-based MCP configs, `userConfig` entries in the Claude
  Code plugin, `user_config` entries in the `.mcpb` bundle, and declared arguments plus environment
  variables in the MCP Registry listing. Optional values are passed to the server through the
  environment rather than as arguments, so an unset one can never shift the argument list; an empty
  value — or an unsubstituted `${user_config.…}` placeholder from a launcher — counts as "not
  configured" rather than failing every conversion.

### Changed
- `search_source` now scans the module's own converted text form rather than every file in its
  converted directory, which keeps a shared `--converted-dir` from leaking one module's hits into
  another's results.

## [0.4.0] - 2026-08-07

### Added
- **`--convert-command <path>`**: run a site-supplied converter instead of `frmf2xml`, for
  installations that wrap the Forms tools with their own environment setup or logon handling. It
  is invoked as `<command> <module>` with the working directory set to the module's cache dir —
  the same convention `frmf2xml` already follows — and must emit the formats the parser reads
  (XML for fmb/mmb/olb, `.pld` for pll). Oracle's `_fmb.xml` naming is preferred but not required.
  Precedence is `--convert-command` → `ORACLE_HOME` → copy-mode; a blank value counts as unset.
  The command is operator configuration and is never reachable from a tool argument; it is spawned
  with an argv list, never through a shell.
- **One-command Claude Code install**: the repository is now a Claude Code plugin marketplace.
  `/plugin marketplace add aoreshkov/oracle-forms-mcp` + `/plugin install
  oracle-forms@oracle-forms-mcp` registers the MCP server, prompts for the forms directory
  (`userConfig`, the plugin equivalent of the MCPB folder picker), and fetches the released server
  on first use. A plugin ships as a git checkout and cannot carry the jars, so
  `plugins/oracle-forms/launcher/OracleFormsMcpLauncher.java` resolves the release (`latest`, cached
  for 24 h, or a pinned `server_version`), verifies its published SHA-256, unpacks it into the
  plugin's data directory, and loads it into its own JVM so the stdio pipes stay unbroken. It runs
  in Java single-file source mode, so this channel needs a **JDK 21+** rather than a JRE.
- Releases now publish `oracle-forms-mcp-server-<version>.zip.sha256` next to the zip; the plugin
  launcher refuses to install a distribution that doesn't match it.
- **One-click desktop install**: releases now ship an `.mcpb`
  ([MCP Bundle](https://github.com/modelcontextprotocol/mcpb)) alongside the server zip and the
  container image. Opening it in Claude Desktop installs the connector and asks for the forms
  directory with a native folder picker. The bundle carries the server, not a Java runtime, so a
  **JRE 21+ must be on `PATH`** — MCPB manifests can only declare Node/Python runtimes, so this is
  documented rather than enforced. Built by `gradlew :server:packageMcpb`; its manifest version is
  generated from `project.version`, so there is still no extra file to bump at release time.
- The `.mcpb` is published as a second package in the MCP Registry listing (`registryType: mcpb`)
  with the `fileSha256` clients verify before installing, and carries build-provenance attestation
  like the other release artifacts.
- Registry listing metadata: `title`, `websiteUrl`, and `icons` (a new square mark in `assets/`)
  in `server.json`, plus a `publisher-provided` `_meta` block spelling out the runtime
  requirements of each distribution channel. The release workflow now guards these fields and
  re-pins the icon URLs to the tag being released.

### Changed
- `ModuleConverters.forEnvironment` takes an additional (defaulted, trailing) `convertCommand`
  parameter. Source-compatible, but the JVM signature changed — embedders compiling against
  `core` should recompile.

## [0.3.0] - 2026-07-16

### Changed
- **Package annotation identity**: a package's spec and body are now distinct annotation targets.
  `annotate_element`/`relate_elements`/`get_element_annotations` on a `program_unit` that exists as
  both require `ownerPath='PACKAGE_SPEC'` or `'PACKAGE_BODY'` (mirroring `get_program_unit`'s
  `unitType`), and the stored `ElementId` carries that owner. Package annotations stored before
  this release (owner-less) are no longer matched by the element views but stay visible via
  `search_annotations` and the `oracleforms://{module}/annotations` resource — re-assert them with
  an `ownerPath`. Procedures/functions are unaffected.
- `get_trigger` gains an optional `ownerPath` argument using the same scope vocabulary as the
  annotation tools: `BLOCK`, `BLOCK.ITEM`, or `:FORM` for the form-level trigger (`:` cannot occur
  in a Forms name, so the token never collides with a block named `FORM`). An exact `ownerPath`
  match now takes precedence, so `STOCK` selects the block-level trigger even when an item in
  `STOCK` has a same-named one. The `block`/`item` arguments keep working.

### Fixed
- Same-named elements at different scopes are no longer silently conflated. Annotating an item,
  menu item, or package unit whose name matches several elements now fails with an error listing
  the candidate owners instead of binding to the first match; ambiguity errors for triggers list
  the exact `ownerPath` tokens to pass. Form-level and block-level triggers shadowed by same-named
  triggers at other levels are now reachable via `:FORM` / exact-owner precedence.
- PL/SQL sidecar files no longer overwrite each other when two elements produce the same file name
  (a block literally named `FORM` vs the form-level scope, case-variant names on case-insensitive
  filesystems, sanitizer-collapsed characters). Colliding names get a deterministic `~2`, `~3`, …
  suffix in document order, so every trigger/unit/menu-command body survives with its own
  `SourceRef`.
- Docker: the annotation store and module cache are now writable when a volume is mounted at
  `/home/mcp/.cache`. The image runs as non-root (uid 10001); the cache tree is pre-created and
  owned by that user before the `VOLUME` is declared, so an anonymous or named volume inherits the
  ownership instead of being created root-owned (which silently failed every annotation write with
  `AccessDenied`). README documents mounting the volume to persist annotations across runs, plus the
  bind-mount `chown` caveat.
- Docs: the README "See it work" transcript is now reproducible against the bundled
  `sample-forms` (real module names and counts), the cache-layout tree includes the
  `plsql/menu-items` sidecars, and the `list_triggers`/`search_source` tool descriptions mention
  the 0.2.0 `verbosity` and `offset`/`nextOffset` pagination options.

### Known limitations
- Menu-level triggers carry no menu owner in the index, so two same-named triggers in different
  menus of one `.mmb` cannot be told apart yet (the ambiguity is reported, not misresolved).

## [0.2.0] - 2026-07-16

### Added
- Annotation layer: `annotate_element`, `relate_elements`, `get_element_annotations`,
  `search_annotations`, and `remove_annotation` tools let the model persist durable
  meta-information (notes, tags, summaries, classifications, and cross-reference relations) about
  Forms elements. Stored outside the fingerprinted cache in a separate `AnnotationStore` keyed by a
  stable `ElementId`, so annotations survive `fetch_module` re-indexing and are flagged
  `staleAgainstSource` (never deleted) when they predate the current source. Surfaced inline by the
  read tools, exposed as the `oracleforms://{module}/annotations` resource, with a
  `--annotations-dir` option (default `<cache dir>/annotations`).
- `search_source` pagination via `offset`/`nextOffset` (`SearchResults` gains `offset` + `nextOffset`).
- `list_triggers` verbosity control: `concise` (default) omits the PL/SQL preview, `detailed` includes it.

## [0.1.1] - 2026-07-13

### Changed
- Shortened the `server.json` server description to satisfy the MCP Registry 100-character limit.

## [0.1.0] - 2026-07-12

### Added
- MCP server (stdio + Streamable HTTP) serving Oracle Forms modules from a directory.
- Module types: `.fmb`, `.mmb`, `.pll`, `.olb`.
- Conversion via `ORACLE_HOME` tools (`frmf2xml`, `frmcmp_batch`) or copy of pre-converted
  `*_fmb.xml`/`*_mmb.xml`/`*_olb.xml`/`*.pld` files when `ORACLE_HOME` is unset.
- Structured on-disk index with source-file fingerprinting and staleness detection.
- 11 tools: `list_modules`, `fetch_module`, `get_module_overview`, `list_blocks`, `get_block`,
  `list_triggers`, `get_trigger`, `list_program_units`, `get_program_unit`, `search_source`,
  `get_object_xml`.
- Per-module index resources, `oracleforms://{module}/index` template, `explain_module` prompt.

[Unreleased]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/aoreshkov/oracle-forms-mcp/releases/tag/v0.1.0
