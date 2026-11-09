---
paths:
  - "core/src/**/convert/**"
  - "server/src/**/Main.kt"
---

# Converters and the Oracle tools

- **Where the tools are found.** `ORACLE_HOME/bin`, then `FORMS_INSTANCE/bin` (Linux Forms 12c+ has
  `frmf2xml` only as `frmf2xml.sh` there). `frmcmp_batch` over `frmcmp`. Exit codes are unreliable,
  so success is judged by the output file.
- **`frmf2xml` converts a copy, never the module in place.** It writes to the process **cwd** on
  Windows but **next to its input** on Linux 14.1.2, so it runs on a *copy* of the module in a
  per-run staging dir inside the target (cwd = that dir) and the result is moved out. The forms dir
  may be read-only.
- **Headless environment.** Outside Windows the tools get `TERM=vt220` /
  `FORMS_API_TK_BYPASS=TRUE` when unset, and `TERM=dumb` counts as unset because it fails the same
  way (`headlessEnv`). Without them a host's minimal environment fails `frmcmp_batch` (`FRM-91500`)
  and `frmf2xml` (no display).
- **`--convert-command` swaps in a site-supplied converter** on the same cwd convention
  (`CustomCommandModuleConverter`). Precedence is command → `ORACLE_HOME` → copy-mode.
- **It is a whole command line, not an executable.** `ConvertCommandSpec` splits it into argv
  (quoted string with literal backslashes, or a JSON array) and substitutes the module path for
  `{}`, appending it when `{}` is absent. A value naming an existing file is taken whole, which is
  what keeps unquoted `C:\Program Files\…` configs working, so keep that check ahead of tokenizing.
  All four channels pass it as one string (flag, env var, two `user_config` text fields), so the
  split has to live in the server.
- **Operator config only.** The command is never reachable from a tool argument, and is spawned
  with an argv list, never a shell.
- **`--converted-dir` is that cwd.** Conversion runs directly in `FormsService.convertedDirOf(key)`
  and `canonicalizeConverted` only renames the result to `ModuleKey.convertedFileName` in place.
- **What keeps one shared directory unambiguous** is `OutputRun` — a run's output is only a file it
  *produced*, canonical name first, and a freely named fallback never one named as another module's
  text form (`ModuleType.matchConverted`) — plus `FormsService.sharedOutputLock`, which serialises
  each conversion **together with its `canonicalizeConverted` rename** whenever `--converted-dir` is
  set: a freely named output is overwritable by the next run until it carries its module's name.
- **What counts as produced** (`ConversionSupport.kt`, shared output and exit-code handling): a file
  that is fresh (not older than `startedAt` minus the 2s FAT slack) **and** new or changed
  (mtime+size) since the directory listing `OutputRun.begin` takes. Freshness alone takes the
  sibling converted a moment ago. So a converter that copies with preserved mtimes (`copy`,
  `cp -p`) reads as having produced nothing.
- **Unset means blank or an unsubstituted template.** Both options also read
  `OFMCP_CONVERT_COMMAND` / `OFMCP_CONVERTED_DIR` (flag wins), and the `configured()` converter in
  `Main.kt` treats blank *and* an unsubstituted `${user_config.…}` template as unset (cli-kit's
  `unsetWhen`). That is how the MCPB and plugin channels pass "not set".
- **`--compile-command` (`OFMCP_COMPILE_COMMAND`) is a second command of the same kind for `.pll`
  only**, because `frmf2xml` rejects libraries and a `--convert-command` built on it loses them all.
  `ModuleConverters.forEnvironment` wraps the usual choice in `ByModuleTypeConverter`, so a `.pll`
  goes compile → convert → `ORACLE_HOME` → copy and every other type is untouched. Unset, nothing is
  wrapped.
- **`{out}`.** `frmcmp` writes the `.pld` **next to the module**, whatever its cwd, unless given
  `Output_File`, hence `{out}` (the absolute canonical output path, substituted in the same single
  pass as `{}`, **never appended**) and the stray-file hint on "produced no output file".
- **`{start}`** is the third placeholder in that pass (the server's start directory, never
  appended). A relative *program* is resolved against it, but an argument is resolved by the child
  in its own cwd, so a file beside the server reaches an interpreter only as `{start}/…`.
- **Messages name the right option** through the internal `ConverterOption`, whose fallback advice
  differs per option.
- **Whether a module's binary or its text form is consumed — and fingerprinted — is
  `ModuleConverter.convertsBinary(type)`, asked per module type.** Never re-derive it from the
  configuration in the server: with only `--compile-command` set, forms stay in copy-mode, and a
  form fingerprinted against its `.fmb` would never go stale when its XML is re-exported
  (`PerTypeConversionTest`).
