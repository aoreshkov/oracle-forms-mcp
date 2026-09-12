---
name: trace-form
description: Trace how an Oracle Forms screen actually works — which form opens a modal, where a button's code really lives when its body looks empty, what a :GLOBAL carries back into a field. Use when asked how a Forms form, block, item, button, modal window or LOV behaves, where a value comes from, what calls what, or when a trigger comes back with no body.
---

# Trace a Forms interaction

Tracing a Forms application is following one value across objects that hide where they are defined.
The `oracle-forms` MCP server answers every step of that; this is the order to ask in, and the four
places where a naive reading is not merely incomplete but **wrong**.

Tool names below are the plain ones. Through this plugin they are namespaced —
`mcp__plugin_oracle-forms_oracle-forms__get_block` — which is the form to use in permission rules,
a subagent's `tools` list, or a hook matcher.

## Read through the tools, not the files

The converted XML and the extracted PL/SQL live in a cache the server owns and fingerprints against
the `.fmb`. Opening those files directly — `grep`, `sed`, a directory walk — reads text that may
describe a form that is no longer the one being served; that drift is exactly what a `STALE` status
reports. The paths in a result are cache-relative by design, so they are not host paths you could
open anyway.

A `STALE` row's `staleReason` says which kind of drift it is: `SOURCE_CHANGED` (the `.fmb` moved on)
or `INDEX_OUTDATED` (an older build of the server indexed it — its answers can be wrong in ways
that read as plausible, such as an inherited trigger reported empty). Both are healed by
`fetch_module`; the second re-parses the converted file and runs no conversion. After upgrading the
server, `list_modules(status="stale")` is the one call worth making before trusting a warm cache.

Every reflex has a call:

| Reflex | Call instead |
|---|---|
| `ls`/`find` the forms directory | `list_modules` with `pattern`, `type`, `status` (filtered and paged) |
| `grep` a module for an item, prompt or property | `get_block` (`verbosity: "detailed"` for data type, column, canvas, LOV) |
| `grep` across the whole directory | `search_modules` (every fetched module at once) |
| `sed -n '320,340p'` on the XML | `get_object_xml` for one named object, `read_source` for a line range |
| open the sidecar a hit named | `read_source` with the hit's `uri` |

## The order to ask in

1. **`list_modules`** — find the module. Filter; never page through thousands of rows.
2. **`fetch_module`** — converts and indexes it. Idempotent, and every other tool needs it first.
3. **`get_module_overview`** with `verbosity: "detailed"` — the inventory, plus the window and
   canvas objects: **modality**, sizes, toolbar canvases, and which window hosts which canvas.
4. **`get_block`** — items with type, **property class**, prompt, trigger names, and subclassing
   pointers. This is where a screen's fields become legible.
5. **`list_triggers`** → **`get_trigger`**, **`list_program_units`** → **`get_program_unit`** — the
   PL/SQL. `list_triggers` filters by block, item or level, which is how same-named triggers at
   different levels are told apart.
6. **`search_source`** inside one module; **`search_modules`** when the question leaves it.
7. **`read_source`** — the lines around anything a result pointed at, by its `uri`.

## Four things that mislead if you skip them

**An empty PL/SQL body is not "this does nothing."** A subclassed object stores only its overrides,
so its body is genuinely empty *here* while the code that runs lives in the parent module. Check
`bodySource` on every body you read: `own`, `inherited`, `resolved`, `empty`. When it is
`inherited`, the `inherited` pointer names the parent module and the object's name and `ownerPath`
*there*, and `hint` names the exact next call. `get_trigger`/`get_program_unit` take `resolve: true`
to follow it, but only into a module that is **already fetched** — so fetch the parent first.

**Modality lives on the Window, not on the block or canvas.** "How does this modal work" starts at
`get_module_overview(verbosity: "detailed")`: a modal window is a dialog, which means the code that
fills it and the code that consumes its result sit on opposite sides of one interaction, usually in
different modules.

