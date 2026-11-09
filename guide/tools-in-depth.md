# Tools in depth

How to read what the [tools](../README.md#tools) return.

## Where an object's meaning lives

Two Forms properties carry more meaning than anything else in a module.

An item's **property class** is usually its role: Forms shops name their classes for what the
object does, so a push button is an *LOV* button and a text item is a *filter* field only because
of the class it inherits. `get_block` reports `propertyClass` on every item and block, confirmed
against the classes the module actually declares.

A window's **modality** decides how a form is read — a modal window is a dialog, so the code that
fills it and the code that consumes the result sit on opposite sides of one interaction.
`get_module_overview(verbosity: "detailed")` returns the window and canvas objects behind two of
its name lists: modality, size, toolbar canvases, and the window each canvas sits on.

A property Forms did not write comes back as `null`, meaning the object keeps the Forms default —
never `false`. On an item that has a property class it does not even mean the default: the class
supplies the value, and in a real form that is where most of an item's behaviour lives. So
`get_block(verbosity: "detailed")` returns both — `items[].dml`, what the item itself wrote, and
`effectiveDml`, the same properties with the class applied (item, then class, then whatever that
class is based on, followed into other modules that are already fetched). An item is listed in
`effectiveDml` only when that chain resolved to the end, so a `null` there really is the Forms
default. Every other item is a row of `unresolvedItems`, with the reason and — when fetching fixes
it — the module to fetch; `propertyClasses` and the result's `hint` give the same account per class
and name the `fetch_module` call. `items: [...]` narrows all of it to the items a question is about.

`get_block(columns: true)` adds the block's data-source columns, the columns no item supplies, and
which of those are mandatory in the database — the ones an insert fails on unless a trigger assigns
them.

A block's **master-detail relations** decide what it can be queried through, so `get_block` serves
them at every verbosity: `block.relations`, the relations it is the master of (join condition,
`deferred`, `autoQuery`, `deleteRecord`, `preventMasterlessOperations`), and `detailOf`, the
relations that name it as their detail, with the master each is written on. A detail with
`preventMasterlessOperations` cannot be queried except through its master — which the form enforces,
not the database, so a port has to enforce it itself.

## Reading around a result

Everything indexed is a line range into a file the cache owns. Every result that points at a file
carries a `source`:

```jsonc
"source": {
  "uri": "oracleforms://ORDERS.fmb/plsql/triggers/ORDERS.ORDER_ID.WHEN-VALIDATE-ITEM.sql",
  "file": "plsql/triggers/ORDERS.ORDER_ID.WHEN-VALIDATE-ITEM.sql",
  "startLine": 1, "endLine": 3
}
```

`read_source` takes either form back, so a search hit can be read in context and a truncated
fragment continued. The same files are readable as MCP resources —
`oracleforms://{module}/converted` for the converted text form and
`oracleforms://{module}/plsql/{category}/{name}` for one extracted block of PL/SQL — and every
such result also carries a `resource_link` content block, so a client can follow it without
parsing JSON. Reads are capped by lines *and* characters; a cut resource read says so in the text
it returns and names the call that continues it.

Paths stay cache-relative and URIs stay layout-independent: an absolute host path would mean
nothing to a client talking to the container image or over HTTP.

## Subclassed (inherited) objects

Forms lets a block, item, trigger or program unit be **subclassed** from another module, or copied
in with an **object group** (typically from an `.olb`). Either way the child stores only what it
overrides, so its PL/SQL body is genuinely empty in its own file while the code that runs lives in
the parent. Served naively that is not merely incomplete but wrong — an empty body reads as "this
button does nothing".

Every result that carries PL/SQL therefore also carries `bodySource`:

| `bodySource` | Meaning |
|---|---|
| `own` | The body is defined in this module (the ordinary case) |
| `inherited` | The object is subclassed; `text` is empty here and `inherited` names where the code is |
| `resolved` | The body shown is the parent's, followed for this call (`resolvedFrom` says from where) |
| `empty` | The object genuinely has no body |

The `inherited` reference is stated in the **parent's** vocabulary and in the shape the tools take,
so it is directly callable — `BAR_LIST.SELECT` in this form is `name: SELECT`, `ownerPath: BAR` over
there:

```jsonc
{
  "name": "WHEN-BUTTON-PRESSED", "block": "BAR_LIST", "item": "SELECT",
  "text": "", "bodySource": "inherited",
  "inherited": { "module": "TOOLBAR", "file": "toolbar.fmb", "name": "SELECT", "ownerPath": "BAR" },
  "hint": "… Call fetch_module(module=\"TOOLBAR.fmb\"), then get_trigger(module=\"TOOLBAR.fmb\", name=\"WHEN-BUTTON-PRESSED\", ownerPath=\"BAR.SELECT\")."
}
```

For an object-group member, `name` is the object's own name and `objectGroup` names the group that
carried it — where the library files that group is not recorded in this module, so no `ownerPath`
is claimed for it. A parent in the *same* module is a **property class**: it supplies properties
rather than a definition, hides nothing, and is deliberately not reported as inheritance.

`get_trigger` and `get_program_unit` take `resolve: true` to follow the pointer in one call. It
reads only modules that are **already cached** — reaching an un-cached one would mean converting
it, which a read-only tool must not do — and falls back to the pointer plus the `fetch_module`
hint rather than failing. `get_block` flags subclassed blocks and items the same way, and
`get_object_xml` returns the pointer resolved to the level it was asked about (Forms writes the
raw `ParentModule` attribute on the enclosing owner, so the fragment alone cannot answer it).

## Tracing across modules

A question about one form rarely stays inside it. A modal window is opened by whoever calls it, the
value it hands back travels through a `:GLOBAL`, and its toolbar is defined in a third form
entirely — so `search_source`, which searches the module you name, cannot answer any of the three:

```text
search_modules "PICKER"                                    → which forms call it
search_modules ":GLOBAL.picked_ref"                        → every module that writes or reads it
search_modules 'ParentFilename="toolbar.fmb"' scope=xml    → every module that subclasses it
```

Each hit names its module (`moduleSpec`, the `NAME.ext` string the other tools take), the
cache-relative file, the line, a snippet, and the `uri` that opens it. Matching is
case-insensitive by default, because Forms code writes the same module name as `PICKER`, `picker`
and `Call_Form('picker')` in the same code base; pass `ignoreCase: false` when precision matters.

Only modules that have been fetched are searched — reaching an un-fetched one would mean converting
it, which a read-only tool must not do. So the coverage is part of the answer rather than an
assumption:

```jsonc
{
  "query": ":GLOBAL.picked_ref",
  "cachedModules": 12, "scannedModules": 12, "skippedNotCached": 3090, "skippedStale": 1,
  "hint": "3090 matching module(s) are not cached and were not searched — list_modules(status=\"not_cached\") names them, and fetch_module adds one to the search. 1 cached module(s) changed on disk …"
}
```

To make a whole directory searchable, warm the cache before the session instead of one
`fetch_module` at a time: `--prefetch` runs the server once as a batch job that converts and
indexes every matching module, one line per module on stderr, then exits (with 1 if any failed).
Modules already current cost a fingerprint check, so a rerun picks up where a stopped one left off.

```
server --forms-dir /srv/forms --prefetch-all
server --forms-dir /srv/forms --prefetch ORDER --prefetch-type form
```

A module is current as long as its *file* is unchanged — which says nothing about whether it was
converted right. `frmf2xml` run without the forms directory on `FORMS_PATH`, for one, succeeds but
leaves out every inherited object, and that result is served until the module is saved again. After
fixing the conversion setup, convert again with `--force` (`--prefetch-all --force`), or one module
at a time with `fetch_module(module=…, force=true)`. Annotations are kept either way.

Both bounds are enforced and resumable: `maxResults` hits per page, and a ceiling on how many
modules one call reads — a query that matches nothing would otherwise read every converted file in
the cache before answering. Either one sets `truncated` and returns a `nextCursor` to pass back
with the same arguments. The cursor is bound to those arguments, so it cannot silently continue a
different search.

## Annotations

Meta-information the assistant records back **about** an element rather than reads *from* it —
semantic notes, tags, classifications, and cross-reference relations. It is persisted in a durable
store, kept separate from the derived index (not in the protocol `_meta` field), so it survives
`fetch_module` re-indexing and is served back to later sessions. Each entry carries its author and
is flagged `staleAgainstSource` when it predates the module's current source, so a note is never
silently dropped. The read tools (`get_module_overview`, `get_block`, `get_trigger`,
`get_program_unit`, `get_object_xml`) surface an element's annotations inline. The
[tools table](../README.md#tools) lists the annotation tools.
