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
FROM eclipse-temurin:25-jre@sha256:d0eb1b9018b3044da1b7346f39e945f71095749853d69a3aa16b8c99dad9bb45

LABEL org.opencontainers.image.source="https://github.com/aoreshkov/oracle-forms-mcp" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="MCP server serving Oracle Forms module content (.fmb/.mmb/.pll/.olb) — copy-mode only, no Oracle tools bundled" \
      io.modelcontextprotocol.server.name="io.github.aoreshkov/oracle-forms-mcp"

RUN useradd --create-home mcp
USER mcp

COPY --chown=mcp server/build/install/server /app

# Parsed module indexes; mount a volume here to persist across runs.
VOLUME ["/home/mcp/.cache"]

ENTRYPOINT ["/app/bin/server"]
# The forms dir must be supplied at runtime. Mount pre-converted modules and pass
# --forms-dir, e.g.:
#   docker run -i -v /path/to/forms:/forms ghcr.io/aoreshkov/oracle-forms-mcp --forms-dir /forms
# stdio is the default transport; override with: --transport http --port 3000
CMD ["--help"]