**An item's property class is usually its role.** Forms shops name classes for what the object does,
so a push button is an *LOV* button and a text item is a *filter* field only because of the class it
inherits. `get_block` reports `propertyClass` on items and blocks; without it every button looks
alike.

**A property Forms did not write is `null`, not `false`.** Forms2XML emits a property only where it
differs from the default, so `visible`, `required`, `modal`, `raiseOnEnter` and friends are nullable:
absence means "not overridden", never "off".

## Questions that leave the module

`search_modules` searches every **fetched** module in one call. Three shapes cover most of tracing:

```text
search_modules "ORDERS"                                   → which forms call this one
search_modules ":GLOBAL.picked_ref"                       → who writes and who reads a global
search_modules 'ParentFilename="toolbar.fmb"' scope=xml   → who subclasses a shared block
```

Matching is case-insensitive by default, because the same module is written `PICKER`, `picker` and
`Call_Form('picker')` in one code base. Read the coverage fields before trusting an empty answer:
`cachedModules`, `scannedModules`, `skippedNotCached`, `skippedStale`. Only fetched modules are
searched, so a thin result may mean "not fetched yet" — the `hint` names the `fetch_module` call
that widens the search. When `truncated` is set, pass the returned `nextCursor` back with the same
arguments.

## When `get_object_xml` is the right call

It is the escape hatch, and it is legitimate when:

- a property no tool models yet decides the answer (it returns the raw attributes);
- you are confirming an attribute a result summarised;
- the object is a vendor- or version-specific element the parser passed through generically.

It is the wrong call for item prompts and properties (`get_block`), trigger bodies (`get_trigger`),
finding anything (`search_source`/`search_modules`), or reading a range of a file (`read_source`).
Fragments are capped and say so when cut; a subclassed object's fragment also carries the
inheritance pointer, because Forms writes the parent attributes on the *enclosing* owner and the
fragment alone could not answer it.

## A worked trace

Against the repository's own `sample-forms` directory — *how does the value picked in the modal get
into the field on the calling form?*

1. `fetch_module ENTRY.fmb`
2. `get_trigger ENTRY.fmb KEY-HELP block=ASSIGNMENTS item=AGENT_REF` → the body calls
   `call_form('picker')`
3. `fetch_module picker` — the name out of the PL/SQL is a usable module spec
4. `get_module_overview PICKER.fmb verbosity=detailed` → `WIN_PICKER` is `modal: true`, toolbar canvas
   `BAR_LIST`
5. `get_block PICKER.fmb block=BAR_LIST` → the block is subclassed from `TOOLBAR`; item `SELECT`
   carries `ownerPath: BAR`
6. `get_trigger PICKER.fmb WHEN-BUTTON-PRESSED block=BAR_LIST item=SELECT` → empty body,
   `bodySource: inherited`, hint names `fetch_module(module="TOOLBAR.fmb")`
7. `fetch_module TOOLBAR.fmb`, then the same `get_trigger` with `resolve: true` → the real body,
   `Do_Key('HELP')`
8. `list_triggers PICKER.fmb` → `KEY-HELP` at block level on `CUSTOMERS`; `get_trigger` it → it
   writes `:GLOBAL.picked_name` / `:GLOBAL.picked_ref` and exits the form
9. `search_modules ":GLOBAL.picked_ref"` → the calling form reads it back into
   `:ASSIGNMENTS.AGENT_REF`
10. `read_source` with that hit's `uri` → the line in context

Not one step needed a file. If a trace of your own seems to, say what was missing rather than
reaching for the shell — that is a gap in the server, and it is the kind that gets fixed.

## Record what you worked out

A trace is expensive and the conclusions are not in the `.fmb`. `annotate_element` stores a note,
tag, summary or classification on one element; `relate_elements` records a directed cross-reference
(this trigger *calls* that program unit; this button *opens* that form). Both survive re-indexing
and cache eviction, and the read tools surface them inline the next time anyone looks.
