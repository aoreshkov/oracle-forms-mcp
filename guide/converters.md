# Converters

How a module becomes the text form the server indexes, and how to change that.

## Keeping the converted XML

By default a module's converted text form lives inside its cache entry. Point `--converted-dir` at
a directory of your own to keep the XML where you can read, diff, or feed it to other tooling:

```
server --forms-dir C:\forms --converted-dir C:\forms-xml
```

All modules share that one flat directory, each file named the way Oracle names it —
`orders_fmb.xml`, `mainmenu_mmb.xml`, `utils.pld` — so a re-fetch replaces a module's file rather
than accumulating copies. The directory is created if missing, and it must not be the forms
directory itself (the names would collide with the pre-converted modules read from there); a
subdirectory of the forms directory is fine, since it is scanned non-recursively.

**This is the directory the converter writes into**, not somewhere files are moved to afterwards:
the converter runs with it as its working directory, so a wrapper that writes to a fixed location
can simply be pointed at it. Output is attributed to a module by Oracle's naming first
(`orders_fmb.xml`), falling back to "the newest matching file this run wrote" for converters that
name their output freely. A file that was already there when the run started — another module's
text form, a killed run's partial output — never counts, and conversions sharing the directory are
serialised, rename included, so two modules cannot be mixed up. Whatever name the converter chose, the file is renamed to the
canonical one, and the index records it by a stable `converted/<name>` path either way — so an
index stays valid whether or not this option is set.

## Using your own converter

If your site wraps the Forms tools — its own environment setup, logon handling, a patched
`frmf2xml`, or a different converter entirely — point the server at it:

```
server --forms-dir C:\forms --convert-command C:\tools\fmb2xml.bat
```

The value is a **whole command line, not just an executable**, so a wrapper that needs arguments
of its own — an interpreter, a container, a compatibility layer — is configured directly:

```
server --forms-dir /srv/forms --convert-command "/opt/forms/convert.sh --xml --quiet"
server --forms-dir C:\forms  --convert-command "\"C:\tools\my tools\f2x.bat\" /nologon"
server --forms-dir /srv/forms --convert-command '["wine", "/opt/forms/frmf2xml.exe", "{}"]'
```

Two syntaxes are accepted, and neither is ever handed to a shell — the command is split here and
spawned with an argv list:

- **A quoted string.** Split on whitespace, with `"…"` or `'…'` grouping a part that contains
  spaces. Backslashes are literal, so Windows paths need no doubling.
- **A JSON array** — `["wine", "f2x.exe", "-xml"]` — one element per argument, the same shape MCP
  clients use for `command`/`args`. Prefer it when quoting gets awkward, and note that JSON itself
  requires `\\` for a backslash.

A value that names an existing file is taken whole, spaces and all, so a plain path needs no
quoting.

The module's absolute path goes wherever you write **`{}`**; with no `{}` in the command it is
appended as the last argument. The command runs with the **working directory set to the converted
directory** — that module's cache directory by default — and is expected to write the text form
there. A tool that cannot be pointed at a working directory is given the output file instead:
**`{out}`** is replaced with its absolute path (`<converted dir>/orders_fmb.xml`,
`<converted dir>/utils.pld`). `{out}` is only ever substituted, never appended. This mirrors how
`frmf2xml` is driven, so a script that already wraps it needs no changes. Emit the same formats the
parser reads: XML for `.fmb`/`.mmb`/`.olb`, a `.pld` dump for `.pll`. Oracle's `<name>_fmb.xml`
naming is preferred but not required — any `.xml` (or `.pld` for a library) written into the working
directory is picked up.

The program itself may be a path (absolute or relative to where the server was started) or a bare
name, which is looked up on `PATH`. An *argument*, though, is read by the command, which runs in
the converted directory — so a file shipped beside the server and passed to an interpreter would be
looked for there. Write it with **`{start}`**, which is replaced with the absolute directory the
server was started in (and, like `{out}`, never appended):

```
server --forms-dir /srv/forms --convert-command '["java", "{start}/converter/Convert.java", "xml", "{}", "{out}"]'
```

That is how a plugin whose host starts its server in the plugin's own directory, and passes
configuration through literally, names a converter it carries.

