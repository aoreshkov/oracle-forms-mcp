---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring & resource-template gotcha

Tool files in `tools/` are **declarative adapters**: parse args → call `FormsService` →
`toolResult(dto)`. No business logic in a tool file (or in `ToolSupport.kt`) — it belongs in
`FormsService`. Every tool declares a `title`, behavior annotations (read tools reuse the shared
`LOCAL_READ_ONLY` constant in `ToolSupport.kt`, annotation writers `ANNOTATION_WRITE` in
`AnnotateElementTool.kt`; `fetch_module` and `remove_annotation` declare their own hints), and
`outputSchemaOf<Dto>()`; `toolResult` returns JSON text **and**
matching `structuredContent`. DTO fields are defaulted so output schemas stay forward-compatible.
`ToolRegistrationTest` enforces all of this — a new tool that skips a piece fails that test.

**stdout is the stdio protocol channel.** Never `println`/write to stdout to debug — all logging
goes Kermit → SLF4J → Logback → stderr.

**Resource templates gotcha (the inverse of the usual one):** this project deliberately does NOT
add a custom `SegmentTemplateMatcher`. The SDK's default matcher works here and must keep working;
`ModuleResourcesTest.sdkDefaultMatcherExtractsTheModuleSegment` is the regression canary. If it dies
with `NoSuchMethodError`, a newly added dependency has reintroduced the shadowed
`kotlinx.collections.immutable` — fix the dependency, do not add the workaround matcher.

**Tool handlers run concurrently** (MCP SDK 0.15+): the server dispatches inbound requests in
parallel after the handshake, so two tool calls can be inside `FormsService` at the same time.
Anything you add that mutates shared state needs its own serialisation — `fetchModule` uses a
per-`ModuleKey` `Mutex` (taken before `sharedOutputLock`, never after), the annotation store uses
`writeMutex`. Registration is once-only: `addTool`/`addPrompt`/`addResource`/`addResourceTemplate`
throw `IllegalArgumentException` on a duplicate name instead of silently replacing, which is why
`addModuleIndexResource` both checks `uri in resources` and swallows that exception.
