---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring, serving and bounding

## Tools

Tool files in `tools/` are **declarative adapters**: parse args → call `FormsService` →
`toolResult(dto)`. No business logic in a tool file (or in `ToolSupport.kt`) — it belongs in
`FormsService`. Every tool declares a `title`, behavior annotations (read tools reuse the shared
`LOCAL_READ_ONLY` constant in `ToolSupport.kt`, annotation writers `ANNOTATION_WRITE` in
`AnnotateElementTool.kt`; `fetch_module` and `remove_annotation` declare their own hints), and
`outputSchemaOf<Dto>()`; `toolResult` returns JSON text **and**
matching `structuredContent`. DTO fields are defaulted so output schemas stay forward-compatible.
`ToolRegistrationTest` enforces all of this — a new tool that skips a piece fails that test.

Register with **`addCheckedTool`** (`tools/ToolArguments.kt`), not the SDK's `addTool`: it
validates the arguments against the tool's `inputSchema` (unknown, missing, out-of-enum — all in
one error) before the handler runs, and tallies `isError` results in `ToolErrorTally`. The SDK's
`ToolSchema` cannot say `additionalProperties: false`, so without it an argument a tool lacks is
silently dropped, and a new tool that bypasses the wrapper fails
`ToolRegistrationTest.everyToolRejectsAnArgumentItDoesNotHave`. The schema *is* the contract:
declare every argument the handler reads, or the check rejects it; conditional requirements (`uri`
*or* `file`) stay in the handler. Give write tools an `example` call.

**stdout is the stdio protocol channel.** Never `println`/write to stdout to debug — all logging
goes Kermit → SLF4J → Logback → stderr (`routeKermitToSlf4j()` runs before the SDK creates any
logger; `logback.xml` targets stderr). The command line is cli-kit's (`Main.kt`: one `CommandSpec`
drives parsing, help and the Environment list; `CliOptionsTest` runs it through `program.record`,
the help page is pinned in `golden/help-root.txt`, regenerated with `-Pgolden.update=true`). The kit
writes stdout only for `--help` and `--version`; a summary that belongs on stdout (`--prefetch`)
goes through `context.output`, never `println`.

**No forms directory is a state the server serves, not an exit.** A plugin host starts the server
for everyone who installed it, so `ServerConfig.formsDir` is nullable: the tool list stays the real
one, `registerAllTools` ends by making every tool registered in it answer
`FORMS_DIR_NOT_CONFIGURED` (`refuseTools`, ahead of the argument check), and the instructions lead
with it. Resources and the prompt reach `FormsService` directly, so its entry points call
`requireFormsDir()` — a new one reachable from them must too. Never answer with an empty list
instead: an agent reads that as "there are no Forms modules here". A prefetch, and a directory
given but missing, are still refused at startup. `forms_dir` is optional in the plugin and MCPB
manifests for the same reason, and the launcher must not refuse an unset directory on its own.

## References and resources

**A ref a caller cannot open is half a fact.** Every result that names a file carries a
`SourceLocation` (`uri` + cache-relative `file` + line range), passed to `toolResult(dto, dto.source)`,
which attaches the `resource_link` block, and `read_source` takes either form back. URIs are built
in `resources/SourceUris.kt` — kept apart from `ModuleResources.kt` so `FormsService` and the
resource handlers can both use it without depending on each other. They stay layout-independent like
the refs they mirror: an absolute host path would mean nothing under the GHCR image or the HTTP
transport. Never mint a URI for a ref shape no resource covers: an omitted field is honest, a dead
link is not.

Sidecars are reachable by **template** (`oracleforms://{module}/plsql/{category}/{name}`), never one
registered resource per file: a form has hundreds, and `resources/list` has no cursor in the SDK.
Resource reads are capped like everything else, with the cut stated *inside* the returned text — a
resource read carries bytes and nothing else, so a silent truncation is indistinguishable from a
short file.

The location therefore appears twice — inside the JSON and again as the link block — and that is
**deliberate, not an oversight to tidy away** (it has been filed as redundancy twice now). The spec
blesses exactly this (*"a tool MAY return links to resources"*), and which of the two a client puts
in front of the model is the client's choice, not ours: the roadmap names "a `tools/call` response
can carry the same output in more than one form… no way to know which form a given client will put
in front of the model" as a protocol-level gap to be standardised. Drop the link when that contract
lands, not before.

**Resource templates gotcha (the inverse of the usual one):** this project deliberately does NOT
add a custom `SegmentTemplateMatcher`. The SDK's default matcher works here and must keep working;
`ModuleResourcesTest.sdkDefaultMatcherExtractsTheModuleSegment` is the regression canary. If it dies
with `NoSuchMethodError`, a newly added dependency has reintroduced the shadowed
`kotlinx.collections.immutable` — fix the dependency, do not add the workaround matcher.

## Properties resolved at serve time