With [`--converted-dir`](#keeping-the-converted-xml) that working directory *is* your directory, so
a wrapper that writes to a fixed place of its own — rather than to wherever it was started — works
by pointing `--converted-dir` at it. Scripts that write into their working directory need no
adjustment either way.

Precedence is `--convert-command` → `ORACLE_HOME` → copy-mode, so an explicitly configured
command wins even on a machine with a Forms installation (`.pll` libraries can be taken out of
that order — see [below](#converting-plsql-libraries-with-their-own-command)). A blank value counts
as unset. Like `ORACLE_HOME`, the command is parsed and validated at the first conversion rather
than at startup, so a stale setting still leaves cached modules readable; the error then names the
flag to fix.

> **The output must be freshly written.** Because Forms-era tools return unreliable exit codes,
> a run is judged by its output file, and a file older than the run is treated as a leftover from
> a previous failed attempt. A script that *copies* a pre-generated file with `copy`, `xcopy`, or
> `cp -p` preserves the source's timestamp and will be rejected with "produced no output file".
> Redirect or rewrite instead (`type src > out`, `cat src > out`), or `touch` the result.

The command is **operator configuration only** — no tool argument can choose or extend it. Tool
callers supply a module name, which is resolved against the scanned forms directory before the
converter sees it, and the command is spawned directly with an argv list rather than through a
shell, so nothing in a module's path or in your own quoting can turn into a second command.

### Converting PL/SQL libraries with their own command

No Oracle tool converts a `.pll` to XML: `frmf2xml` accepts forms, menus, and object
libraries only, and a library is dumped to `.pld` text by `frmcmp` instead. A `--convert-command`
built around `frmf2xml` therefore fails on every library. `--compile-command` gives `.pll` modules
a command line of their own, with the same syntax and placeholders, and leaves every other module
type where it was:

```
server --forms-dir /srv/forms --converted-dir /srv/forms-xml --convert-command "/opt/forms/f2xml.sh {}" --compile-command "frmcmp_batch Module={} Module_Type=LIBRARY Script=YES Batch=YES Logon=NO Output_File={out}"
```

or, as a JSON array:

```
--compile-command '["frmcmp_batch", "Module={}", "Module_Type=LIBRARY", "Script=YES", "Batch=YES", "Logon=NO", "Output_File={out}"]'
```

Write it on one line. The value is split into arguments without a shell, so a `\` line
continuation copied into a configuration field would reach the tool as a literal argument.

**Give `frmcmp` `Output_File={out}`.** Without it, `frmcmp` writes the `.pld` next to the *module* —
into the forms directory, whatever its working directory — where the server would then read it as
a pre-converted file. When a run produces nothing in the converted directory but did leave a fresh
file next to the module, the error names that file and says to add `{out}`; the server does not
delete it.

`Logon=NO` matters too: without it (or a real `Userid=`), `frmcmp` prints its usage and exits 0
having written nothing.

Which converter a module reaches:

| `--compile-command` | `--convert-command` | `ORACLE_HOME` | `.pll` | `.fmb` / `.mmb` / `.olb` |
| --- | --- | --- | --- | --- |
| — | — | — | copy-mode | copy-mode |
| — | — | set | `frmcmp` | `frmf2xml` |
| — | set | — / set | convert command | convert command |
| set | — | — | compile command | copy-mode |
| set | — | set | compile command | `frmf2xml` |
| set | set | — / set | compile command | convert command |

With `--compile-command` unset, a `--convert-command` that handles libraries keeps receiving
them. Where a type is served in copy-mode, its pre-converted text form is
what the cache entry is checked against, so re-exporting an `_fmb.xml` still marks that form stale
while its libraries are converted from their binaries.

## Notes on the Oracle tools

- The tools are looked for in `$ORACLE_HOME/bin`, then in `$FORMS_INSTANCE/bin`: on Linux,
  Forms 12c and later ship `frmf2xml` only as `frmf2xml.sh` in the Forms instance. `.sh`
  wrappers are accepted there (and `.bat`/`.cmd`/`.exe` on Windows).
- `frmf2xml` writes its output into the process working directory on some installs and next to
  its input on others (Linux 14.1.2). The server therefore converts a copy of the module in a
  private staging directory, with that directory as cwd, and moves the result into the converted
  directory (`--converted-dir`, else the module's cache dir) — so the forms directory is never
  written to and may be read-only. It passes `OVERWRITE=YES USE_PROPERTY_IDS=NO`.
- Outside Windows, the tools run with `TERM=vt220` and `FORMS_API_TK_BYPASS=TRUE` unless the
  server's environment already sets them: an MCP host may start the server with neither a
  terminal nor a display, and without them `frmcmp_batch` fails with `FRM-91500` and `frmf2xml`
  with "Failed to create new forms context". `TERM=dumb` counts as unset, since `frmcmp_batch`
  fails under it the same way. Set either yourself to override it.
- `frmcmp_batch` is preferred over `frmcmp` (headless); the server passes
  `Script=YES Batch=YES Logon=NO` and an explicit `Output_File` — without one, `frmcmp` writes the
  `.pld` next to the module rather than into its working directory — and augments `FORMS_PATH`
  with the forms dir so attached libraries resolve.
- Forms tools have unreliable exit codes — success is judged by the output file existing,
  being non-empty, and being newer than the invocation; failures surface the tool's output tail.
- `.pld` files may be written in the client NLS charset; the parser reads UTF-8 with a
  windows-1252 fallback (set `NLS_LANG` accordingly if you see mojibake).
