# CLAUDE.md

MCP server serving Oracle Forms module content (.fmb/.mmb/.pll/.olb) from a `--forms-dir`.
A KMP core of pure models + ports, with a JVM MCP server of declarative tool adapters over one
`FormsService`.

The detail behind everything here is in `.claude/rules/`, which loads when a matching file is
touched: `core.md`, `server.md`, `conversion.md`, `plugin.md`, `distribution.md`.

## Layout

- `core/` — KMP library. `commonMain`: pure `@Serializable` models (`ModuleIndex`, `ModuleKey`,
  `SourceRef`, the annotation layer), DTOs (`dto/ToolResults.kt`), and ports (`ModuleConverter`,
  `ModuleParser`, `ModuleCache`, `AnnotationStore`, `FormsDirectoryScanner`). `jvmMain`:
  implementations, with converters in `convert/` and parsers in `parse/`.
- `server/` — JVM MCP app. `FormsService` is the single logic layer; tool files in `tools/` are
  declarative adapters (parse args → service call → `toolResult(dto)`). Transports in `transport/`,
  resources and prompts in their packages, composition root `McpServerFactory`.
- `build-logic/` — convention plugins `kmp-library` (toolchain 25, explicitApi, kover, KGP ABI
  validation) and `jvm-application`.
- `plugins/oracle-forms/` + `.claude-plugin/marketplace.json` — the Claude Code plugin channel,
  served from the `stable` branch. `evals/` tests its skill.
- `server.json`, `server/mcpb/`, `.github/workflows/release.yml` — the release channels: GHCR image,
  release zip, `.mcpb` bundle, MCP Registry listing.

## Invariants

- **stdout is the stdio protocol channel.** All logging goes to stderr; never `println`.
- **Index JSON stays small.** Never inline PL/SQL into `ModuleIndex`; reference it by `SourceRef`.
- **`SourceRef` paths are cache-relative.** Never put an absolute path in one.
- **Bump `CURRENT_INDEX_VERSION` in the same commit** as any parser change that alters what is
  written or what a written field means.
- **Every tool declares title, annotations and `outputSchemaOf<Dto>()`, and registers with
  `addCheckedTool`,** never the SDK's bare `addTool`. `ToolRegistrationTest` enforces both.
- **Every list-shaped result is bounded and says so.** No tool may return an unbounded collection.
- **Exactly two files carry the version by hand:** `gradle.properties` and `server.json`.
- **Tool handlers run concurrently.** Anything reachable from a tool must be safe for that. Lock
  order is always per-module fetch lock → `sharedOutputLock`, never the reverse.

## Gotchas

- Kotlin nests block comments: a glob like `plsql/**` or `*.sql` after a `/` inside KDoc opens
  an unclosed nested comment. Spell paths without `/*` sequences in doc comments.
- Tests never require an Oracle installation: converter tests build a fake `ORACLE_HOME` with stub
  `.bat`/sh scripts (`FakeOracleHome`), and the copy-mode pipeline is covered by
  `FormsServiceIntegrationTest`. Classpath fixtures under `/fixtures/**` are copied by the build from
  the canonical repo-root `sample-forms/` dir (also the demo dir for `--forms-dir`).

## Commands

```
gradlew build                 # everything, incl. tests
gradlew :core:jvmTest         # core tests only
gradlew updateKotlinAbi       # required after public API changes (KGP ABI validation on core)
gradlew :server:installDist   # launcher at server/build/install/server/bin/server(.bat)
gradlew :server:packageMcpb   # .mcpb desktop bundle at server/build/mcpb/
gradlew :server:run --args="--forms-dir sample-forms"

claude plugin validate . --strict                        # the marketplace manifest
claude plugin validate ./plugins/oracle-forms --strict   # the Claude Code plugin manifest
```

Test the plugin end to end without installing it — `--plugin-dir` loads it straight from the
worktree, and `OFMCP_SERVER_HOME` makes the launcher use a local `installDist` tree instead of
downloading a release:

```
claude --plugin-dir ./plugins/oracle-forms
```

A Stop hook (`.claude/hooks/stop-verify.sh`) runs `:server:compileKotlin` before a turn ends when
`.kt/.kts` files changed.
