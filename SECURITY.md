# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Latest release (0.x) | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Instead, use GitHub's private vulnerability reporting: go to the repository's **Security**
tab → **Report a vulnerability** (or use
[this link](https://github.com/aoreshkov/oracle-forms-mcp/security/advisories/new)).

You can expect an initial response within a few days. Please include reproduction steps and
the affected version. Once a fix is released, the advisory will be published with credit to
the reporter (unless you prefer to stay anonymous).

## Scope notes

This server reads Oracle Forms modules from a configured directory, optionally invokes the
local Oracle tools (`frmf2xml`, `frmcmp_batch`) to convert them, and writes converted text and
parsed indexes to a local cache directory. Reports about path traversal while writing the
cache, injection into the tool invocations or environment (`FORMS_PATH`, `ORACLE_HOME`),
denial of service via malformed module XML, or protocol-stream injection over the stdio/HTTP
transports are particularly relevant.
