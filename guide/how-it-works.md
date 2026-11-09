# How it works

What happens between a module on disk and a tool result. For the list of tools, see the
[README](../README.md#tools).

1. `list_modules` scans the configured `--forms-dir` (non-recursive) and reports each module's
   cache status: `NOT_CACHED`, `CACHED`, `STALE`, or `SOURCE_MISSING`. A `STALE` row's
   `staleReason` says why: the source changed on disk (`SOURCE_CHANGED`), which a re-fetch
   re-converts, or an older build of this server wrote the index (`INDEX_OUTDATED`), which a
   re-fetch only re-parses.
   A production forms directory holds thousands of modules, so the answer is filtered and paged:
   narrow with `pattern`/`type`/`status`, follow `nextCursor` for the rest, and read
   `countsByStatus` for the shape of the whole match.
2. `fetch_module` produces the module's text form in the cache and indexes it:
   - **`ORACLE_HOME` set** — binaries are converted with the Oracle tools in
     `%ORACLE_HOME%\bin` (then `$FORMS_INSTANCE/bin`, where Linux Forms 12c+ keeps
     `frmf2xml.sh`): `frmf2xml` for `.fmb`/`.mmb`/`.olb` (XML), `frmcmp_batch`
     (`Module_Type=LIBRARY Script=YES`) for `.pll` (a `.pld` text dump).
   - **`ORACLE_HOME` not set** — pre-converted files are expected next to the modules
     (`orders_fmb.xml`, `dupes_fmb.xml`, `picker_fmb.xml`, `toolbar_fmb.xml`,
     `mainmenu_mmb.xml`, `objects_olb.xml`, `utils.pld`) and copied into the cache.
3. A single StAX pass parses the XML into a structured index (blocks with items, triggers with
   decoded PL/SQL, program units, LOVs, record groups, windows, canvases, …). PL/SQL bodies are
   extracted to `.sql` sidecar files; every named XML element gets a line-range reference so
   `get_object_xml` can slice it back out of the converted file.
4. The other tools read the cached index. Caching is fingerprint-based (size + mtime + sha256 of
   the source file): editing a module marks it `STALE` and read tools ask for a re-fetch.
5. `annotate_element` and `relate_elements` let the assistant write durable meta-information back
   about individual elements (notes, tags, summaries, classifications, cross-references). This is
   kept in a **separate** store — not the derived index — so it survives re-fetching, and the read
   tools surface it inline. An annotation made before a source change is flagged, never dropped.

## Cache

`%LOCALAPPDATA%\oracle-forms-mcp` (Windows), `~/Library/Caches/oracle-forms-mcp` (macOS),
`$XDG_CACHE_HOME/oracle-forms-mcp` (Linux). One directory per module:

```
ORDERS.fmb/
  converted/orders_fmb.xml      converted (or copied) text form
  plsql/triggers/*.sql          decoded trigger bodies
  plsql/program-units/*.sql     decoded program units
  plsql/menu-items/*.sql        menu-item command bodies (menu modules)
  index.json                    the structured index
```

Safe to delete at any time; modules are simply re-fetched. With
[`--converted-dir`](converters.md#keeping-the-converted-xml) the `converted/` file is written to the directory
you name instead and the rest of the entry stays here.

Annotations are **not** part of this derived cache. They live in a separate `annotations/` store
(one `NAME.ext.json` per module, defaulting to `<cache dir>/annotations`, overridable with
`--annotations-dir`), so deleting a module's cache entry — or re-fetching it — leaves the notes,
tags, and relations you recorded intact.
