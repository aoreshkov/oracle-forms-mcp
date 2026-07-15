---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring & resource-template gotcha

Tool files in `tools/` are **declarative adapters**: parse args → call `FormsService` →
`toolResult(dto)`. No business logic in a tool file (or in `ToolSupport.kt`) — it belongs in
`FormsService`. Every tool declares a `title`, behavior annotations (the shared `LOCAL_READ_ONLY`
constant in `ToolSupport.kt`), and `outputSchemaOf<Dto>()`; `toolResult` returns JSON text **and**
matching `structuredContent`. DTO fields are defaulted so output schemas stay forward-compatible.
`ToolRegistrationTest` enforces all of this — a new tool that skips a piece fails that test.

**stdout is the stdio protocol channel.** Never `println`/write to stdout to debug — all logging
goes Kermit → SLF4J → Logback → stderr.

**Resource templates gotcha (the inverse of the usual one):** this project deliberately does NOT
add a custom `SegmentTemplateMatcher`. The SDK's default matcher works here and must keep working;
`ModuleResourcesTest.sdkDefaultMatcherExtractsTheModuleSegment` is the regression canary. If it dies
with `NoSuchMethodError`, a newly added dependency has reintroduced the shadowed
`kotlinx.collections.immutable` — fix the dependency, do not add the workaround matcher.
