package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey

/**
 * Port that turns a converted module file into a structured [ModuleIndex].
 *
 * Intentionally **synchronous and pure-ish**: the JVM implementation streams the XML with StAX
 * (or line-scans a `.pld`) — CPU/IO-bound work callers offload to a background dispatcher. It
 * also writes the decoded PL/SQL sidecars under `<moduleCacheDir>/plsql/` as it parses, so
 * trigger/program-unit bodies never need a second XML pass.
 */
public interface ModuleParser {

    /**
     * Parses [convertedFile] (absolute path), writing PL/SQL sidecars under [moduleCacheDir], and
     * returns the index for [key]. The returned index carries placeholder source metadata — the
     * service stamps [ModuleIndex.sourceFile]/[ModuleIndex.fingerprint] afterwards, since only it
     * knows which file the fingerprint anchors to.
     */
    public fun parse(key: ModuleKey, convertedFile: String, moduleCacheDir: String): ModuleIndex
}
