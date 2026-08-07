# Oracle Forms — Claude Code plugin

The one-command install of [oracle-forms-mcp](https://github.com/aoreshkov/oracle-forms-mcp) for
Claude Code. Installing the plugin registers the MCP server, prompts for your forms directory, and
fetches the server itself — no clone, no Gradle build, no `.mcp.json` to hand-edit.

```
/plugin marketplace add aoreshkov/oracle-forms-mcp
/plugin install oracle-forms@oracle-forms-mcp
```

Claude Code then asks for the **Forms directory**. Point it at the folder holding your `.fmb`,
`.mmb`, `.pll`, and `.olb` modules and the tools are live in the next session (or immediately after
`/reload-plugins`). Verify with `/mcp` — the server appears as `plugin:oracle-forms:oracle-forms`.

## Requirements

- **A JDK 21+ on your `PATH`.** Check with `javac -version`. The launcher below runs in Java's
  single-file source mode, which a bare JRE cannot compile. If you only have a JRE, use the
  [`.mcpb` bundle or `claude mcp add`](https://github.com/aoreshkov/oracle-forms-mcp#quick-start)
  instead — both take a prebuilt launcher script.
- **An Oracle Forms installation (`ORACLE_HOME`) for live conversion**, exported in the environment
  you start Claude Code from. Without it the server runs in copy-mode and reads the pre-converted
  `*_fmb.xml` / `*.pld` files sitting next to your modules. Oracle's `frmf2xml` and `frmcmp` are
  proprietary and are never bundled.

## Configuration

Set these in `/plugin` → **Oracle Forms** → configure; they are stored in your user
`settings.json` under `pluginConfigs`.

| Option | Default | What it does |
|---|---|---|
| `forms_dir` | — (required) | The directory of Forms modules to serve. Scanned non-recursively. |
| `convert_command` | — (optional) | A site-supplied converter to run instead of Oracle's `frmf2xml`, as `<command> <module>` with the working directory set to the module's cache directory. Takes precedence over `ORACLE_HOME`. |
| `converted_dir` | — (optional) | Where to keep the converted XML / `.pld` text forms: one flat directory for all modules, each file named after its module (`orders_fmb.xml`, `utils.pld`). Must not be the forms directory. Defaults to inside the cache. |
| `server_version` | `latest` | Which released server build to run. Set a version such as `0.4.0` to pin. |

The two optional ones are handed to the server as `OFMCP_CONVERT_COMMAND` and
`OFMCP_CONVERTED_DIR` (see the [escape hatches](#escape-hatches) table) rather than as command-line
arguments, so leaving them unset can never shift the argument list. Details on both in the
[main README](https://github.com/aoreshkov/oracle-forms-mcp#using-your-own-converter).

## How the server gets here

A plugin is distributed as a git checkout, so it cannot carry the server's jars. `.mcp.json` starts
`launcher/OracleFormsMcpLauncher.java`, which on first run:

1. resolves `latest` to the newest GitHub release (remembered for 24 hours, so a session start is
   normally not a network call) or uses the version you pinned;
2. downloads `oracle-forms-mcp-server-<version>.zip` and its published `.sha256` from that release,
   and **refuses to install anything that doesn't match the checksum**;
3. unpacks it into the plugin's persistent data directory, moving it into place only once complete;
4. loads it into the launcher's own JVM, so the stdio pipes Claude Code opened are the pipes the
   MCP protocol speaks over — one process, nothing left orphaned when a session ends.

Later sessions find the unpacked distribution and skip straight to step 4. Everything the launcher
prints goes to stderr; stdout is the protocol channel.

Both the archive and the plugin come from the same repository over HTTPS, so the checksum protects
against a corrupted or truncated download rather than against a compromised GitHub account. Release
artifacts also carry [build provenance](https://github.com/aoreshkov/oracle-forms-mcp/attestations),
verifiable with `gh attestation verify <zip> --repo aoreshkov/oracle-forms-mcp`.

### Escape hatches

| Variable | Effect |
|---|---|
| `OFMCP_SERVER_HOME` | Path to an existing server distribution (a `gradlew :server:installDist` tree or an unpacked release zip). Nothing is downloaded. |
| `OFMCP_SERVER_VERSION` | Same as the `server_version` option; the plugin sets it from your configuration. |
| `OFMCP_CONVERT_COMMAND` | Same as the `convert_command` option (the server's `--convert-command`). |
| `OFMCP_CONVERTED_DIR` | Same as the `converted_dir` option (the server's `--converted-dir`). |

To wipe the downloaded distributions, delete the plugin's data directory
(`~/.claude/plugins/data/oracle-forms-oracle-forms-mcp/`); the next session re-fetches.

## What you get

The full tool set — `list_modules`, `fetch_module`, `get_module_overview`, `list_blocks`,
`get_block`, `list_triggers`, `get_trigger`, `list_program_units`, `get_program_unit`,
`search_source`, `get_object_xml` — plus the annotation tools that let Claude record durable notes,
tags, and cross-references on individual elements, the `oracleforms://` resources, and the
`explain_module` prompt. See the [main README](https://github.com/aoreshkov/oracle-forms-mcp#tools).

Because the server is plugin-provided, its tools are namespaced:
`mcp__plugin_oracle-forms_oracle-forms__list_modules`. Use that form in permission rules, a
subagent's `tools` list, or a hook matcher.
