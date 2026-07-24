# Runtime-only image: the distribution is built by CI (or locally) first with
#   ./gradlew :server:installDist
# The dist is pure JVM (arch-independent), so one COPY serves amd64 and arm64.
# Tag + digest: the digest pins the exact multi-arch image (Dependabot's docker
# ecosystem bumps it); the tag documents the intent.
#
# COPY-MODE ONLY: Oracle's frmf2xml/frmcmp binaries are proprietary and are NOT
# bundled. This image can only serve modules that have already been converted to
# text (`*_fmb.xml`/`*_mmb.xml`/`*_olb.xml`/`*.pld`) sitting next to the modules in
# the mounted forms dir. For live .fmb/.pll conversion, run the server on a host
# that has an Oracle Forms installation (ORACLE_HOME set) instead.
FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539

LABEL org.opencontainers.image.source="https://github.com/aoreshkov/oracle-forms-mcp" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="MCP server serving Oracle Forms module content (.fmb/.mmb/.pll/.olb) — copy-mode only, no Oracle tools bundled" \
      io.modelcontextprotocol.server.name="io.github.aoreshkov/oracle-forms-mcp"

# Explicit numeric UID so Kubernetes `runAsNonRoot` can prove non-root from the
# image (a bare username is not verifiable at admission time).
#
# Pre-create + chown the cache tree BEFORE declaring the VOLUME: a mount point that
# already exists in the image lets an anonymous/named volume inherit its ownership
# on first init. Without this, Docker creates the missing mount point root-owned and
# the non-root process (uid 10001) can't write — annotations and the module cache
# both fail with AccessDenied. ENV HOME makes `user.home` deterministic even if the
# runtime leaves HOME unset (the JVM would otherwise fall back to getpwuid).
RUN useradd --create-home --uid 10001 mcp \
 && mkdir -p /home/mcp/.cache/oracle-forms-mcp/annotations \
 && chown -R 10001:10001 /home/mcp/.cache
ENV HOME=/home/mcp
USER 10001

COPY --chown=mcp server/build/install/server /app

# Parsed module indexes + the durable annotation store; mount a volume here to
# persist across runs. Anonymous/named volumes inherit the 10001 ownership set above.
# A BIND MOUNT does NOT — Docker never chowns bind targets — so the host directory
# must be writable by uid 10001 (chown it, or run with --user $(id -u)); otherwise
# redirect writes with --cache-dir/--annotations-dir onto a writable path.
VOLUME ["/home/mcp/.cache"]

ENTRYPOINT ["/app/bin/server"]
# The forms dir must be supplied at runtime. Mount pre-converted modules and pass
# --forms-dir, e.g.:
#   docker run -i -v /path/to/forms:/forms ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
# To persist the cache + annotations across runs, add a volume. An anonymous/named
# volume just works:
#   docker run -i -v ofmcp-cache:/home/mcp/.cache -v /path/to/forms:/forms ... --forms-dir /forms
# A host BIND MOUNT must be writable by uid 10001 (Docker won't chown it):
#   chown 10001 /host/cache   # once, on the host
#   docker run -i -v /host/cache:/home/mcp/.cache -v /path/to/forms:/forms ... --forms-dir /forms
# stdio is the default transport; override with: --transport http --port 3000
CMD ["--help"]
