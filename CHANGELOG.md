# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Docker: the annotation store and module cache are now writable when a volume is mounted at
  `/home/mcp/.cache`. The image runs as non-root (uid 10001); the cache tree is pre-created and
  owned by that user before the `VOLUME` is declared, so an anonymous or named volume inherits the
  ownership instead of being created root-owned (which silently failed every annotation write with
  `AccessDenied`). README documents mounting the volume to persist annotations across runs, plus the
  bind-mount `chown` caveat.

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
