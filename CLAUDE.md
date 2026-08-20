# CLAUDE.md

MCP server serving Oracle Forms module content (.fmb/.mmb/.pll/.olb) from a `--forms-dir`.
A KMP core of pure models + ports, with a JVM MCP server of declarative tool adapters over one
`FormsService`.

## Layout

- `core/` — KMP library. `commonMain`: pure `@Serializable` models (`ModuleIndex`, `ModuleKey`,
  `SourceRef`, plus the annotation layer `ElementId`, `Annotation`/`Relation`/`ModuleAnnotations`),
  DTOs (`dto/ToolResults.kt`), and ports (`ModuleConverter`, `ModuleParser`, `ModuleCache`,
  `AnnotationStore`, `FormsDirectoryScanner`). `jvmMain`: implementations — `OnDiskModuleCache`,
  `OnDiskAnnotationStore`, `FormsDirectoryScannerImpl`, converters (`convert/`), parsers (`parse/`).
- `server/` — JVM MCP app. `FormsService` is the single logic layer; tool files in `tools/` are
  declarative adapters (parse args → service call → `toolResult(dto)`). The read tools plus the
  annotation tools (`annotate_element`, `relate_elements`, `get_element_annotations`,
  `search_annotations`, `remove_annotation`) and the `oracleforms://{module}/annotations` resource
  live here. Transports in `transport/`, resources/prompts in their packages, composition root
  `McpServerFactory`.
- `build-logic/` — convention plugins `kmp-library` (toolchain 21, explicitApi, kover, KGP ABI validation) and
  `jvm-application`.
- Distribution — three channels, all cut by `.github/workflows/release.yml` from a `v*` tag: the
  GHCR image, the release zip (plus its `.zip.sha256`), and an `.mcpb` desktop bundle
  (`server/mcpb/manifest.template.json` → `packageMcpb`). `server.json` is the MCP Registry
  listing; the publish job rewrites its version, icon tag, and appends the `mcpb` package with the
  bundle's sha256.
- `plugins/oracle-forms/` + `.claude-plugin/marketplace.json` — the Claude Code plugin channel,
  served straight from the repo by `/plugin marketplace add aoreshkov/oracle-forms-mcp`. The plugin
  carries no jars: `.mcp.json` runs `launcher/OracleFormsMcpLauncher.java` in Java single-file
  source mode (hence **JDK** 21+, not JRE), which downloads the release zip, verifies its published
  sha256, unpacks it under `${CLAUDE_PLUGIN_DATA}`, and loads it in-process. The
  `claude-code-plugin` CI job validates the manifests and compiles the launcher — nothing else
  stands between a commit on main and a user's install.

## Invariants

- **stdout is the stdio protocol channel.** All logging goes Kermit → SLF4J → Logback → stderr
  (`routeKermitToSlf4j()` runs before the SDK creates any logger; `logback.xml` targets stderr).
- **Index JSON stays small.** PL/SQL bodies live in `plsql/**` sidecars (or the `.pld` itself),
  referenced by 1-based inclusive `SourceRef` line ranges. Never inline code into `ModuleIndex`.
- **`SourceRef` paths are cache-relative and layout-independent.** The converted text form is
  always addressed as `converted/<name>` even when `--converted-dir` has moved it out of the cache
  entry; `FormsService.resolveRef` maps that prefix to the configured directory and re-checks
  containment against whichever root it used. Never put an absolute path in a `SourceRef`.
- **Cache entries are fingerprinted** (size+mtime, sha256-confirmed) against the file the
  pipeline consumed; reads throw `ModuleStaleException` on mismatch. Exception messages are
  written for the model — they must say which tool call fixes the situation.
- **Annotations are asserted, not derived.** They live in `AnnotationStore` (own root, separate
  from the fingerprinted cache), keyed by a stable `ElementId` (module + kind + name + ownerPath —
  never `SourceRef` line ranges), so they survive re-fetch and cache eviction. A source-fingerprint
  mismatch is a `staleAgainstSource` drift flag on the served view, never a delete. Never inline
  annotations into `ModuleIndex`.
- **The XML parser never fails on unknown vocabulary.** Forms XML is huge and version-dependent;
  unknown elements are skipped generically (but still get an `ObjectRef` when named).
- **Every tool** declares title, annotations, and `outputSchemaOf<Dto>()`; DTO fields are
  defaulted so schemas stay forward-compatible. `ToolRegistrationTest` enforces this.
- **Exactly two files carry the version by hand**: `gradle.properties` and `server.json` (the
  release tag guard checks both). Everything else derives it — the MCP `Implementation` version
  via `generateVersionResource`, the MCPB manifest via the `@version@` token in
  `server/mcpb/manifest.template.json`. Never add a third. The Claude Code plugin's `version` is
  **not** an exception: it versions the plugin's own files (manifest + launcher), which change on
  their own schedule, and the server build it runs is resolved at runtime — never bump it as part
  of a release.
- **Tool handlers run concurrently.** Since MCP SDK 0.15 the server dispatches inbound requests in
  parallel once the handshake is done (`ServerOptions.handlerCoroutineContext`, default
  `Dispatchers.Default`, bounded internally, no opt-out). Anything reachable from a tool must be
  safe for that: `FormsService.fetchModule` holds a per-`ModuleKey` `Mutex`,
  `OnDiskModuleCache.putIndex` writes-then-renames, `OnDiskAnnotationStore` serialises on
  `writeMutex`, and `addModuleIndexResource` tolerates a lost registration race. Lock order is
  always per-module fetch lock → `sharedOutputLock`, never the reverse.
