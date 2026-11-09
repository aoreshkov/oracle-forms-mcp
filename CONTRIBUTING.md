# Contributing to oracle-forms-mcp

Thanks for your interest! Bug reports, feature requests and pull requests are all welcome.
For significant changes, please open an issue first so we can discuss the direction before
you invest time.

## Prerequisites

- **JDK 25** (the build uses Gradle toolchains, so any JDK 17+ that can start the wrapper works —
  Gradle downloads JDK 25 for its daemon and the toolchain when none is installed).
- No other setup: `./gradlew` downloads everything else.
- **No Oracle installation is required to build or test.** Converter behavior is covered by a
  fake `ORACLE_HOME` (stub scripts), and the copy-mode pipeline runs end-to-end against the
  bundled `sample-forms/`.

## Build & test

```sh
./gradlew build     # compiles all modules, runs tests and API checks
./gradlew :core:jvmTest :server:test    # unit tests only (core is KMP — a bare `test` skips it)
./gradlew :server:run --args="--forms-dir sample-forms"   # run the MCP server locally
./gradlew :server:packageMcpb    # build the installable .mcpb bundle
```

## Project conventions

- **Dependency versions live only in `gradle/libs.versions.toml`.** Never hard-code a version
  in a build script.
- **Public API of `core` is validated.** If you intentionally change it, run
  `./gradlew updateKotlinAbi` and commit the updated `core/api/core.api`; `./gradlew build`
  fails otherwise.
- **stdio transport: never write to stdout.** Only MCP protocol frames may go to stdout —
  all logging goes to stderr/file (Kermit → SLF4J → Logback). A stray `println` corrupts
  the protocol stream.
- **The XML parser never fails on unknown vocabulary.** Forms XML is huge and version-dependent;
  unknown elements are skipped generically (still getting an `ObjectRef` when named).
- **Keep the index JSON small.** PL/SQL bodies live in `plsql/**` sidecars referenced by
  `SourceRef` line ranges — never inline code into `ModuleIndex`.
- When adding an MCP tool, provide all three metadata pieces: `title`, behavior annotations
  (reuse the shared constants — `LOCAL_READ_ONLY` in `ToolSupport.kt` for read tools,
  `ANNOTATION_WRITE` in `AnnotateElementTool.kt` for annotation writers — or declare the
  hints explicitly as `fetch_module`/`remove_annotation` do), and an `outputSchema`
  (`outputSchemaOf<Dto>()`).

## Changes that need an extra step

- **Parser changes: bump `CURRENT_INDEX_VERSION`** in `ModuleIndex.kt`, in the same commit, whenever
  a change alters what the parser writes or what a written field means. An upgrade changes no
  `.fmb`, so without the bump an existing cache looks current and keeps serving the old parser's
  answers. No test catches a missed bump.
- **Plugin changes: bump the plugin's `version`** in
  `plugins/oracle-forms/.claude-plugin/plugin.json`, which is separate from the server's version.
  Claude Code updates an installed plugin only when that field changes. CI fails if anything under
  `plugins/oracle-forms/` except its `README.md` differs from the `stable` branch while `version`
  still matches `stable`. One bump covers a release cycle, so if `main` already has a newer
  `version` than `stable`, you don't need another.
- **Changes to the `trace-form` skill or its description: run the evals** and compare with the
  baseline in [evals/README.md](evals/README.md). They need Linux or WSL and a JDK 25+ on `PATH`,
  and they make real model calls, against your plan's usage or your API bill, so nothing runs them
  automatically.

  ```sh
  ./gradlew :server:installDist
  bash evals/run.sh --model sonnet
  ```

- **Changes to the command line: regenerate the help page.** `HelpPageTest` compares `--help` with
  `server/src/test/resources/golden/help-root.txt`, so a changed option fails the build until you
  run the command below. Read the diff before committing it.

  ```sh
  ./gradlew :server:test "-Pgolden.update=true"
  ```

## Pull requests

1. Fork, create a topic branch from `main`.
2. Add tests for new behavior; make sure `./gradlew build` passes locally.
3. Use a clear title in imperative mood (Conventional Commits style — `feat:`, `fix:`,
   `docs:`, … — is appreciated but not enforced).
4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.

CI runs the full build on Linux and Windows; both must pass before merge.

## Reporting issues

Use the issue forms. For security vulnerabilities, **do not open a public issue** — see
[SECURITY.md](SECURITY.md).
