# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.10.0] - 2026-09-12

### Fixed
- **A cached module is no longer served by a build that did not index it.** Upgrading the server
  changes no `.fmb`, so every already-fetched module stayed warm against its source fingerprint and
  kept answering with the previous build's facts — indefinitely, and invisibly. A session run on
  0.9.0 against modules indexed by 0.8.0 saw every one of that release's fixed defects: subclassed
  triggers reported empty, whole procedures reported as one line, items with no property class.
  The index now records the parser version that wrote it. A mismatch reads as stale — reads say so
  and name the call that fixes it, `list_modules` reports `STALE` with
  `staleReason: "INDEX_OUTDATED"` — and `fetch_module` heals it by re-parsing the converted file
  already in the cache entry. **No conversion runs**: the source is unchanged by definition, so
  healing a large directory after an upgrade costs a parse per module and no Oracle tooling.
- **One-line PL/SQL previews are capped.** `firstLine` took the first non-blank line whole, and a
  body that really is one physical line has no bound — `list_triggers(verbosity: "detailed")` on a
  large module could return several bodies in the field documented as a one-line preview. It is now
  cut at 200 characters with an ellipsis, beside the honest `lineCount`.

### Changed
- **`search_source` now ignores case by default**, as `search_modules` already did, and takes
  `ignoreCase` to opt out. The two search tools disagreeing about the same query was a trap:
  PL/SQL is case-insensitive and Forms writes its own names in upper case, so the case-sensitive
  default silently under-reported. Pass `ignoreCase: false` for the previous behaviour.

## [0.9.0] - 2026-09-11

### Fixed
- **Doubly-escaped PL/SQL is recovered at parse time.** Some converters write a newline as
  `&amp;#10;` rather than `&#10;`, so the XML parser decodes it once and the body still holds the
  literal characters `&#10;`. The whole body then reads as a single physical line: `lineCount` was
  1 for a hundred-line procedure, every recorded line range collapsed onto that line, and a
  line-oriented search could only ever report line 1 of a very long one. Bodies that carry no real
  line break, and whose leftover numeric references decode into one, are now recovered — and
  flagged `textEncoding: "recovered"` rather than quietly swapped in, because a body that genuinely
  contained those characters is indistinguishable from one that was escaped twice. A correctly
  escaped file, and a genuine one-liner, are untouched.
- **Inherited (subclassed) objects are no longer served as empty.** A block, item, trigger or
  program unit subclassed from another module stores only its overrides, so its body came back as
  `""` with nothing to distinguish "this trigger has no code" from "this trigger's code lives in
  another module" — a reader who trusted the tool concluded the button did nothing. Forms splits
  the pointer across two levels (`ParentModule`/`ParentFilename`/`ParentName` on the owner,
  `SubclassSubObject="true"` on each inherited child), so even `get_object_xml` on the object
  itself could not answer it. The parser now reassembles that pointer and states it in the parent
  module's own vocabulary, and `get_trigger`, `get_program_unit`, `get_block`, `list_triggers` and
  `get_object_xml` all carry it. The same applies to objects copied in with an **object group**,
  whose form-level triggers and program units are stored empty in exactly the same way; a parent
  in the same module, which is a property class rather than a hidden definition, is not reported
  as inheritance.
- **`list_modules` no longer overflows on a real forms directory.** It returned every module
  unconditionally, so a directory of a few thousand modules produced a response several times
  larger than the tool-output limit of any MCP client — discovery, the entry point to every other
  tool, was the one call that could not succeed. It now takes `pattern` (substring, or a regex
  with `regex: true`), `type`, `status`, `limit` (default 100, max 500) and an opaque `cursor`,
  and returns `total`, `returned`, `truncated`, `nextCursor` and a `countsByStatus` summary that
  covers the whole name/type match even when `status` narrows the rows. Filtering happens before
  any file is stat-ed or hashed, so a narrow call is also a cheap one.
- **`resources/list` is bounded.** One MCP resource was registered per cached module — at startup
  for the whole cache, and again on every fetch — which made a warm cache over a large forms
  directory overflow the client on a request it issues by itself, before the model calls anything.
  At most 50 recently fetched modules are now registered; every cached module remains readable
  through the `oracleforms://{module}/index` URI template, which addresses all of them.

