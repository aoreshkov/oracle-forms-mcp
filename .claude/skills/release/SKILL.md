---
name: release
description: Cut a new oracle-forms-mcp release — bump the version in both tag-guarded files consistently, update the changelog, run the pre-flight checks, and (on request) tag. Use when preparing a release or when a release tag failed the version-match guard.
disable-model-invocation: true
argument-hint: "[new version, e.g. 0.1.2]"
---

# Cut a release

The release workflow (`.github/workflows/release.yml`) is triggered by a `v*` tag and its
**first step is a hard version-match guard**: if the tag doesn't match the version in both
source-of-truth files, the whole release (GitHub release + GHCR image + MCP registry publish)
fails. This skill makes those files agree *before* the tag is pushed.

## 1. Determine the new version

Use `$ARGUMENTS` if given. Otherwise read the current version from `gradle.properties`
(`version=`), decide the bump with the user (semver), and confirm. Call the new value
`<VERSION>` (no `v` prefix inside files; the git tag is `v<VERSION>`).

## 2. Bump the version in BOTH guarded files

The tag guard checks these two exact patterns (keep the formatting identical):

1. `gradle.properties` — the line `version=<VERSION>` (guard: `^version=<VERSION>$`).
2. `server.json` — the top-level `"version": "<VERSION>"` (guard: `jq .version == <VERSION>`).
   Note: the publish job later rewrites `server.json` `.version` and `.packages[0].identifier`
   from the tag, but the guard still requires the committed value to match, so set it here.

**There is no third file.** The MCP `Implementation` version is baked at build time from
`project.version` into a classpath resource (`generateVersionResource` in
`server/build.gradle.kts` → `ServerVersion.kt`), and the MCPB bundle's manifest version comes from
the same place via the `@version@` token in `server/mcpb/manifest.template.json` — so neither can
drift from `gradle.properties`. Do not hand-edit a version constant in the Kotlin sources or the
manifest template. The `icons[].src` URLs in `server.json` also need no hand-editing: the publish
job re-pins them to the tag (the guard only checks they point at a `vX.Y.Z` tag, never a branch).
Confirm the two files:

```
grep -n "version=" gradle.properties
grep -n '"version"' server.json
```

## 3. Update the changelog

`CHANGELOG.md` follows Keep a Changelog. Move the `## [Unreleased]` items into a new
`## [<VERSION>] - <YYYY-MM-DD>` section, add a fresh empty `## [Unreleased]`, and update the
link-reference lines at the bottom (`[Unreleased]` compare range + a new `[<VERSION>]` tag link).
Use `git log <last-tag>..HEAD` to gather user-facing changes. (The GitHub release itself also
sets `generate_release_notes: true`, but the curated changelog is the human-facing record.)

## 4. Pre-flight checks (catch the known release-pipeline gotchas)

- **MCP registry field caps:** in `server.json`, both `.description` and `.title` must be **≤ 100
  characters** or the registry publish rejects the listing. Verify:
  `jq -r '.description | length, (.title | length)' server.json` → both ≤ 100. (The release
  workflow's guard step re-checks this, along with the icon URLs being tag-pinned.)
- **MCPB bundle builds:** `./gradlew :server:packageMcpb` produces
  `server/build/mcpb/oracle-forms-mcp-<VERSION>.mcpb`. Its sha256 is computed in CI and written
  into the registry listing, so a broken bundle fails the release. Sanity-check the archive has
  `manifest.json` at the root and an executable `server/bin/server` (`unzip -Z <bundle>`).
- **`gradlew` executable bit:** the wrapper must stay executable in git or the CI build fails
  on a fresh checkout. Verify: `git ls-files -s gradlew` shows mode `100755`.
- **No private paths leak:** confirm nothing under `docs/` (private) is referenced from
  released files, and `docs/` is gitignored (`git check-ignore docs/`).
- **Public API is in sync:** `./gradlew checkKotlinAbi` passes (run `updateKotlinAbi` first if it
  fails and the change was intentional).
- **Build is green:** `./gradlew build` passes, and `./gradlew :server:installDist` produces
  the distribution the release zip is built from.

## 5. Simulate the tag guard locally

Before tagging, run the guard's own logic so you don't discover a mismatch in CI:

```
VERSION="<VERSION>"
grep -q "^version=${VERSION}$" gradle.properties && \
jq -e --arg v "$VERSION" '.version == $v' server.json >/dev/null && \
echo "guard OK" || echo "guard WOULD FAIL"
```

## 6. Commit and (on explicit request) tag

Commit the version bump + changelog. **Only if the user explicitly asks**, create and push an
**annotated** `v<VERSION>` tag — pushing the tag triggers the live release, GHCR push, and MCP
registry publish. Otherwise stop after the commit and report that the tag is ready to push.

```
git tag -a v<VERSION> -m "oracle-forms-mcp v<VERSION>"
git push origin v<VERSION>
```

Use an annotated tag (`-a`), not a lightweight one: it carries the tagger, date, and message,
is preferred by `git describe`, and can be signed. This matches `v0.1.0`. Note that
`release.yml` triggers on either tag type (`on: push: tags: v*`), so this is hygiene, not a
functional requirement.

**Never re-tag or force-move an already-published tag** (e.g. `v0.1.1`, which is live on GHCR
and the MCP registry). Fix a bad release by cutting the next version, not by moving its tag.

## 7. Report

Summarize: old → new version, the two files updated, changelog entry, pre-flight results
(description/title length, MCPB bundle build, gradlew mode, checkKotlinAbi, build status, local
guard simulation), and whether a tag was pushed or is pending. Note that the tag publishes four
things: the GitHub release (zip + `.mcpb`), the GHCR image, and the MCP Registry listing.
