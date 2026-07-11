package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ScannedModule

/**
 * Port that discovers modules in the configured forms directory (non-recursive — the flat layout
 * is the common Forms convention). Pairs each module's binary with its pre-converted sibling when
 * both exist, so the service can pick a fingerprint source matching the active converter.
 */
public interface FormsDirectoryScanner {

    /** All modules currently present, sorted by canonical key. */
    public suspend fun scan(): List<ScannedModule>
}
