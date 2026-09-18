# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.12.0] - 2026-09-18

### Added
- **`search_source` says what a page does not show.** Every page now carries `total` (every hit in
  the module, not only this page's), `files` (hits per file across the whole result, with
  `fileTotal`/`filesTruncated`) and, when hits remain, a `hint` naming the exact call for the next
  page. A caller who stopped at the last hit of a cut page used to have sampled without knowing it;
  now one call answers whether a name appears anywhere else, and roughly where. The page itself is
  also kept inside the result budget — a page of long XML lines is cut to fit and continues at
  `nextOffset` like any other.
- **`fetch_module` names the attached libraries that are not fetched yet** — with the
  `fetch_module` call for each, and the converter's caveat when it cannot convert libraries. A
  form's triggers usually call into them, and an unfetched library is invisible to every other tool.
- **`get_block` names every item it could not resolve.** At `verbosity="detailed"`, `unresolvedItems`
  lists each item missing from `effectiveDml` with its property class, a `reason`
  (`CLASS_MODULE_NOT_FETCHED`, `CLASS_NOT_FOLLOWABLE`, `SUBCLASSED`) and, where fetching fixes it,
  the `missingModule`. The unknown used to be stated only per class and in prose, so an item absent
  from the map was easy to read as an item with nothing to report — which is how a class-supplied
  length limit got reported as no limit. `effectiveDml` itself is unchanged: an item is there only
  when its whole chain resolved, so a `null` in it is still the Forms default.
- **`get_block(items=[...])` narrows a block to the items a question is about.** Names are
  case-insensitive and may carry the block (`ORDERS.STATUS`); `itemsMatched` counts the matches while
  `itemTotal` still counts the block, and `columns` are still set against all of its items. An
  unknown name fails with the block's item names. A hint that follows up on a cut `effectiveDml` or an
  unresolved class now names this narrowed call.
- **`get_block` serves a block's master-detail relations.** `block.relations` lists the relations
  the block is the master of — detail block, join condition, `deferred`, `autoQuery`, `deleteRecord`,
  `relationType` and `preventMasterlessOperations` — and `detailOf` the relations that name it as
  their detail, with the master block each is written on. Both come back at every verbosity: with
  `preventMasterlessOperations` a detail block can only be queried through its master, so this is
  what decides what a screen can see, and it used to be reachable only by knowing to grep the XML
  for an element no tool named. A join condition is recovered from double escaping and marked
  `joinEncoding: RECOVERED`, like a body; a subclassed block or relation says where its definition
  lives. `get_object_xml` now lists `Relation` among its element types (its owner is the master).
- **Item size.** `get_block(verbosity="detailed")` adds each item's `width` and `height` — layout
  units, usually pixels, and not the length a field accepts, which stays `maximumLength`.
- **`effectiveGeometry` resolves an item's size through its property class**, the way
  `effectiveDml` resolves the rest. A classed item in a real form writes a `Width` and no `Height`
  at all — measured on a 142-item screen, 107 of its 125 classed items carried a width and *none*
  carried a height — so serving only what the item wrote reported every one of them as having no
  height, an absence indistinguishable from one nobody set. That is the failure `unresolvedItems`
  exists to prevent, and the size question had been left outside it. An item is in
  `effectiveGeometry` only when its whole chain resolved, is named in `unresolvedItems` when it did
  not, and is left out entirely when no link of the chain writes a size — never served as a row of
  `null`s. `items[].width`/`height` still mean what the item itself wrote.

### Changed
- **A module that is not in the forms directory is said to be missing, not to be unfetched.** Two
  hints in the same response used to disagree about this: the attached-library hint stayed silent
  when the library was not in the directory — making an unreadable library look like a fetched one —
  while the property-class hint named a `fetch_module` for a module that is not there, a call that
  fails. Both now draw the same distinction: the call is named when it would work, and the fact is
  stated either way. `unresolvedItems` gains the reason `CLASS_MODULE_NOT_IN_DIRECTORY` beside
  `CLASS_MODULE_NOT_FETCHED`, because one names an action and the other names a limit, and a form
  whose property classes live in a module this server does not serve gets the second.
