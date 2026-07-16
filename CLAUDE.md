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
- `build-logic/` — convention plugins `kmp-library` (toolchain 21, explicitApi, kover, BCV) and
  `jvm-application`.

## Invariants

- **stdout is the stdio protocol channel.** All logging goes Kermit → SLF4J → Logback → stderr
  (`routeKermitToSlf4j()` runs before the SDK creates any logger; `logback.xml` targets stderr).
- **Index JSON stays small.** PL/SQL bodies live in `plsql/**` sidecars (or the `.pld` itself),
  referenced by 1-based inclusive `SourceRef` line ranges. Never inline code into `ModuleIndex`.
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
- Public API changes require `gradlew apiDump` (binary-compatibility-validator on `core`).

## Gotchas

- Kotlin nests block comments: a glob like `plsql/**` or `*.sql` after a `/` inside KDoc opens
  an unclosed nested comment. Spell paths without `/*` sequences in doc comments.
- StAX reports an event's **end** location; `FormsXmlParser` derives element start lines from
  the previous event's end (see `startLineOf`). Pinned by `objectRefSlicesReparseAsXml`.
- Oracle tools: `frmf2xml` writes to the process **cwd** (run it with cwd = cache `converted/`);
  exit codes are unreliable — success is judged by the output file. `frmcmp_batch` over `frmcmp`.
- This project deliberately does NOT add a custom `SegmentTemplateMatcher`: that workaround is
  only needed when `kotlin-compiler` shadows `kotlinx.collections.immutable`.
  `ModuleResourcesTest.sdkDefaultMatcherExtractsTheModuleSegment` is the regression canary — if
  it dies with NoSuchMethodError, a new dependency reintroduced the shadow.
- Tests never require an Oracle installation: converter tests build a fake `ORACLE_HOME` with
  stub `.bat`/sh scripts (`FakeOracleHome`); the copy-mode pipeline is covered by
  `FormsServiceIntegrationTest` against `fixtures/`. Classpath fixtures under `/fixtures/**`
  are not checked in per module — the build copies them from the canonical repo-root
  `sample-forms/` dir (also the demo dir for `--forms-dir`).

## Commands

```
gradlew build                 # everything, incl. tests
gradlew :core:jvmTest         # core tests only
gradlew apiDump               # refresh core/api/*.api after public API changes
gradlew :server:installDist   # launcher at server/build/install/server/bin/server(.bat)
gradlew :server:run --args="--forms-dir sample-forms"
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
