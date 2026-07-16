# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Known limitations
- Menu-level triggers carry no menu owner in the index, so two same-named triggers in different
  menus of one `.mmb` cannot be told apart yet (the ambiguity is reported, not misresolved).
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

[Unreleased]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/aoreshkov/oracle-forms-mcp/releases/tag/v0.1.0