### Added
- **The server's `instructions` now carry the guidance that no single tool description can.** They
  state the traversal order, the hazard of reading the cached files directly — text on disk can
  describe a form that is no longer the one being served, which is what `STALE` reports, and the
  paths in a result are cache-relative rather than host paths — and the call that replaces each
  shell reflex (`get_block` not `grep`, `get_object_xml` not `sed`, `read_source` for the lines
  around a hit, `search_modules` not a directory walk). They also say up front that an empty PL/SQL
  body means nothing without `bodySource`. A regression test pins those facts: a client sees the
  instructions once, so a quiet edit dropping one would otherwise be invisible.
- **A `trace-form` skill in the Claude Code plugin.** The plugin shipped only `.mcp.json`; it now
  also carries the traversal order at the length a worked example needs — the four readings that are
  misleading rather than merely incomplete, when `get_object_xml` is the right call and when it is a
  detour, and a full trace over the demo directory. Claude loads it when a question is about how a
  form, field, button or modal behaves; `/oracle-forms:trace-form` invokes it directly. The plugin
  version tracks its own files and is deliberately not bumped by a server release.
- **The trace itself is a regression test.** `TraceScenarioTest` follows a modal window through a
  subclassed toolbar block, an inherited trigger, a `Do_Key` into a block-level `KEY-HELP`, and a
  `:GLOBAL` back into the field on the calling form — driving the registered MCP tools, with each
  call's arguments taken from the previous call's result. Nothing in it opens a file, so a
  regression that forced a reader back to raw XML could not be written this way at all. Each
  response's size is recorded and asserted under a client's tool-output budget, so a field added to
  a DTO later cannot quietly re-create the day discovery returned more than any client would accept.
- **`search_modules` — one search across every cached module.** `search_source` searches the module
  it is given, so the questions that leave a form had no answer: which forms call this one, what
  else writes the `:GLOBAL` a modal hands its result back through, which modules subclass a shared
  toolbar block. Each of those meant fetching candidate modules one at a time, which on a directory
  of a few thousand is not a strategy. A distinct tool rather than `search_source(module: "*")`:
  `scope: "all"` already means "PL/SQL *and* XML within one module", and overloading it would make
  the choice between the two tools ambiguous. Hits carry the module (as `moduleSpec`, the `NAME.ext`
  string the other tools take), the file, the line, a snippet and the `uri` that opens it, ordered
  by module, file and line. Matching is case-insensitive by default — Forms code writes the same
  name as `PICKER`, `picker` and `Call_Form('picker')` — with `ignoreCase: false` for precision.
  Only fetched modules are searched, since reaching an un-fetched one would mean converting it, so
  `cachedModules`, `scannedModules`, `skippedNotCached` and `skippedStale` report the coverage and
  the hint names the `fetch_module` call that widens it: a search that reached a tenth of the
  directory must not read like one that found nothing. Bounded twice — hits per page, and modules
  read per call, because a query that matches nothing would otherwise read every converted file in
  the cache — with an opaque `cursor` that resumes at the exact position and is bound to the
  arguments it was minted for.
- **The properties that give an object its role.** `ItemInfo` gains `propertyClass`, `visible`,
  `required` and `lovName`; in most Forms applications the property class *is* an item's semantics
  — a push button is an LOV button only because of the class it inherits — and without it every
  button looked alike. `WindowInfo` gains `modal`, `width`, `height` and the toolbar canvas names,
  `CanvasInfo` gains `propertyClass`, `raiseOnEnter` and its size and viewport. Whether a window is
  modal decides how a form is read, and all of it previously cost one `get_object_xml` per object.
  A property Forms did not write stays `null` — meaning "not overridden", never `false`.
- `verbosity` on `get_module_overview`: `detailed` adds a `detail` section carrying the window and
  canvas objects behind two of the name lists, which also answers "which window hosts this canvas?"
  in the same call. The name lists keep their shape, so nothing a caller already reads changes.
- `verbosity` on `get_block`, since a block of a real form runs to dozens of items. The default
  keeps each item's name, type, property class, prompt, trigger names and subclassing pointer —
  everything a reader would otherwise have to infer — and `detailed` adds data type, column, canvas
  and the visible/required/LOV properties.
