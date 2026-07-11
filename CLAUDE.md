# CLAUDE.md

MCP server serving Oracle Forms module content (.fmb/.mmb/.pll/.olb) from a `--forms-dir`.
A KMP core of pure models + ports, with a JVM MCP server of declarative tool adapters over one
`FormsService`.

## Layout

- `core/` — KMP library. `commonMain`: pure `@Serializable` models (`ModuleIndex`, `ModuleKey`,
  `SourceRef`, …), DTOs (`dto/ToolResults.kt`), and ports (`ModuleConverter`, `ModuleParser`,
  `ModuleCache`, `FormsDirectoryScanner`). `jvmMain`: implementations — `OnDiskModuleCache`,
  `FormsDirectoryScannerImpl`, converters (`convert/`), parsers (`parse/`).
- `server/` — JVM MCP app. `FormsService` is the single logic layer; tool files in `tools/` are
  declarative adapters (parse args → service call → `toolResult(dto)`). Transports in
  `transport/`, resources/prompts in their packages, composition root `McpServerFactory`.
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
