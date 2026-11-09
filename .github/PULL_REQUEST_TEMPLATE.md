## What & why

<!-- What does this PR change, and what problem does it solve? Link the issue if one exists. -->

## How was it tested?

<!-- Unit tests? Ran the server against a real forms dir? Which client? -->

## Checklist

- [ ] `./gradlew build` passes locally (includes tests and `checkKotlinAbi`)
- [ ] Ran `./gradlew updateKotlinAbi` and committed `core/api/core.api` (only if the public API of `core` changed intentionally)
- [ ] Bumped `CURRENT_INDEX_VERSION` in the same commit (only if what the parser writes, or what a written field means, changed)
- [ ] `CHANGELOG.md` updated under `[Unreleased]` (user-visible changes only)
- [ ] No stdout writes on the stdio path (logging goes to stderr)