**On a classed object, absence is not the default either — the class decides it.** In a real form
most items write almost no DML properties of their own, and the class they name is usually a *stub*
pointing at a shared module. So the two are served separately and never merged in the index:
`ItemInfo.dml`/`BlockInfo.dml` are what the object wrote, and `get_block`'s `effectiveDml` is item →
class → the class that one is based on, resolved at serve time. Resolution follows a pointer only
into an **already-cached, current** module (`readOnlyHint`), and an item appears in `effectiveDml`
only when its whole chain resolved — that is what makes a `null` there mean the Forms default.
Everything unresolved is counted and named — per item in `unresolvedItems` (beside the map, never as
`null`s inside it), per class in `propertyClasses`, and in the `hint` — never approximated.

**The item's *size* is one of those properties, and the rule holds for it too.** A classed item in a
real form writes a `Width` and no `Height` at all, so `ItemInfo.width`/`height` alone report every
such item as having no height — an absence indistinguishable from one nobody set.
`PropertyClassInfo.geometry` records what a class supplies and `get_block`'s `effectiveGeometry`
resolves the same chain as `effectiveDml`, with the same contract: present only when the chain
resolved, absent (and named in `unresolvedItems`) when it did not. An item with no size anywhere in
its chain is left out rather than served as a row of `null`s.

## Bounding results

**Every list-shaped result is bounded and says so.** Clients cap tool output (Claude Code at 25k
tokens), and a real forms directory holds thousands of modules, so no tool may return an unbounded
collection: `list_modules` filters (`pattern`/`type`/`status`) then pages (`limit`/opaque `cursor` →
`nextCursor`), and the per-module lists cap rows with a `truncated` flag beside an honest `total`
(see `MAX_LIST_ROWS`/`MAX_OVERVIEW_NAMES` next to `MAX_OBJECT_XML_CHARS`). Paging vocabulary is
fixed: `offset`/`nextOffset` where page N is cheap to recompute (`search_source`), an opaque `cursor`
where it is not.

**A size ceiling is counted the way the result travels.** `read_source` and `get_object_xml` cap on
**JSON-escaped** characters (`ResultBudget.kt`), because converted XML is mostly quotes and a budget
counted on raw text overshoots exactly where responses are largest; both sit inside
`MAX_RESULT_CHARS`, which they declare to the client as `_meta["anthropic/maxResultSizeChars"]` so
its limit for them is the server's own number rather than a token estimate. Declare that key **only**
on a tool the server caps below it — on an uncapped tool it lowers the client's limit instead of
raising it. A cut result says where to continue (`nextStartLine` plus a `hint` naming the call),
because `truncated` alone gets read past and the next call then starts past a hole.

**A cross-module scan is bounded by modules too, not only by rows.** `search_modules` reads at most
`MAX_MODULES_PER_SEARCH` cache entries per call, because a query that matches nothing has no row cap
to stop it and would read every converted file in the cache. Whichever bound stops the scan, the
result is `truncated` with a keyset `cursor` (module + hits already served from it) that is
fingerprinted against the query/scope/pattern it was minted for. Coverage is reported, never assumed:
un-fetched and stale modules are counted and named in the hint rather than being silently absent — a
search that reached a tenth of the directory must not read like one that found nothing. Never search
an un-cached module by converting it; `readOnlyHint` says we do not.

**`resources/list` stays bounded too.** It is a call the *client* issues on its own and the SDK
answers without a cursor, so per-module index resources are capped at the 50 most recently fetched
(`ModuleIndexResources`) and no startup snapshot of the cache is registered. Every cached module
stays reachable through `registerModuleIndexTemplate`; never re-add a registration per cache entry.

## Concurrency and sessions

**Tool handlers run concurrently** (MCP SDK 0.15+): once the handshake is done the server dispatches
inbound requests in parallel (`ServerOptions.handlerCoroutineContext`, default `Dispatchers.Default`,
bounded internally, no opt-out), so two tool calls can be inside `FormsService` at the same time.
Anything reachable from a tool that mutates shared state needs its own serialisation — `fetchModule`
uses a per-`ModuleKey` `Mutex` (taken before `sharedOutputLock`, never after),
`OnDiskModuleCache.putIndex` writes-then-renames, the annotation store uses `writeMutex`.
Registration is once-only: `addTool`/`addPrompt`/`addResource`/`addResourceTemplate` throw
`IllegalArgumentException` on a duplicate name instead of silently replacing, which is why
`addModuleIndexResource` both checks `uri in resources` and swallows that exception.

**Nothing is keyed on the MCP session.** Since SDK 0.16, HTTP sessions expire after 30 min idle
(`SESSION_IDLE_TIMEOUT` in `HttpTransport.kt`; an in-flight call holds one, an open `GET` does not),
and the 2026-07-28 spec revision drops sessions altogether. All state lives in `FormsService`, the
cache and the annotation store, keyed by module or element; paging is opaque cursors/offsets. The
only session-bound features — `list_changed` notifications and the log forwarder — stay isolated in
`ModuleIndexResources` and `Logging.kt`.
