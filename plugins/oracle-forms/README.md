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
Skip the prompt and the server still starts; its tools then answer with an error asking for the
directory until you [set it](#configuration).

To install from a shell or a script, use the same commands without the slash. There's no prompt in
this case, so pass the directory with `--config`, which you can repeat for the other
[options](#configuration):

```
claude plugin marketplace add aoreshkov/oracle-forms-mcp
claude plugin install oracle-forms@oracle-forms-mcp --config forms_dir=/path/to/forms
```

## Requirements

- **A JDK 25+**, on your `PATH` (check with `javac -version`) or anywhere else, named by the
  `java` option below. The launcher runs in Java's single-file source mode, which a bare JRE
  cannot compile. If you only have a JRE, use the
  [`.mcpb` bundle or `claude mcp add`](https://github.com/aoreshkov/oracle-forms-mcp#quick-start)
  instead — both take a prebuilt launcher script. If the JDK that starts the launcher is 21 to 24
  and `JAVA_HOME` names Java 25+, the server runs on `JAVA_HOME`'s Java instead (plugin 1.7.0+).
- **An Oracle Forms installation (`ORACLE_HOME`) for live conversion**, exported in the environment
  you start Claude Code from. Without it the server runs in copy-mode and reads the pre-converted
  `*_fmb.xml` / `*.pld` files sitting next to your modules. Oracle's `frmf2xml` and `frmcmp` are
  proprietary and are never bundled.

## Configuration

In a Claude Code session, set these with `/plugin configure oracle-forms@oracle-forms-mcp`, or run
`/plugin`, select the plugin on the **Installed** tab, and choose **Configure options**. They also
appear as rows in `/config`.

From a shell, `claude plugin configure oracle-forms@oracle-forms-mcp` lists them. Adding
`--values-stdin` saves a JSON object piped to it, and any option you leave out keeps its value.
Restart Claude Code to apply what you saved this way.

Either way, the values are stored in your user `settings.json` under `pluginConfigs`.

| Option | Default | What it does |
|---|---|---|
| `forms_dir` | — | The directory of Forms modules to serve. Scanned non-recursively. Until it is set the server still starts, and every tool answers with an error asking for it (plugin 1.6.0+, with server 0.19.0+). |
| `convert_command` | — (optional) | A site-supplied converter to run instead of Oracle's `frmf2xml` — a whole command line with its arguments (`/opt/forms/convert.sh --xml {}`), quoted where a part contains spaces, or a JSON array. It runs with the working directory set to `converted_dir`, or to the module's cache directory when that is unset, and must write its output there; the module's path replaces `{}`, or is appended when `{}` is absent. Never goes through a shell. Takes precedence over `ORACLE_HOME`. |
| `compile_command` | — (optional) | A separate converter for `.pll` libraries only, with the same syntax as `convert_command`; the output `.pld` path replaces `{out}`. Needed when `convert_command` is built on `frmf2xml`, which rejects libraries: `frmcmp_batch Module={} Module_Type=LIBRARY Script=YES Batch=YES Logon=NO Output_File={out}`. Needs server 0.11.0+. |
| `converted_dir` | — (optional) | Where to keep the converted XML / `.pld` text forms: one flat directory for all modules, each file named after its module (`orders_fmb.xml`, `utils.pld`). Must not be the forms directory. Defaults to inside the cache. |
| `server_version` | `latest` | Which released server build to run. Set a version such as `0.4.0` to pin. |
| `java` | `java` | The `java` executable that runs the launcher and the server — a JDK 25+. The default is the one on `PATH`; give a full path to the executable (`C:\Program Files\Java\jdk-25\bin\java.exe`, `/usr/lib/jvm/jdk-25/bin/java`) to use a JDK that isn't on `PATH`, or a newer one than `PATH` has. If the one chosen is too old for the server, `JAVA_HOME` is tried next (plugin 1.7.0+). Plugin 1.4.0+. |

The three optional ones are handed to the server as `OFMCP_CONVERT_COMMAND`,
`OFMCP_COMPILE_COMMAND` and `OFMCP_CONVERTED_DIR` (see the [escape hatches](#escape-hatches) table)
rather than as command-line arguments, so leaving them unset can never shift the argument list.
Details in the [converters guide](https://github.com/aoreshkov/oracle-forms-mcp/blob/main/guide/converters.md#using-your-own-converter).

## How the server gets here

A plugin is distributed as a git checkout, so it cannot carry the server's jars. `.mcp.json` starts
`launcher/OracleFormsMcpLauncher.java`, which on first run:

1. resolves `latest` to the newest GitHub release, or uses the version you pinned;
2. downloads `oracle-forms-mcp-server-<version>.zip` and its published `.sha256` from that release,
   and **refuses to install anything that doesn't match the checksum**;
3. unpacks it into the plugin's persistent data directory, moving it into place only once complete;
4. loads it into the launcher's own JVM, so the stdio pipes Claude Code opened are the pipes the
   MCP protocol speaks over — one process, nothing left orphaned when a session ends. Only the
   `JAVA_HOME` fallback runs a second JVM, and that one also ends when the session closes its pipes.

Later sessions start the installed version straight away, so a slow or blocked network can't delay
them. With `latest`, the launcher also checks GitHub in the background at most once every 24 hours,
and installs a newer release for the next session to use. If the check or the install fails, the
running server carries on, the reason goes to stderr, and the launcher tries again after an hour.

A pinned version doesn't update: if it isn't installed and can't be, the server fails to start with
the reason. Everything the launcher prints goes to stderr; stdout is the protocol channel.

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
| `OFMCP_COMPILE_COMMAND` | Same as the `compile_command` option (the server's `--compile-command`). |
| `OFMCP_CONVERTED_DIR` | Same as the `converted_dir` option (the server's `--converted-dir`). |

To wipe the downloaded distributions, delete the plugin's data directory
(`~/.claude/plugins/data/oracle-forms-oracle-forms-mcp/`); the next session re-fetches.

The launcher also runs outside Claude Code (plugin 1.5.0+): a host that starts it with a fixed
command line can pass the forms directory as `OFMCP_FORMS_DIR`, and without `OFMCP_PLUGIN_DATA` the
downloads go to a per-user directory — `%LOCALAPPDATA%\oracle-forms-mcp\launcher` on Windows,
`~/Library/Application Support/oracle-forms-mcp/launcher` on macOS, and
`$XDG_DATA_HOME/oracle-forms-mcp/launcher` (else `~/.local/share/…`) elsewhere.

## Updating

The server and the plugin update separately:

- **Server:** with `server_version` set to `latest`, it updates itself as described
  [above](#how-the-server-gets-here), and a new release takes effect in the session after it is
  downloaded.
- **Plugin (launcher, skill and options):** a new version becomes available with a server release.
  Auto-update is off by default for a third-party marketplace like this one. To turn it on, run
  `/plugin`, go to the **Marketplaces** tab, select this marketplace, and choose **Enable
  auto-update**. To update by hand, select **Update now** on the plugin's page in the **Installed**
  tab, or run `claude plugin update oracle-forms@oracle-forms-mcp` in your shell. If that says you
  already have the latest version when you don't, first run
  `claude plugin marketplace update oracle-forms-mcp`.

## Troubleshooting

**The server shows as failed, or `CONNECTION_CLOSED`.** The launcher's reason is its last line on
stderr, which Claude Code doesn't show in that message. On Windows, Claude Code keeps it in
`%LOCALAPPDATA%\claude-cli-nodejs\Cache\<project>\mcp-logs-plugin-oracle-forms-oracle-forms\`.

**To try again,** select **Reconnect** from the server's menu in `/mcp`. In `claude -p` runs, a
plugin's server that failed is skipped for 15 minutes.

**The first start times out.** It has to download about 14 MiB before the server can answer, and
Claude Code allows 30 seconds by default. Set `MCP_TIMEOUT`, in milliseconds, in the environment you
start Claude Code from, such as `120000`. Only a first install or a newly pinned version has to
wait like this.

**Java is too old.** The launcher names the Java it found and what to change. Set the `java` option,
or `JAVA_HOME`, to a Java 25+ installation. The launcher only targets Java 21 and later, so with an
older `java` on `PATH` it may not start at all, and `JAVA_HOME` can't help: set the `java` option.

**You're behind a proxy** (plugin 1.7.0+). Set `HTTPS_PROXY` in the environment you start Claude Code
from, as `http://host:port` or `host:port`. `NO_PROXY` takes a comma-separated list of hosts, each of
which also covers its subdomains, or `*` for everything. The launcher connects to `api.github.com`,
`github.com` and `release-assets.githubusercontent.com`.

A proxy that requires authentication isn't supported. Any credentials in `HTTPS_PROXY` are not sent.
Instead, download the release zip yourself, unpack it, and point `OFMCP_SERVER_HOME` at the result.

## What you get

The full tool set — `list_modules`, `fetch_module`, `get_module_overview`, `list_blocks`,
`get_block`, `list_triggers`, `get_trigger`, `list_program_units`, `get_program_unit`,
`search_source`, `search_modules`, `read_source`, `get_object_xml` — plus the annotation tools that
let Claude record durable notes, tags, and cross-references on individual elements, the
`oracleforms://` resources, and the `explain_module` prompt. See the
[main README](https://github.com/aoreshkov/oracle-forms-mcp#tools).

Because the server is plugin-provided, its tools are namespaced:
`mcp__plugin_oracle-forms_oracle-forms__list_modules`. Use that form in permission rules, a
subagent's `tools` list, or a hook matcher.

### The `trace-form` skill

The plugin also ships one skill, `skills/trace-form/SKILL.md`. It carries what no individual tool
description can: the order to ask in when tracing how a screen works, the five readings that are
misleading rather than merely incomplete (an inherited body that looks empty, modality living on the
window, a property class that *is* the item's role, `null` meaning "not overridden" rather than
"off", and a classed item's properties coming from its class), when `get_object_xml` is the right
call and when it is a detour, and a worked trace over the demo directory. Claude loads it on its own
when a question is about how a form, field, button or modal behaves; `/oracle-forms:trace-form`
invokes it directly.
