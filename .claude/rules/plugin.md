---
paths:
  - "plugins/**"
  - ".claude-plugin/**"
  - "evals/**"
---

# Claude Code plugin channel

- **Delivery.** `/plugin marketplace add aoreshkov/oracle-forms-mcp` reads the marketplace from
  `main`, and its entry serves the plugin from the **`stable` branch**. `release.yml` moves `stable`
  to each tag once the release is published, so a plugin change reaches users at the next release
  and never ahead of a server it needs.
- **A plugin-only fix waits for a release, or for `stable` to be moved by hand.** A hand move must
  fast-forward to a commit on `main`, never force, and never cross a plugin change that needs an
  unreleased server.
- **The plugin carries no jars.** `.mcp.json` runs `launcher/OracleFormsMcpLauncher.java` in Java
  single-file source mode (hence a **JDK** 25+, not a JRE), which downloads the release zip,
  verifies its published sha256, unpacks it under `${CLAUDE_PLUGIN_DATA}` (passed as
  `OFMCP_PLUGIN_DATA`; without it, a per-user OS data directory, so a host that sets only `OFMCP_*`
  variables can run it too), and loads it in-process.
- **Once a version is installed, the network is never in the way of a start.** With `latest` the
  check and any newer install run on a daemon thread for the next session, and only a first install
  blocks.
- **The launcher stays on the Java 21 language and API** (CI compiles it with `--release 21`), so on
  a JDK older than the server needs it still starts, runs the server as a child on `JAVA_HOME`'s
  Java when that is new enough, and otherwise names the fix instead of failing to compile.
- **An unset `forms_dir` is not the launcher's to refuse.** No forms directory is a state the server
  serves, so the option stays optional in the manifests (`.claude/rules/server.md` has the rule).
- **CI gate.** The `claude-code-plugin` job validates the manifests, compiles the launcher, and
  requires `version` to differ from `stable`'s whenever the plugin's files do, because Claude Code
  won't update an install to the same version. Nothing else stands between a commit on main and the
  next release's install. Bump `version` for a change to the plugin's files, never for a release
  alone.
- **The one skill, `skills/trace-form/SKILL.md`,** holds the traversal order and the habit→tool
  mapping that the server's `instructions` state in brief, at the length a worked example needs.
  Both are guidance, not behaviour. Keep them agreeing with each other and with the tools they name
  (`PluginSkillTest` fails on a tool name in the skill that isn't registered).
- **`evals/` tests the skill with real model calls**, so it runs by hand when the skill changes
  (Linux or WSL, through `evals/run.sh`, never `claude plugin eval` directly: an eval run has no
  `forms_dir`, so the script evaluates a temporary copy whose `.mcp.json` names `sample-forms` and a
  local build).
