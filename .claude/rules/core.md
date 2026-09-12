---
paths:
  - "core/src/**/*.kt"
---

# Core parsing, cache & index gotchas

- **Index JSON stays small.** PL/SQL bodies never get inlined into `ModuleIndex`; they live in
  `plsql` sidecars (or the `.pld` itself) and are referenced by 1-based inclusive `SourceRef`
  line ranges. Adding a field that carries source text is a design break.
- **Cache entries are fingerprinted** (size+mtime, sha256-confirmed) against the file the
  pipeline consumed. Reads throw `ModuleStaleException` on mismatch — and its message is written
  for the model: it must name the tool call that fixes the situation (e.g. re-run `fetch_module`).
- **The index also carries the parser version that wrote it** (`ModuleIndex.indexVersion` vs
  `CURRENT_INDEX_VERSION`), because an upgrade changes no `.fmb` and would otherwise keep serving
  the old parser's answers out of a cache that looks current. **Any change to what the parser
  writes — a new field, a different meaning for an existing one, a recovery like
  `decodeDoubleEscaped` — bumps `CURRENT_INDEX_VERSION` in the same commit.** Version `0` is
  reserved: it is what every entry written before the stamp existed deserializes to.
- **Annotations never go into `index.json`.** AI/user-supplied notes, tags, and relations belong
  to the `AnnotationStore` (its own root, keyed by a stable `ElementId` — never `SourceRef` line
  ranges), decoupled from the derived cache so they survive re-fetch. A source-fingerprint mismatch
  is a `staleAgainstSource` drift flag on the served view, not a reason to delete the annotation.
- **Subclassing pointers are reassembled, never invented.** Forms puts the cross-module pointer
  (`ParentModule`/`ParentFilename`/`ParentName`) on the *owner* and only `SubclassSubObject="true"`
  on each inherited child, so `inheritanceOf` threads it down the element stack and translates the
  path into the parent's own names. Only the *immediately* enclosing element's pointer is
  followed: Forms marks every link of a chain, so a gap means the object in between is defined
  here, and continuing across it would fabricate a parent path. An object marked subclassed with
  no pointer above it still gets a bare `InheritanceRef` — the point of the field is that an empty
  body must never read as "there is no code".
- **The same `Parent*` attributes carry three unrelated relationships**, so `inheritanceOf`
  classifies before it records: a pointer into *another* module (`ParentName` = the object's name
  there), an **object group** copy (`SubclassObjectGroup="true"` — the object keeps its own name,
  `ParentName` is the group, and no owner path in the library may be asserted), and a **property
  class** in this same module (`ParentModule` = this module, no `ParentFilename`), which is not
  inheritance at all and must stay out of `InheritanceRef`. Getting this wrong is not a cosmetic
  slip: it emits pointers that address the wrong kind of object at paths that do not exist, on the
  majority of items in a typical form.
- **Never report a Forms default as a value.** Forms2XML writes a property only where it is
  overridden, so every such property is nullable and absence means "not overridden". Reading a
  missing `Visible` as `false` invents a fact about the item.
- **Don't hard-code `ParentType`.** Its numbering is version-dependent and the XML defines it
  nowhere. Where the parser needs to know what kind of parent an object has, it asks the document:
  `propertyClass` is confirmed against the declared `PropertyClass` elements in a pass at the end
  of `parse`, because those are written after the objects that use them.
- **Text recovery is guarded and flagged, never silent.** `decodeDoubleEscaped` undoes a
  doubly-escaped body only when it holds no real line break *and* decoding introduces one, and the
  result carries `TextEncoding.RECOVERED`. It cannot be proven — a body that really contained
  `&#10;` in a literal looks identical — so it must stay visible to whoever reads the text.
- **The XML parser never fails on unknown vocabulary.** Forms XML is huge and version-dependent;
  unknown elements are skipped generically, but a named element still gets an `ObjectRef`. Do not
  add hard failures for unexpected tags.
- **StAX reports an event's end location.** `FormsXmlParser` derives element start lines from the
  previous event's end (`startLineOf`); pinned by `objectRefSlicesReparseAsXml`. Keep that
  derivation if you touch line tracking.
- **Kotlin nests block comments.** A glob like a `plsql` path with wildcards, or a `*.sql` after a
  slash inside KDoc, opens an unclosed nested comment. Spell paths in doc comments without slash+star
  sequences.
- **Public API changes require `gradlew updateKotlinAbi`** — KGP-native ABI validation guards `core`.
