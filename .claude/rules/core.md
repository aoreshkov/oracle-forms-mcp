---
paths:
  - "core/src/**/*.kt"
---

# Core parsing, cache & index gotchas

## Index and cache

- **Index JSON stays small.** PL/SQL bodies never get inlined into `ModuleIndex`; they live in
  `plsql` sidecars (or the `.pld` itself) and are referenced by 1-based inclusive `SourceRef`
  line ranges. Adding a field that carries source text is a design break.
- **`SourceRef` paths are cache-relative and layout-independent.** The converted text form is always
  addressed as `converted/<name>` even when `--converted-dir` has moved it out of the cache entry;
  `FormsService.resolveRef` maps that prefix to the configured directory and re-checks containment
  against whichever root it used. Never put an absolute path in a `SourceRef`, not even to make one
  resolvable, which is what `SourceLocation` URIs are for.
- **Cache entries are fingerprinted** (size+mtime, sha256-confirmed) against the file the
  pipeline consumed. Reads throw `ModuleStaleException` on mismatch — and its message is written
  for the model: it must name the tool call that fixes the situation (e.g. re-run `fetch_module`).
- **The index also carries the parser version that wrote it** (`ModuleIndex.indexVersion` vs
  `CURRENT_INDEX_VERSION`), because an upgrade changes no `.fmb` and would otherwise keep serving
  the old parser's answers out of a cache that looks current. Reads throw
  `ModuleIndexOutdatedException`, `list_modules` reports `STALE` with
  `staleReason = INDEX_OUTDATED`, and `fetch_module` heals it by **re-parsing the converted file
  already in the entry, never re-converting**, since the source is unchanged by definition. **Any
  change to what the parser writes — a new field, a different meaning for an existing one, a recovery
  like `decodeDoubleEscaped` — bumps `CURRENT_INDEX_VERSION` in the same commit.** Version `0` is
  reserved: it is what every entry written before the stamp existed deserializes to.
- **Annotations never go into `index.json`.** AI/user-supplied notes, tags, and relations belong
  to the `AnnotationStore` (its own root, keyed by a stable `ElementId` — module + kind + name +
  ownerPath, never `SourceRef` line ranges), decoupled from the derived cache so they survive
  re-fetch and cache eviction. A source-fingerprint mismatch is a `staleAgainstSource` drift flag on
  the served view, not a reason to delete the annotation.

## What the parser records

- **An empty body is never served as a fact.** A subclassed object stores only its overrides, so its
  `TriggerText` is empty here while the code that runs lives in the parent module. Every read that
  serves a body carries a `bodySource` (`own`/`inherited`/`resolved`/`empty`) and, when it is
  `inherited`, the `InheritanceRef` plus a hint naming the exact next call. `get_trigger` and
  `get_program_unit` take `resolve` to follow it, but only into **already-cached** modules:
  converting one would break `readOnlyHint`.
- **Subclassing pointers are reassembled, never invented.** Forms puts the cross-module pointer
  (`ParentModule`/`ParentFilename`/`ParentName`) on the *owner* and only `SubclassSubObject="true"`
  on each inherited child, so `inheritanceOf` threads it down the element stack and states the path
  in the *parent's* vocabulary (`BAR_LIST.SELECT` here is `name=SELECT, ownerPath=BAR` there), which
  makes it directly callable. Only the *immediately* enclosing element's pointer is followed: Forms
  marks every link of a chain, so a gap means the object in between is defined here, and continuing
  across it would fabricate a parent path. An object marked subclassed with no pointer above it
  still gets a bare `InheritanceRef`, because an empty body must never read as "there is no code".
- **The same `Parent*` attributes carry three unrelated relationships**, so `inheritanceOf`
  classifies before it records: a pointer into *another* module (`ParentFilename`, or a
  `ParentModule` naming another module; `ParentName` = the object's name there), an **object group**
  copy (`SubclassObjectGroup="true"` — the object keeps its own name, `ParentName` is the group, so
  it goes in `objectGroup`, and no owner path in the library may be asserted), and a **property
  class** in this same module (`ParentModule` = this module), which supplies properties, hides
  nothing, and must stay out of `InheritanceRef`. Getting this wrong is not a cosmetic slip: it emits
  pointers that address the wrong kind of object at paths that do not exist, on the majority of items
  in a typical form.
- **Never report a Forms default as a value.** Forms2XML writes a property only where it is
  overridden, so `Visible`/`Required`/`Modal`/`RaiseOnEnter` and friends are nullable and absence
  means "not overridden". Reading a missing `Visible` as `false` invents a fact about the item.
- **Don't hard-code `ParentType`.** Its numbering is version-dependent and the XML defines it
  nowhere. Where the parser needs to know what kind of parent an object has, it asks the document:
  `propertyClass` is confirmed against the declared `PropertyClass` elements in a pass at the end
  of `parse`, because those are written after the objects that use them.
- **Data-source columns are read, not indexed.** One form repeats the same wide base table across
  several blocks — thousands of `DataSourceColumn` elements, hundreds of KB — so `ModuleIndex` keeps
  only `BlockInfo.dataSourceColumnCount` and `get_block(columns=true)` reads them from the block's
  own XML slice through `DataSourceColumnReader`. An item supplies a column by `ColumnName` with any
  table alias dropped (a block over an inline subquery writes `S.OWNER` for the column `OWNER`), or
  by its own name; `mandatoryColumnsWithoutItem` is the part an insert fails on.
- **A relation is stored once, on its master.** Forms2XML writes `Relation` inside the master
  `Block`, so that is the only place `BlockInfo.relations` holds it; `get_block`'s `detailOf` (the
  relations naming a block as detail) is found at serve time, never indexed a second time. Relations
  are structure, not description: served at every verbosity, and spent through the result budget
  ahead of the items.

## Reading the XML

- **Text recovery is guarded and flagged, never silent.** A converter that writes `&amp;#10;` leaves
  the whole body on one physical line, which makes `lineCount` 1 for a whole procedure and collapses
  every `SourceRef` onto that line. `decodeDoubleEscaped` undoes it only when the body holds no real
  line break *and* decoding introduces one, and the result carries `TextEncoding.RECOVERED`. It
  cannot be proven — a body that really contained `&#10;` in a literal looks identical — so it must
  stay visible to whoever reads the text.
- **The XML parser never fails on unknown vocabulary.** Forms XML is huge and version-dependent;
  unknown elements are skipped generically, but a named element still gets an `ObjectRef`. Do not
  add hard failures for unexpected tags.
- **StAX reports an event's end location.** `FormsXmlParser` derives element start lines from the
  previous event's end (`startLineOf`); pinned by `objectRefSlicesReparseAsXml`. Keep that
  derivation if you touch line tracking.