- **A `search_source` that finds nothing says what it read and where it could not look.** An empty
  result carried no total, no coverage and no hint, so it read as an answer — which is how a pattern
  that could never have matched becomes a finding that the form does not contain the thing. It now
  reports `filesSearched` and a hint naming what the scope cannot reach: from `plsql`, that
  properties and subclassing pointers are attributes (`scope="xml"`); from `xml`, that a `<` there
  is a literal `<` and never `&lt;`; and from either, that Forms writes values with no PL/SQL naming
  them at all — query population, a property class, an LOV return item, a relation's join key.
- **The column-name lists are bounded, and say when they are cut.** `columnsWithoutItem` and
  `mandatoryColumnsWithoutItem` are the answer `columns=true` exists to give, so they are still
  computed over every column and still served ahead of the column rows — but they were built after
  the rows had been budgeted and were capped by nothing at all, which on a thousand-column table
  carried the whole result past the size ceiling on names alone. They now spend from the same
  budget, cap at 500 names, and carry `columnsWithoutItemTotal`,
  `mandatoryColumnsWithoutItemTotal` and `namesTruncated` so a shortened list is never read as the
  whole set.
- **The tool descriptions name the block's own DML properties** — `dml.whereClause`,
  `orderByClause`, `dmlDataTargetName`, `keyMode`/`lockMode` — instead of only "its DML properties".
  A `whereClause` restricts what a block queries without a line of PL/SQL, so it decides what a
  screen can see; it was documented only for item-level `effectiveDml` and had to be found by
  reading the returned JSON.
- **`get_trigger` and `get_program_unit` say what their line numbers count from.** A body is
  extracted to a file holding that body alone, so line 1 is the first line of the body: cite it as
  `POST-INSERT:75`, never `ORDERS.fmb:75`, which addresses a line nobody opening the form can find.
  A `.pll` is the exception — its units are ranges within the one `.pld` dump of the whole library.
  `SourceLocation` now documents the same rule.
- **The `trace-form` skill gains "Where is a value written"** — the PL/SQL write shapes to search
  for (`:=`, `SELECT`/`FETCH`/`RETURNING … INTO`, `Copy`, `Set_Item_Property`/`Set_Block_Property`,
  OUT arguments, `:GLOBAL`), the ones no search can reach, and the declarative writers no PL/SQL
  search can see at all (the query itself, `copyValueFromItem`, `initialValue`, relation join keys,
  LOV return items). With two rules that each cost a wrong claim: a zero-hit pattern proves the
  pattern absent, not the write; and in `scope: "xml"` a `<` is a literal `<`, so a pattern spelled
  `&lt;Relation` is a silently dead branch. Plugin version 1.2.0.
- **Index format v4.** Every warm cache entry reports `STALE` with `staleReason: INDEX_OUTDATED`
  once after upgrading; `fetch_module` heals it by re-parsing the converted file already in the
  cache — no re-conversion. (One re-index, not two: v3 was never released.)

### Fixed
- The `INDEX_OUTDATED` message read "indexed by a older build".
- **A cut column list is reported.** On a block over a wide base table, `columns` was cut to fit
  while the result's own `truncated` stayed `false` — the flag was assembled from the item and
  property lists alone — and no hint mentioned the columns at all. A caller reading the rows as the
  table was short some columns with nothing saying so: the same silent omission as an item missing
  from `effectiveDml`. `truncated` now covers the column rows and both name lists, and the hint says
  how many of how many columns the rows show, that the name lists still cover every column, and how
  to leave more room for the rest.
- **A cut `effectiveDml` is reported.** On a block whose items fit but whose resolved properties did
  not, `get_block(verbosity="detailed")` dropped the overflow with no signal: `truncated` stayed
  `false`, and an item missing from `effectiveDml` read exactly like an item whose property class
  did not resolve. The result is now `truncated`, and the hint says how many resolved items the map
  covers, from which item on the rest were left out, and how to read their properties instead.