- **Source refs are addressable.** A `SourceRef` line range named a cache-relative path with no
  tool exposing the root, so locating the file it pointed at meant searching the filesystem. Every
  result that names a file now carries a `source` — the cache-relative `file`, the line range, and
  a `uri` that opens it — plus a `resource_link` content block, and `fetch_module` returns the
  `convertedUri` of the text form it produced. `search_source` hits carry the `uri` of the file
  they were found in. The paths stay layout-independent: an absolute host path would be useless
  under the container image and over HTTP, and it is the one thing a `SourceRef` must never carry.
- `read_source` — a line range of any cached file, by `uri` or by `file`, with `startLine`,
  `endLine` and `maxLines`. Capped on both lines and characters (neither bounds this data alone:
  a converted form runs to hundreds of thousands of lines, while one line of PL/SQL can be a whole
  procedure), reporting `totalLines` and `truncated` so the next call knows where to resume.
- Resource templates `oracleforms://{module}/converted` and
  `oracleforms://{module}/plsql/{category}/{name}`, so a client's own resource machinery can read
  the converted text form and the extracted PL/SQL. Templates rather than one resource per file —
  a form yields hundreds of sidecars, and `resources/list` has no cursor. Reads are capped, and a
  truncated one says so in the text it returns, naming the `read_source` call that continues it.
- `bodySource` on every result that serves PL/SQL (`own`, `inherited`, `resolved`, `empty`) and an
  `inherited` reference naming the parent module, file, and the object's name and owner path
  *there* — shaped so it is directly callable (`BAR_LIST.SELECT` here is `name: SELECT`,
  `ownerPath: BAR` in the parent), plus `objectGroup` where a group carried the object in. When a
  body is inherited, the result also carries a hint naming the exact next call.
- `resolve` on `get_trigger` and `get_program_unit`: follows the subclassing pointer and returns
  the inherited body with `resolvedFrom`. It reads only modules that are **already cached** —
  reaching an un-cached one would mean converting it, which a `readOnlyHint` tool must not do — and
  degrades to the pointer plus a `fetch_module` hint rather than failing.
- `get_object_xml` returns the subclassing pointer resolved to the level it was asked about, so
  the escape hatch answers the question that sent a reader to it.
- A hard row ceiling plus a `truncated` flag on every list-shaped result (`list_blocks`,
  `list_triggers`, `list_program_units`, `search_annotations`, and each section of
  `get_module_overview`), with `total` reporting what was left behind. A capped page that says so
  beats a response the client has to reject.
- A flat `name` on every `list_modules` row, beside the existing `module` key — the identifier to
  match on, without reaching through the nested object.

## [0.8.0] - 2026-08-20

### Fixed
- **Concurrent `fetch_module` calls for the same module no longer collide.** The MCP Kotlin SDK
  now dispatches tool calls in parallel, and the fetch pipeline (convert → parse → cache) was
  written for the serial dispatch that preceded it: two overlapping calls both missed the cache
  and both converted into the same directory, on Windows failing outright with "the process
  cannot access the file because it is being used by another process". Fetches are now serialised
  per module, so one caller does the work and the rest get its result as a cache hit. Fetches of
  *different* modules still run in parallel.
- **The cached module index is written atomically.** It was written in place, so a read that
  landed mid-write saw a truncated file, treated the entry as corrupt, and silently discarded a
  valid cache entry — reachable in normal operation once tool calls could overlap. The index is
  now written to a temporary file and renamed over the old one, and a concurrent reader never
  observes a partial or missing index.
- **A module's index resource can no longer be registered twice.** Registering a duplicate is now
  an error in the SDK rather than a silent replace, so two first-time fetches of one module raced
  and the loser reported a successful fetch as a failure.

### Changed
- MCP Kotlin SDK 0.14.0 → 0.15.0. Malformed request parameters are now reported as `Invalid
  params` rather than `Internal error`; clients sending `Accept: */*` or `application/*` are
  matched correctly; Streamable HTTP responses keep their status and body for clients that accept
  only `text/event-stream`.

### Added
- **Keep-alive heartbeats on the HTTP transport.** Converting a large module can hold the event
  stream open for up to `--conversion-timeout` (120 s by default) with only three progress frames
  in between, which idle-timeout proxies and clients read as a dead connection. The stream now
  emits a heartbeat every 30 seconds.

## [0.7.0] - 2026-08-13

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

[Unreleased]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/aoreshkov/oracle-forms-mcp/releases/tag/v0.1.0
