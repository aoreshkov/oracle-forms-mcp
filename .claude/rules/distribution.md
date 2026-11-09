---
paths:
  - ".github/workflows/release.yml"
  - "server.json"
  - "gradle.properties"
  - "server/mcpb/**"
  - "assets/**"
---

# Release channels, versions and icons

- **Three channels, all cut by `.github/workflows/release.yml` from a `v*` tag:** the GHCR image, the
  release zip (plus its `.zip.sha256`), and an `.mcpb` desktop bundle
  (`server/mcpb/manifest.template.json` → `packageMcpb`).
- **`server.json` is the MCP Registry listing.** The publish job rewrites its version and icon tag,
  and appends the `mcpb` package with the bundle's sha256.
- **Exactly two files carry the version by hand:** `gradle.properties` and `server.json` (the release
  tag guard checks both). Everything else derives it: the MCP `Implementation` version via
  `generateVersionResource`, the MCPB manifest via the `@version@` token in
  `server/mcpb/manifest.template.json`. Never add a third.
- **The Claude Code plugin's `version` is not an exception.** It versions the plugin's own files
  (manifest + launcher), which change on their own schedule, and the server build it runs is
  resolved at runtime. Never bump it as part of a release.
- **`assets/icon.svg` + `assets/icon-512.png` are generated artwork, not hand-edited.** Both files are
  drawn from one geometry description by a generator kept outside this repo, and copied in. Editing
  either in place is undone by the next redraw, and editing only one re-creates the divergence they
  were unified to fix (the SVG's gradients are per-shape, and a raster that uses one canvas-wide
  gradient instead draws a different icon).
- **Where the icons are consumed:** `server/build.gradle.kts` (server resources),
  `server/mcpb/manifest.template.json`, and `server.json`'s tag-pinned `icons` URLs, so a change here
  reaches the registry listing only at the next tag.