- Public API changes require `gradlew updateKotlinAbi` (KGP ABI validation on `core`).

## Gotchas

- Kotlin nests block comments: a glob like `plsql/**` or `*.sql` after a `/` inside KDoc opens
  an unclosed nested comment. Spell paths without `/*` sequences in doc comments.
- StAX reports an event's **end** location; `FormsXmlParser` derives element start lines from
  the previous event's end (see `startLineOf`). Pinned by `objectRefSlicesReparseAsXml`.
- Oracle tools: `frmf2xml` writes to the process **cwd** (run it with cwd = cache `converted/`);
  exit codes are unreliable — success is judged by the output file. `frmcmp_batch` over `frmcmp`.
  `--convert-command` swaps in a site-supplied converter on the same cwd convention
  (`CustomCommandModuleConverter`); precedence is command → `ORACLE_HOME` → copy-mode. It is a
  **whole command line**, not an executable: `ConvertCommandSpec` splits it into argv (quoted
  string with literal backslashes, or a JSON array) and substitutes the module path for `{}`,
  appending it when `{}` is absent. A value naming an existing file is taken whole — that is what
  keeps unquoted `C:\Program Files\…` configs working, so keep that check ahead of tokenizing. The
  command is **operator config only** — never reachable from a tool argument — and is spawned with
  an argv list, never a shell. All four channels pass it as one string (flag, env var, two
  `user_config` text fields), so the split has to live in the server. `--converted-dir` **is** that
  cwd: conversion runs directly in `FormsService.convertedDirOf(key)` and
  `canonicalizeConverted` only renames the result to `ModuleKey.convertedFileName` in place. What keeps
  one shared directory unambiguous is `ConversionOutput.canonical` (tried before the newest-matching
  heuristic) plus `FormsService.sharedOutputLock`, which serialises conversions whenever
  `--converted-dir` is set. Both options also read `OFMCP_CONVERT_COMMAND` /
  `OFMCP_CONVERTED_DIR` (flag wins), and `Main.configured()` treats blank *and* an unsubstituted
  `${user_config.…}` template as unset — that is how the MCPB/plugin channels pass "not set". Shared output/exit-code handling lives in `ConversionSupport.kt`: an output
  file older than `startedAt` (minus 2s FAT slack) is rejected as a leftover, so a converter that
  copies with preserved mtimes (`copy`, `cp -p`) reads as having produced nothing.
- This project deliberately does NOT add a custom `SegmentTemplateMatcher` (the SDK default
  matcher works); see `.claude/rules/server.md` for the shadowing cause and the
  `ModuleResourcesTest.sdkDefaultMatcherExtractsTheModuleSegment` regression canary.
- Tests never require an Oracle installation: converter tests build a fake `ORACLE_HOME` with
  stub `.bat`/sh scripts (`FakeOracleHome`); the copy-mode pipeline is covered by
  `FormsServiceIntegrationTest` against `fixtures/`. Classpath fixtures under `/fixtures/**`
  are not checked in per module — the build copies them from the canonical repo-root
  `sample-forms/` dir (also the demo dir for `--forms-dir`).

## Commands

```
gradlew build                 # everything, incl. tests
gradlew :core:jvmTest         # core tests only
gradlew updateKotlinAbi       # refresh core/api/*.api after public API changes
gradlew :server:installDist   # launcher at server/build/install/server/bin/server(.bat)
gradlew :server:packageMcpb   # .mcpb desktop bundle at server/build/mcpb/
gradlew :server:run --args="--forms-dir sample-forms"
java scripts/icon/GenerateIcon.java   # re-render assets/icon-512.png after editing assets/icon.svg

claude plugin validate . --strict                        # the marketplace manifest
claude plugin validate ./plugins/oracle-forms --strict   # the Claude Code plugin manifest
```

Test the plugin end to end without installing it — `--plugin-dir` loads it straight from the
worktree, and `OFMCP_SERVER_HOME` makes the launcher use a local `installDist` tree instead of
downloading a release:

```
claude --plugin-dir ./plugins/oracle-forms
```

## Claude Code setup

The committed `.claude/` config is shareable (public repo); only `settings.local.json`,
`plans/`, and `CLAUDE.local.md` are gitignored.

- **Stop hook** (`.claude/hooks/stop-verify.sh`) runs `:server:compileKotlin` before a turn
  ends, but only when `.kt/.kts` changed — a fast compile gate. Bypass a known-good stop via
  `/hooks`.
- **Path-scoped rules** auto-load when you edit matching sources: `.claude/rules/core.md`
  (`core/src/**`) and `.claude/rules/server.md` (`server/src/**`).
- **Skills:** `/release <version>` (bump the two guarded files + changelog, pre-flight, tag),
  `/review-currency [focus]` (expert-panel currency audit → `docs/reviews/`).
- **Agents:** six read-only `*-currency` reviewers (the review-currency panel) plus
  `verify-build` (second-opinion Gradle build/test).