- **The Claude Code plugin can set the `.pll` converter command.** 0.11.0 added `--compile-command`
  to the CLI, the environment, the `.mcpb` bundle and the registry listing, but not to the plugin,
  so a plugin install whose `convert_command` is built on `frmf2xml` still failed on every library.
  The plugin now has a `compile_command` option, passed as `OFMCP_COMPILE_COMMAND`; plugin version
  1.1.0.

## [0.11.0] - 2026-09-16

### Added
- **`get_block` answers what an insert or update actually writes.** Item and block DML properties
  (`DatabaseItem`, `InsertAllowed`, `UpdateAllowed`, `Enabled`, `InitializeValue`, the block's
  `WhereClause`/`OrderByClause`/key and lock modes, …) are indexed, and `verbosity="detailed"`
  serves both what the item itself wrote (`items[].dml`) and `effectiveDml` — the same properties
  with the item's **property class** applied, followed through a stub class into the module that
  defines it. In a real form that is where most of an item's behaviour lives, so an absent property
  on a classed item never meant the Forms default; now it is resolved instead of guessed, and an
  item appears in `effectiveDml` only when its whole chain could be read. Unresolved classes are
  accounted for in `propertyClasses`, with a hint naming the `fetch_module` call.
- **`get_block(columns=true)`** returns the block's data-source columns, the columns no item
  supplies, and which of those are **mandatory** — the columns an insert fails on unless a trigger
  assigns them. Read from the block's own XML on demand, not indexed: one form repeats the same
  wide base table across several blocks.
- **`search_modules` names the attached libraries it could not search.** A procedure a form calls
  but does not define is usually in a `.pll`, and an unfetched library is invisible to a search that
  otherwise reads as an answer.
- **`list_modules` says up front when a module type cannot be converted** — libraries under a
  Forms2XML-based `--convert-command`, where every `fetch_module` would fail — and names
  `--compile-command` instead of leaving it to be discovered one failure at a time.
- **PL/SQL libraries can have a converter command of their own: `--compile-command`** (also
  `OFMCP_COMPILE_COMMAND`, and a `compile_command` field in the `.mcpb` bundle). No Oracle tool
  converts a `.pll` to XML — `frmf2xml` rejects libraries — so a site that set `--convert-command`
  to a wrapper around `frmf2xml` lost every library, with "produced no output file" as the only
  clue. The new command runs for `.pll` modules only, ahead of `--convert-command`, `ORACLE_HOME`,
  and copy-mode; every other module type is unaffected, and **with it unset nothing changes**.
- **`{out}` in a converter command** is replaced with the absolute path the text form belongs at,
  for tools that ignore their working directory. `frmcmp` is one: given no `Output_File`, it writes
  the `.pld` next to the module, in the forms directory. `{out}` is never appended, so existing
  commands run exactly as before. When a run leaves nothing in the converted directory but a fresh
  file next to the module, the error now names that file and says to add `{out}`.

### Fixed
- **Results now stay inside what a client accepts.** `read_source` capped a page at 100,000 raw
  characters and `get_object_xml` at 500,000, both far above Claude Code's 25,000-token default —
  and converted XML, which is mostly quoted attributes, is the worst case for that gap. The ceilings
  are 40,000 characters counted as they travel (JSON-escaped), both tools declare that limit to the
  client, and a wide `get_block` is cut to fit rather than overflowing. Tool JSON is compact.
- **A cut result says where to continue.** `read_source` returns `nextStartLine`, the range it was
  clamped to, and a hint naming the next call — `truncated` alone was read past, and the following
  request then started past a hole. A line longer than one response is flagged (`lineCut`) instead
  of continuing as if it had been served whole; a cut `get_object_xml` fragment names the
  `read_source` range that continues it.
- **`list_triggers` reports a trigger with no code as zero lines**, not one, so an inherited body no
  longer reads as a one-line trigger.

### Changed
- The index version is now **2**: warm cache entries report `STALE` with `INDEX_OUTDATED` and
  `fetch_module` re-parses them without re-converting.

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

[Unreleased]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.12.0...HEAD
[0.12.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.10.0...v0.11.0
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
