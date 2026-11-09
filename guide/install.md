# Installing

The plugin, the `.mcpb` bundle and a build from source are in the
[README](../README.md#quick-start). This page covers the other ways to run the server.

## Other MCP clients

Cursor, VS Code and Claude Desktop without the bundle can use the published image, which runs the
server over stdio with no local build. Point the volume mount at your forms directory (copy-mode: the pre-converted `*_fmb.xml`/`*.pld` files must sit next to the
modules — see [Docker](#docker-copy-mode-only)).

**Claude Desktop** (`claude_desktop_config.json`) and **Cursor** (`~/.cursor/mcp.json`) use the same shape:

```json
{
  "mcpServers": {
    "oracle-forms": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-v", "ofmcp-cache:/home/mcp/.cache", "-v", "/path/to/forms:/forms",
               "ghcr.io/aoreshkov/oracle-forms-mcp", "--forms-dir", "/forms"]
    }
  }
}
```

**VS Code** (`.vscode/mcp.json`) uses a `servers` key instead:

```json
{
  "servers": {
    "oracle-forms": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-v", "ofmcp-cache:/home/mcp/.cache", "-v", "${workspaceFolder}/forms:/forms",
               "ghcr.io/aoreshkov/oracle-forms-mcp", "--forms-dir", "/forms"]
    }
  }
}
```

The `ofmcp-cache` named volume keeps the parsed-module cache and — more importantly — the durable
annotation store across container restarts; `--rm` removes the container but not a named volume. Drop
it and the notes/tags/relations the assistant records won't survive the next run. See
[Docker](#docker-copy-mode-only) for the bind-mount variant and its one-time `chown`.

Prefer the native launcher? Swap `"command": "docker", "args": [...]` for
`"command": "/abs/path/to/server/build/install/server/bin/server", "args": ["--forms-dir", "/abs/path/to/forms"]`.

## HTTP transport

```
server --forms-dir C:\forms --transport http --port 3000   # endpoint: http://127.0.0.1:3000/mcp
```

The HTTP transport listens on `127.0.0.1` only. It has no authentication of its own, so the bind
address decides who can call every tool, including `fetch_module`, which runs your converter, and
the annotation writes. `--host` (or `OFMCP_HTTP_HOST`) binds it elsewhere, and the server logs a
warning at startup whenever that address is not loopback. The Host/Origin check stops a browser
from being used against the server through DNS rebinding. It does not stop a client on the network
that sends `Host: 127.0.0.1` itself.

Sessions expire after 30 minutes without a request (an open event stream does not count, a tool
call still running does), and their id then answers `404`, on which an MCP client starts a new
session. At most 10,000 sessions are open at once.

## Docker (copy-mode only)

A container image is published to `ghcr.io/aoreshkov/oracle-forms-mcp`. Oracle's `frmf2xml` /
`frmcmp_batch` binaries are proprietary and **not** bundled, so the image works only in
**copy-mode**: the modules you mount must already have their pre-converted text form
(`*_fmb.xml`/`*_mmb.xml`/`*_olb.xml`/`*.pld`) sitting next to them. For live `.fmb`/`.pll`
conversion, run the server on a host with an Oracle Forms installation (`ORACLE_HOME` set).

```
docker run -i -v /path/to/forms:/forms ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
```

**Persisting the cache and annotations.** Without a volume, the cache and the durable annotation
store live in the container's writable layer and are discarded when it exits. Mount a volume at
`/home/mcp/.cache` to keep them across runs:

```
docker run -i -v ofmcp-cache:/home/mcp/.cache -v /path/to/forms:/forms \
  ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
```

A named or anonymous volume inherits the image's non-root ownership (uid 10001) and just works.
A host **bind mount** does not — Docker never chowns the target — so run `chown 10001 /host/cache`
once on the host first, or redirect the writes with `--cache-dir` / `--annotations-dir` onto a path
the container user can write.

**Running the HTTP transport in a container.** Inside the container, loopback is the container's
own, so the server has to listen on `0.0.0.0` there. Publish the port on the *host's* loopback so
that it stays unreachable from the network:

```
docker run -p 127.0.0.1:3000:3000 -v /path/to/forms:/forms ghcr.io/aoreshkov/oracle-forms-mcp \
  --forms-dir /forms --transport http --host 0.0.0.0 --port 3000   # http://127.0.0.1:3000/mcp
```

A plain `-p 3000:3000` publishes the port on every interface of the host, which exposes every
tool to anyone who can reach it.
