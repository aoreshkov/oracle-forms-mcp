# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/aoreshkov/oracle-forms-mcp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/oracle-forms-mcp/releases/tag/v0.1.0
